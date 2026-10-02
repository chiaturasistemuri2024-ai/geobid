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
                try {
                    int code = 1;
                    for (int attempt = 0; attempt < 2 && ntripRun; attempt++) {
                        code = ntripOnce(host, port, mount, user, pass, attempt == 1);
                        if (code != 1) break;
                        closeNtripSocket();
                    }
                    if (code == 1 && ntripRun) {
                        ntripStatus("error", "კასტერი არ პასუხობს (v1 და v2 ცდა)", 0);
                    }
                } catch (Exception e) {
                    if (ntripRun) ntripStatus("error", String.valueOf(e.getMessage()), 0);
                } finally {
                    closeNtripSocket();
                    if (ntripRun) {
                        ntripRun = false;
                        ntripStatus("closed", null, 0);
                    }
                }
            }
        });
        ntripThread.start();
        call.resolve();
    }

    /** 0 = სესია დასრულდა, 1 = პასუხი არ მოვიდა (ვცადოთ სხვა პროტოკოლი), 2 = საბოლოო შეცდომა (ლოგინი/მაუნთფოინთი). */
    private int ntripOnce(String host, int port, String mount, String user, String pass, boolean v2) throws Exception {
        final String tag = v2 ? "v2" : "v1";
        ntripStatus("connecting", tag + " · TCP…", 0);
        final Socket s = new Socket();
        s.connect(new InetSocketAddress(host.trim(), port), 10000);
        s.setSoTimeout(8000);
        ntripSock = s;
        final OutputStream os = s.getOutputStream();
        InputStream is = s.getInputStream();

        String auth = Base64.encodeToString((user + ":" + pass).getBytes("UTF-8"), Base64.NO_WRAP);
        String req;
        if (v2) {
            req = "GET /" + mount.trim() + " HTTP/1.1\r\n"
                + "Host: " + host.trim() + ":" + port + "\r\n"
                + "Ntrip-Version: Ntrip/2.0\r\n"
                + "User-Agent: NTRIP SaveleAzomva/1.0\r\n"
                + "Authorization: Basic " + auth + "\r\n"
                + "Connection: close\r\n\r\n";
        } else {
            req = "GET /" + mount.trim() + " HTTP/1.0\r\n"
                + "User-Agent: NTRIP SaveleAzomva/1.0\r\n"
                + "Accept: */*\r\n"
                + "Authorization: Basic " + auth + "\r\n"
                + "Connection: close\r\n\r\n";
        }
        os.write(req.getBytes("US-ASCII"));
        os.flush();
        ntripStatus("connecting", tag + " · მოთხოვნა გაიგზავნა, ველოდები პასუხს…", 0);
        String g0 = lastGga;
        if (g0 != null) {
            os.write((g0 + "\r\n").getBytes("US-ASCII"));
            os.flush();
        }

        String first;
        try {
            first = readLine(is);
        } catch (SocketTimeoutException te) {
            ntripStatus("connecting", tag + " · პასუხი არ მოვიდა (8 წმ)", 0);
            return 1;
        }
        if (first == null) {
            ntripStatus("connecting", tag + " · კასტერმა კავშირი დახურა", 0);
            return 1;
        }
        boolean chunked = false;
        if (first.startsWith("ICY 200")) {
            // NTRIP v1: მონაცემები მაშინვე იწყება
        } else if (first.startsWith("HTTP/") && first.contains(" 200")) {
            String h;
            while ((h = readLine(is)) != null && !h.isEmpty()) {
                String l = h.toLowerCase();
                if (l.contains("transfer-encoding") && l.contains("chunked")) chunked = true;
            }
        } else {
            String m = tag + " · პასუხი: " + first;
            boolean fatal = false;
            if (first.contains("401")) { m = "ლოგინი ან პაროლი არასწორია (401)"; fatal = true; }
            else if (first.contains("403")) { m = "წვდომა აკრძალულია (403)"; fatal = true; }
            else if (first.toUpperCase().contains("SOURCETABLE")) { m = "მაუნთფოინთი არ მოიძებნა (SOURCETABLE)"; fatal = true; }
            ntripStatus(fatal ? "error" : "connecting", m, 0);
            if (fatal) {
                ntripRun = false;
                return 2;
            }
            return 1;
        }

        ntripStatus("connected", tag + (chunked ? " (chunked)" : ""), 0);
        s.setSoTimeout(15000);
        // GGA-ს პერიოდული გაგზავნა (VRS)
        final Thread gt = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (ntripRun && ntripSock == s) {
                        Thread.sleep(5000);
                        String g = lastGga;
                        if (g != null) {
                            synchronized (s) {
                                os.write((g + "\r\n").getBytes("US-ASCII"));
                                os.flush();
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        });
        gt.start();

        byte[] buf = new byte[2048];
        int[] remaining = new int[] { 0 };
        long total = 0;
        long lastStatus = 0;
        int idle = 0;
        while (ntripRun) {
            int n;
            try {
                n = chunked ? readChunk(is, buf, remaining) : is.read(buf);
            } catch (SocketTimeoutException te) {
                idle++;
                ntripStatus("connected", tag + " · კორექციები არ მოდის (" + (idle * 15) + " წმ)", total);
                if (idle >= 4) return 1;
                continue;
            }
            if (n < 0) break;
            idle = 0;
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
            long now = System.currentTimeMillis();
            if (now - lastStatus > 1000) {
                lastStatus = now;
                ntripStatus("connected", tag, total);
            }
        }
        return 0;
    }

    private int readChunk(InputStream is, byte[] buf, int[] remaining) throws Exception {
        if (remaining[0] == 0) {
            String l = readLine(is);
            if (l == null) return -1;
            l = l.trim();
            if (l.isEmpty()) {
                l = readLine(is);
                if (l == null) return -1;
                l = l.trim();
            }
            int sz = Integer.parseInt(l.split(";")[0].trim(), 16);
            if (sz == 0) return -1;
            remaining[0] = sz;
        }
        int n = is.read(buf, 0, Math.min(buf.length, remaining[0]));
        if (n > 0) remaining[0] -= n;
        return n;
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
