package com.heathen.persephone;

import android.content.Context;
import android.hardware.usb.*;
import android.os.*;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Receive-only CDC-ACM telemetry from the bundled passive ESP32 firmware. */
final class UsbReceiver {
    interface Sink { void row(JSONObject observation); void status(String message); }
    private final UsbManager manager;
    private final Sink sink;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean active;
    private volatile UsbDeviceConnection connection;
    private volatile int generation;
    UsbReceiver(Context context, Sink sink) { manager = (UsbManager) context.getSystemService(Context.USB_SERVICE); this.sink = sink; }
    UsbManager manager() { return manager; }
    List<UsbDevice> devices() {
        List<UsbDevice> devices = new ArrayList<>();
        if (manager != null) for (UsbDevice device : manager.getDeviceList().values()) for (int i = 0; i < device.getInterfaceCount(); i++)
            if (device.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_CDC_DATA) { devices.add(device); break; }
        return devices;
    }
    void start(UsbDevice device) {
        stop(); active = true; final int epoch = ++generation;
        Thread worker = new Thread(() -> read(device, epoch), "persephone-usb"); worker.setDaemon(true); worker.start();
    }
    private void read(UsbDevice device, int epoch) {
        UsbDeviceConnection c = null;
        try {
            c = manager.openDevice(device); if (c == null) throw new IllegalStateException("USB permission/open failed");
            synchronized (this) { if (!active || epoch != generation) return; connection = c; }
            UsbEndpoint input = null, output = null; UsbInterface data = null; int control = -1;
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface it = device.getInterface(i);
                if (it.getInterfaceClass() == UsbConstants.USB_CLASS_COMM) control = it.getId();
                if (it.getInterfaceClass() == UsbConstants.USB_CLASS_CDC_DATA) {
                    data = it;
                    for (int e = 0; e < it.getEndpointCount(); e++) {
                        UsbEndpoint ep = it.getEndpoint(e);
                        if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) { if (ep.getDirection() == UsbConstants.USB_DIR_IN) input = ep; else output = ep; }
                    }
                    break;
                }
            }
            if (input == null || output == null || data == null || control < 0 || !c.claimInterface(data, true)) throw new IllegalStateException("Requires direct CDC-ACM USB; CP210x/CH340 are not supported");
            byte[] lineCoding = {(byte)0x00,(byte)0xc2,0x01,0,0,0,8};
            if (c.controlTransfer(0x21, 0x20, 0, control, lineCoding, 7, 1000) < 0 || c.controlTransfer(0x21, 0x22, 3, control, null, 0, 1000) < 0) throw new IllegalStateException("USB CDC setup failed");
            if (epoch != generation || !active) return;
            // HELLO carries no radio command; reject unrelated firmware before START.
            send(c, output, "HELLO\n");
            byte[] bytes = new byte[4096]; java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream(); boolean verified = false;
            LinkedHashMap<String, Long> seen = new LinkedHashMap<>();
            long deadline = SystemClock.elapsedRealtime() + 5000, lastKeepalive = 0;
            while (active && epoch == generation) {
                int n = c.bulkTransfer(input, bytes, bytes.length, 500);
                if (!verified && SystemClock.elapsedRealtime() > deadline) throw new IllegalStateException("Bundled passive firmware handshake not received");
                if (verified && SystemClock.elapsedRealtime() - lastKeepalive > 1000) { send(c, output, "KEEP\n"); lastKeepalive = SystemClock.elapsedRealtime(); }
                if (n <= 0) continue;
                for (int i = 0; i < n; i++) {
                    char ch = (char) (bytes[i] & 255);
                    if (ch == '\n') {
                        String value = new String(line.toByteArray(), StandardCharsets.UTF_8); line.reset();
                        JSONObject row;
                        try { row = new JSONObject(value); } catch (JSONException malformed) { continue; }
                        if (!verified) {
                            if (row.optInt("persephone_passive") == 1) { verified = true; send(c, output, "START\n"); postStatus("Passive ESP32 USB receiver connected", epoch); }
                            continue;
                        }
                        if (valid(row)) {
                            String key = row.optString("radio") + "/" + row.optString("address"); long now = SystemClock.elapsedRealtime();
                            if (seen.containsKey(key) && now - seen.get(key) < 1000) continue;
                            seen.put(key, now); while (seen.size() > 4096) seen.remove(seen.keySet().iterator().next());
                            List<String> services = new ArrayList<>(); Set<Integer> manufacturers = new HashSet<>();
                            JSONArray uuids = row.optJSONArray("services"), ids = row.optJSONArray("manufacturer_ids");
                            if (uuids != null) for (int j = 0; j < Math.min(uuids.length(), 8); j++) if (uuids.optString(j).length() <= 64) services.add(uuids.optString(j));
                            if (ids != null) for (int j = 0; j < Math.min(ids.length(), 8); j++) manufacturers.add(ids.optInt(j));
                            JSONObject observation = new JSONObject().put("radio", row.optString("radio")).put("address", row.optString("address"))
                                .put("name", row.optString("name")).put("rssi", row.optInt("rssi")).put("frequency", row.optInt("frequency"))
                                .put("security", row.optString("security")).put("services", new JSONArray(services)).put("manufacturer_ids", new JSONArray(manufacturers))
                                .put("connectable", false).put("wildcard_probe",row.optBoolean("wildcard_probe")).put("ie_fingerprint",row.optBoolean("ie_fingerprint"));
                            observation.put("schema", 1).put("source", "ESP32_USB").put("time", Instant.now().toString()).put("seen_elapsed", SystemClock.elapsedRealtime());
                            observation.put("evidence", RfCore.evidence(observation.optString("address"), observation.optString("name"), services, manufacturers));
                            ui.post(() -> { if (active && epoch == generation) sink.row(observation); });
                        }
                    } else if (ch != '\r') {
                        line.write(bytes[i]); if (line.size() > 4096) throw new IllegalStateException("Oversized USB telemetry line");
                    }
                }
            }
            send(c, output, "STOP\n");
        } catch (Exception e) { postStatus("USB receiver stopped: " + e.getMessage(), epoch); }
        finally {
            if (c != null) c.close();
            if (epoch == generation) { connection = null; active = false; }
        }
    }
    static boolean valid(JSONObject row) {
        String radio = row.optString("radio");
        return (radio.equals("BLE") || radio.equals("Wi-Fi")) && RfCore.validMac(row.optString("address"))
            && row.optInt("rssi", 100) >= -127 && row.optInt("rssi", 100) <= 0 && row.optString("name").length() <= 128;
    }
    private void postStatus(String value, int epoch) { ui.post(() -> { if (epoch == generation) sink.status(value); }); }
    private static void send(UsbDeviceConnection c, UsbEndpoint output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        if (c.bulkTransfer(output, bytes, bytes.length, 1000) != bytes.length) throw new IllegalStateException("USB command failed");
    }
    synchronized void stop() {
        active = false; generation++;
        // The firmware's three-second keepalive watchdog stops reception after USB loss.
        UsbDeviceConnection c = connection; connection = null; if (c != null) c.close();
    }
}
