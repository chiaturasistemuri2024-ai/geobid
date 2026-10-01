package ge.kadastr.savele;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Build;
import android.util.Base64;

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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Set;
import java.util.UUID;

/**
 * კლასიკური Bluetooth (SPP) GNSS მიმღები: კითხულობს NMEA ხაზებს და აწვდის ვებ-გვერდს;
 * NTRIP კლიენტი: კორექციებს (RTCM) ჩამოტვირთავს და მიმღებს Bluetooth-ით გადასცემს, GGA-ს უკან აგზავნის კასტერს (VRS).
 *
 * JS: listPaired(), connect({address}), disconnect(), ntripStart({host,port,mount,user,pass}), ntripStop()
 * მოვლენები: "nmea" {line}, "status" {state,message}, "ntrip" {state,message,bytes}
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
    private final Object writeLock = new Object();

    private volatile Socket ntripSock;
    private volatile boolean ntripRun = false;
    private Thread ntripThread;
    private volatile String lastGga = null;

    private boolean needsPermission() {
        return Build.VERSION.SDK_INT >= 31 && getPermissionState("bt") != PermissionState.GRANTED;
    }

    // ───────────── Bluetooth ─────────────

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
                                    if (line.charAt(0) == '$' && line.length() > 6 && line.regionMatches(3, "GGA", 0, 3)) {
                                        lastGga = line;
                                    }
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
        ntripStopInternal();
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

    // ───────────── NTRIP ─────────────

    @PluginMethod
    public void ntripStart(PluginCall call) {
        final String host = call.getString("host");
        final String mount = call.getString("mount");
        final String user = call.getString("user", "");
        final String pass = call.getString("pass", "");
        final int port = call.getInt("port", 2101);
        if (host == null || host.trim().isEmpty() || mount == null || mount.trim().isEmpty()) {
            call.reject("NTRIP: მისამართი ან მაუნთფოინთი არ არის შევსებული");
            return;
        }
        ntripStopInternal();
        ntripRun = true;
        ntripThread = new Thread(new Runnable() {
            @Override
            public void run() {
                long total = 0;
                try {
                    ntripStatus("connecting", null, 0);
                    Socket s = new Socket();
                    s.connect(new InetSocketAddress(host.trim(), port), 10000);
                    s.setSoTimeout(10000);
                    ntripSock = s;
                    OutputStream os = s.getOutputStream();
                    InputStream is = s.getInputStream();

                    String auth = Base64.encodeToString((user + ":" + pass).getBytes("UTF-8"), Base64.NO_WRAP);
                    String req = "GET /" + mount.trim() + " HTTP/1.0\r\n"
                        + "User-Agent: NTRIP SaveleAzomva/1.0\r\n"
                        + "Accept: */*\r\n"
                        + "Authorization: Basic " + auth + "\r\n"
                        + "Connection: close\r\n\r\n";
                    os.write(req.getBytes("US-ASCII"));
                    os.flush();

                    // პასუხის პირველი ხაზი
                    String first = readLine(is);
                    if (first == null) {
                        ntripStatus("error", "კასტერმა არ უპასუხა", 0);
                        ntripRun = false;
                        return;
                    }
                    if (first.startsWith("ICY 200")) {
                        // NTRIP v1: მონაცემები მაშინვე იწყება
                    } else if (first.startsWith("HTTP/") && first.contains(" 200")) {
                        // HTTP ჰედერების ბოლომდე წაკითხვა
                        String h;
                        while ((h = readLine(is)) != null && !h.isEmpty()) {
                            // ჰედერები გამოტოვებულია
                        }
                    } else {
                        String m = first;
                        if (first.contains("401")) m = "ლოგინი ან პაროლი არასწორია (401)";
                        else if (first.contains("403")) m = "წვდომა აკრძალულია (403)";
                        else if (first.toUpperCase().contains("SOURCETABLE")) m = "მაუნთფოინთი არ მოიძებნა (SOURCETABLE)";
                        ntripStatus("error", m, 0);
                        ntripRun = false;
                        return;
                    }

                    ntripStatus("connected", null, 0);
                    s.setSoTimeout(1000);
                    byte[] buf = new byte[2048];
                    long lastGgaSent = 0;
                    long lastStatus = 0;
                    while (ntripRun) {
                        long now = System.currentTimeMillis();
                        String g = lastGga;
                        if (g != null && now - lastGgaSent > 5000) {
                            os.write((g + "\r\n").getBytes("US-ASCII"));
                            os.flush();
                            lastGgaSent = now;
                        }
                        int n;
                        try {
                            n = is.read(buf);
                        } catch (SocketTimeoutException te) {
                            continue;
                        }
                        if (n < 0) break;
                        if (n > 0) {
                            BluetoothSocket b = socket;
                            if (b != null) {
                                synchronized (writeLock) {
                                    b.getOutputStream().write(buf, 0, n);
                                    b.getOutputStream().flush();
                                }
                            }
                            total += n;
                        }
                        if (now - lastStatus > 1000) {
                            lastStatus = now;
                            ntripStatus("connected", null, total);
                        }
                    }
                } catch (Exception e) {
                    if (ntripRun) ntripStatus("error", e.getMessage(), total);
                } finally {
                    closeNtripSocket();
                    if (ntripRun) {
                        ntripRun = false;
                        ntripStatus("closed", null, total);
                    }
                }
            }
        });
        ntripThread.start();
        call.resolve();
    }

    private String readLine(InputStream is) throws Exception {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = is.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r' && sb.length() < 1000) sb.append((char) c);
        }
        if (c < 0 && sb.length() == 0) return null;
        return sb.toString();
    }

    @PluginMethod
    public void ntripStop(PluginCall call) {
        ntripStopInternal();
        call.resolve();
    }

    private void ntripStopInternal() {
        ntripRun = false;
        closeNtripSocket();
    }

    private void closeNtripSocket() {
        try {
            Socket s = ntripSock;
            if (s != null) s.close();
        } catch (Exception ignored) {
        }
        ntripSock = null;
    }

    private void ntripStatus(String state, String message, long bytes) {
        JSObject o = new JSObject();
        o.put("state", state);
        if (message != null) o.put("message", message);
        o.put("bytes", bytes);
        notifyListeners("ntrip", o);
    }

    @Override
    protected void handleOnDestroy() {
        ntripStopInternal();
        stopInternal();
    }
}
