package com.zak.budringer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends the Google Fast Pair "Ring" device action over the Fast Pair
 * Message Stream (RFCOMM) to a bonded Fast Pair headset.
 *
 * Message format: [group:1][code:1][length:2, big-endian][data:length]
 *   Ring = group 0x04 (Device Action), code 0x01
 *   data[0] = components bitmask: 0x01 right, 0x02 left, 0x04 case (experimental), 0x00 stop
 *   data[1] = optional timeout in seconds
 * Replies: ACK = 0xFF 0x01, NAK = 0xFF 0x02.
 */
@SuppressLint("MissingPermission")
public class MainActivity extends Activity {

    private static final UUID MESSAGE_STREAM_UUID =
            UUID.fromString("df21fe2c-2515-4fdb-8886-f12c4d67927c");
    private static final int REQ_PERMS = 1;
    private static final int RING_TIMEOUT_SECONDS = 30;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<BluetoothDevice> devices = new ArrayList<>();

    private BluetoothAdapter adapter;
    private BluetoothSocket socket;
    private Spinner deviceSpinner;
    private TextView logView;
    private ScrollView logScroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        BluetoothManager bm = getSystemService(BluetoothManager.class);
        adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null) {
            log("This phone has no Bluetooth adapter.");
            return;
        }
        if (hasPermission()) {
            loadDevices();
        } else {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_PERMS);
        }
    }

    private boolean hasPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_PERMS && hasPermission()) {
            loadDevices();
        } else {
            log("Nearby devices permission is required. Allow it in Settings > Apps > Bud Ringer.");
        }
    }

    // ---------- UI ----------

    private void buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Bud Ringer");
        title.setTextSize(24);
        root.addView(title);

        TextView warn = new TextView(this);
        warn.setText("Take the buds out of your ears before ringing. It is loud.\n"
                + "A bud must be in the case (or out) and connected for anything to ring.");
        warn.setPadding(0, dp(8), 0, dp(8));
        root.addView(warn);

        deviceSpinner = new Spinner(this);
        root.addView(deviceSpinner);

        root.addView(button("Ring both buds", 0x03));
        root.addView(button("Ring left", 0x02));
        root.addView(button("Ring right", 0x01));
        root.addView(button("Ring case (experimental)", 0x04));
        root.addView(button("Ring everything", 0x07));
        root.addView(button("Stop ringing", 0x00));

        Button disconnect = new Button(this);
        disconnect.setText("Disconnect");
        disconnect.setOnClickListener(v -> io.execute(this::closeSocket));
        root.addView(disconnect);

        logView = new TextView(this);
        logView.setTextIsSelectable(true);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logScroll = new ScrollView(this);
        logScroll.addView(logView);
        root.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private Button button(String label, int components) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(v -> sendRing(components));
        return b;
    }

    private void loadDevices() {
        devices.clear();
        List<String> names = new ArrayList<>();
        int preselect = 0;
        for (BluetoothDevice d : adapter.getBondedDevices()) {
            String name = d.getName() == null ? d.getAddress() : d.getName();
            if (name.toLowerCase().contains("pixel buds")) preselect = devices.size();
            devices.add(d);
            names.add(name);
        }
        deviceSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, names));
        if (!devices.isEmpty()) deviceSpinner.setSelection(preselect);
        log(devices.isEmpty() ? "No paired devices found." : "Loaded " + devices.size() + " paired devices.");
    }

    // ---------- Bluetooth ----------

    private void sendRing(int components) {
        int pos = deviceSpinner.getSelectedItemPosition();
        if (pos < 0 || pos >= devices.size()) {
            log("Pick your Pixel Buds first.");
            return;
        }
        BluetoothDevice device = devices.get(pos);
        io.execute(() -> {
            try {
                ensureConnected(device);
                byte[] data = components == 0
                        ? new byte[]{0x00}
                        : new byte[]{(byte) components, (byte) RING_TIMEOUT_SECONDS};
                byte[] msg = new byte[4 + data.length];
                msg[0] = 0x04;                       // Device Action group
                msg[1] = 0x01;                       // Ring
                msg[2] = (byte) (data.length >> 8);
                msg[3] = (byte) data.length;
                System.arraycopy(data, 0, msg, 4, data.length);
                OutputStream out = socket.getOutputStream();
                out.write(msg);
                out.flush();
                log("Sent ring 0x" + String.format("%02X", components) + " -> " + hex(msg));
            } catch (IOException e) {
                log("Failed: " + e.getMessage());
                log("Make sure a bud is connected. Try toggling Bluetooth, or force-stop "
                        + "the Pixel Buds app so the channel is free.");
                closeSocket();
            }
        });
    }

    private void ensureConnected(BluetoothDevice device) throws IOException {
        if (socket != null && socket.isConnected()
                && socket.getRemoteDevice().getAddress().equals(device.getAddress())) {
            return;
        }
        closeSocket();
        adapter.cancelDiscovery();
        log("Connecting to " + device.getName() + "...");
        try {
            socket = device.createRfcommSocketToServiceRecord(MESSAGE_STREAM_UUID);
            socket.connect();
        } catch (IOException secureFail) {
            log("Secure connect failed (" + secureFail.getMessage() + "), trying insecure...");
            socket = device.createInsecureRfcommSocketToServiceRecord(MESSAGE_STREAM_UUID);
            socket.connect();
        }
        log("Connected to Fast Pair message stream.");
        startReader(socket);
    }

    private void startReader(BluetoothSocket s) {
        new Thread(() -> {
            byte[] buf = new byte[512];
            try {
                InputStream in = s.getInputStream();
                int n;
                while ((n = in.read(buf)) > 0) {
                    log("Received: " + hex(buf, n) + describe(buf, n));
                }
            } catch (IOException ignored) {
                // Socket closed.
            }
            log("Connection closed.");
        }, "fp-reader").start();
    }

    private static String describe(byte[] b, int n) {
        if (n < 2) return "";
        int g = b[0] & 0xFF, c = b[1] & 0xFF;
        if (g == 0xFF && c == 0x01) return "  (ACK - command accepted)";
        if (g == 0xFF && c == 0x02) return "  (NAK - command rejected)";
        if (g == 0x04 && c == 0x01) return "  (ring state update)";
        if (g == 0x03) return "  (device info)";
        return "";
    }

    private void closeSocket() {
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) { }
            socket = null;
            log("Disconnected.");
        }
    }

    // ---------- helpers ----------

    private void log(String line) {
        ui.post(() -> {
            logView.append(line + "\n");
            logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private static String hex(byte[] b) { return hex(b, b.length); }

    private static String hex(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(String.format("%02X ", b[i]));
        return sb.toString().trim();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        io.execute(this::closeSocket);
        io.shutdown();
        super.onDestroy();
    }
}
