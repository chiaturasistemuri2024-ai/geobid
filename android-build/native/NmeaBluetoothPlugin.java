package ge.kadastr.savele;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Build;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.InputStream;
import java.util.Set;
import java.util.UUID;

/**
 * კლასიკური Bluetooth (SPP) GNSS მიმღები: კითხულობს NMEA ხაზებს და აწვდის ვებ-გვერდს.
 * JS: listPaired() -> {devices:[{name,address}]}, connect({address}), disconnect(),
 * მოვლენები: "nmea" {line}, "status" {state: connected|disconnected|error, message}
 */
@CapacitorPlugin(
    name = "NmeaBluetooth",
    permissions = { @Permission(alias = "bt", strings = { Manifest.permission.BLUETOOTH_CONNECT }) }
)
public class NmeaBluetoothPlugin extends Plugin {

    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private volatile BluetoothSocket socket;
    private volatile boolean running = false;
    private Thread reader;

    private boolean needsPermission() {
        return Build.VERSION.SDK_INT >= 31 && getPermissionState("bt") != PermissionState.GRANTED;
    }

    @PluginMethod
    public void listPaired(PluginCall call) {
        if (needsPermission()) {
            requestPermissionForAlias("bt", call, "permListCallback");
            return;
        }
        doList(call);
    }

    @PermissionCallback
    private void permListCallback(PluginCall call) {
        if (needsPermission()) {
            call.reject("Bluetooth-ის ნებართვა არ არის მიცემული");
            return;
        }
        doList(call);
    }

    private void doList(PluginCall call) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            call.reject("ამ მოწყობილობას Bluetooth არ აქვს");
            return;
        }
        try {
            JSArray arr = new JSArray();
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    JSObject o = new JSObject();
                    o.put("name", d.getName());
                    o.put("address", d.getAddress());
                    arr.put(o);
                }
            }
            JSObject ret = new JSObject();
            ret.put("devices", arr);
            call.resolve(ret);
        } catch (SecurityException e) {
            call.reject("Bluetooth-ის ნებართვა არ არის მიცემული");
        }
    }

    @PluginMethod
    public void connect(PluginCall call) {
        if (needsPermission()) {
            requestPermissionForAlias("bt", call, "permConnectCallback");
            return;
        }
        doConnect(call);
    }

    @PermissionCallback
    private void permConnectCallback(PluginCall call) {
        if (needsPermission()) {
            call.reject("Bluetooth-ის ნებართვა არ არის მიცემული");
            return;
        }
        doConnect(call);
    }

    private void doConnect(PluginCall call) {
        final String address = call.getString("address");
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || address == null || address.isEmpty()) {
            call.reject("მიმღები არ არის არჩეული");
            return;
        }
        stopInternal();
        running = true;
        reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BluetoothDevice dev = adapter.getRemoteDevice(address);
                    try {
                        adapter.cancelDiscovery();
                    } catch (Exception ignored) {
                    }
                    BluetoothSocket s = open(dev);
                    socket = s;
                    status("connected", null);
                    InputStream in = s.getInputStream();
                    byte[] buf = new byte[1024];
                    StringBuilder sb = new StringBuilder();
                    int n;
                    while (running && (n = in.read(buf)) > 0) {
                        for (int i = 0; i < n; i++) {
                            char c = (char) (buf[i] & 0xFF);
                            if (c == '\n') {
                                String line = sb.toString().trim();
                                sb.setLength(0);
                                if (line.length() > 5) {
                                    JSObject o = new JSObject();
                                    o.put("line", line);
                                    notifyListeners("nmea", o);
                                }
                            } else if (c != '\r') {
                                if (sb.length() < 512) sb.append(c);
                            }
                        }
                    }
                } catch (Exception e) {
                    if (running) status("error", e.getMessage());
                } finally {
                    closeSocket();
                    status("disconnected", null);
                }
            }
        });
        reader.start();
        call.resolve();
    }

    /** სამი მცდელობა: უსაფრთხო SPP, არაუსაფრთხო SPP, არხი 1 (ძველი მიმღებებისთვის). */
    private BluetoothSocket open(BluetoothDevice dev) throws Exception {
        try {
            BluetoothSocket s = dev.createRfcommSocketToServiceRecord(SPP);
            s.connect();
            return s;
        } catch (Exception e1) {
            try {
                BluetoothSocket s = dev.createInsecureRfcommSocketToServiceRecord(SPP);
                s.connect();
                return s;
            } catch (Exception e2) {
                BluetoothSocket s = (BluetoothSocket) dev.getClass()
                    .getMethod("createRfcommSocket", new Class[] { int.class })
                    .invoke(dev, 1);
                s.connect();
                return s;
            }
        }
    }

    @PluginMethod
    public void disconnect(PluginCall call) {
        stopInternal();
        call.resolve();
    }

    private void stopInternal() {
        running = false;
        closeSocket();
    }

    private void closeSocket() {
        try {
            BluetoothSocket s = socket;
            if (s != null) s.close();
        } catch (Exception ignored) {
        }
        socket = null;
    }

    private void status(String state, String message) {
        JSObject o = new JSObject();
        o.put("state", state);
        if (message != null) o.put("message", message);
        notifyListeners("status", o);
    }

    @Override
    protected void handleOnDestroy() {
        stopInternal();
    }
}
