package com.heathen.persephone;

import android.content.*;
import android.content.pm.ProviderInfo;
import android.database.*;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w960dp-h480dp-land")
public class HeadunitTest {
    private Context context;
    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("gate", 0).edit().clear().commit();
        context.getSharedPreferences("settings", 0).edit().clear().commit();
    }
    @Test public void pinDefaultChangeAndPersistentCooldown() {
        PinGate gate = new PinGate(context);
        assertTrue(gate.isDefault()); assertTrue(gate.verify("9818"));
        gate.set("123456", false);
        assertFalse(gate.isDefault()); assertFalse(gate.verify("9818")); assertTrue(new PinGate(context).verify("123456"));
        for (int i = 0; i < 5; i++) assertFalse(gate.verify("000000"));
        assertTrue(new PinGate(context).remainingMs() > 0);
        assertFalse(new PinGate(context).verify("123456"));
    }
    @Test public void sdProviderExportsAllEventsAndNeutralizesSsidFormulas() throws Exception {
        TestDocuments provider = new TestDocuments(); ProviderInfo info = new ProviderInfo();
        info.authority = "persephone.test.documents"; info.exported = true; info.grantUriPermissions = true;
        info.readPermission = "android.permission.MANAGE_DOCUMENTS"; info.writePermission = "android.permission.MANAGE_DOCUMENTS";
        provider.attachInfo(context, info); ShadowContentResolver.registerProviderInternal(info.authority, provider);
        SessionStore store = new SessionStore(context); SessionStore.Session session = store.new Session(false);
        for (int i = 0; i < 600; i++) session.append(new JSONObject().put("source", "ANDROID").put("time", "2026-10-13T13:00:00Z").put("radio", "Wi-Fi").put("address", "AA:BB:CC:00:00:01").put("name", "=sample,\"SSID\"").put("rssi", -70).put("latitude",36.1).put("longitude",-115.2).put("accuracy",5));
        session.close();
        Uri tree = DocumentsContract.buildTreeDocumentUri(info.authority, "root"); store.export(session.file, tree);
        File csv = Arrays.stream(provider.root.listFiles()).filter(f -> f.getName().endsWith(".csv")).findFirst().get();
        String data = new String(Files.readAllBytes(csv.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(601, data.lines().count()); assertTrue(data.contains("\"'=sample,\"\"SSID\"\"\"")); assertTrue(data.contains(",-70,"));
        File json = Arrays.stream(provider.root.listFiles()).filter(f -> f.getName().endsWith(".jsonl")).findFirst().get();
        assertArrayEquals(Files.readAllBytes(session.file.toPath()), Files.readAllBytes(json.toPath()));
        File geo=Arrays.stream(provider.root.listFiles()).filter(f->f.getName().endsWith(".geojson")).findFirst().get();
        JSONArray features=new JSONObject(new String(Files.readAllBytes(geo.toPath()),java.nio.charset.StandardCharsets.UTF_8)).getJSONArray("features");assertEquals(600,features.length());assertEquals(-115.2,features.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates").getDouble(0),.00001);assertEquals("receiver_observation",features.getJSONObject(0).getJSONObject("properties").getString("position_role"));
        provider.failCsv = true;
        try { store.export(session.file, tree); fail("removed / full media must fail"); } catch (Exception expected) { assertTrue(session.file.exists()); }
        assertEquals(1, provider.deleted);
    }
    @Test public void demoNeverEnablesConnectedTrainingAndStopsOnLeave() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity app = controller.get(); new PinGate(app).verify("9818");
            invoke(app, "startSession", new Class<?>[]{boolean.class}, true);
            invoke(app, "generateDemo", new Class<?>[]{});
            assertTrue((boolean) field(app, "running")); assertTrue((boolean) field(app, "demo"));
            JSONObject fake = new JSONObject().put("radio", "BLE").put("address", "AA:BB:CC:00:00:01").put("connectable", true).put("seen_elapsed", SystemClock.elapsedRealtime());
            invoke(app, "labConsent", new Class<?>[]{JSONObject.class}, fake);
            assertNull(field(app, "gatt")); assertNotNull(ShadowAlertDialog.getLatestAlertDialog());
            controller.pause().stop(); assertFalse((boolean) field(app, "running")); assertNull(field(app, "session"));
            controller.start().resume(); assertFalse((boolean) field(app, "running"));
        }
    }
    @Test public void changePinApprovalExpiresOnAppLeave() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity app = controller.get(); new PinGate(app).verify("9818"); invoke(app, "changePin", new Class<?>[]{});
            android.app.AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            controller.pause().stop().start().resume();
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();
            assertFalse(dialog.isShowing()); assertTrue(new PinGate(context).isDefault());
        }
    }
    @Test public void liveWindowIsBoundedWhileRecordingContinues() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity app = controller.get(); new PinGate(app).verify("9818"); invoke(app, "startSession", new Class<?>[]{boolean.class}, true);
            for (int i = 0; i < 600; i++) app.observation(new JSONObject().put("radio", "BLE").put("address", "sample-" + i).put("rssi", -50));
            assertEquals(512, ((Map<?, ?>) field(app, "live")).size()); assertEquals(600, field(app, "events"));
        }
    }
    @Test public void usbTelemetryRejectsMalformedTargetsAndImpossibleSignal() throws Exception {
        assertFalse(UsbReceiver.valid(new JSONObject().put("radio", "BLE").put("address", "bad").put("rssi", -50)));
        assertFalse(UsbReceiver.valid(new JSONObject().put("radio", "BLE").put("address", "AA:BB:CC:00:00:01").put("rssi", 10)));
        assertFalse(UsbReceiver.valid(new JSONObject().put("radio", "BLE").put("address", "AA:BB:CC:00:00:01")));
        assertTrue(UsbReceiver.valid(new JSONObject().put("radio", "BLE").put("address", "AA:BB:CC:00:00:01").put("rssi", -50)));
    }
    @Test public void initialPinBlocksActiveWifiRequest() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity app = controller.get(); new PinGate(app).verify("9818");
            invoke(app, "wifiConsent", new Class<?>[]{}); assertFalse((boolean) field(app, "running"));
        }
    }
    private static Object field(Object obj, String name) throws Exception { Field f = obj.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(obj); }
    private static Object invoke(Object obj, String name, Class<?>[] signature, Object... args) throws Exception { Method m = obj.getClass().getDeclaredMethod(name, signature); m.setAccessible(true); return m.invoke(obj, args); }
    public static class TestDocuments extends DocumentsProvider {
        File root; boolean failCsv; int deleted;
        @Override public boolean onCreate() { try { root = Files.createTempDirectory("persephone-sd-provider").toFile(); return true; } catch (IOException e) { throw new RuntimeException(e); } }
        @Override public boolean isChildDocument(String parent, String child) { return parent.equals("root") && new File(root, child).exists(); }
        @Override public Cursor queryRoots(String[] projection) { return new MatrixCursor(new String[]{DocumentsContract.Root.COLUMN_ROOT_ID}); }
        @Override public Cursor queryDocument(String id, String[] projection) { return new MatrixCursor(new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}); }
        @Override public Cursor queryChildDocuments(String id, String[] projection, String sort) { return new MatrixCursor(new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}); }
        @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
            if (failCsv && name.endsWith(".csv")) throw new FileNotFoundException("simulated full SD card");
            File directory=parent.equals("root")?root:new File(root,parent);
            File file = new File(directory, name); while (file.exists()) file = new File(directory, "copy-" + file.getName());
            try { if(mime.equals(DocumentsContract.Document.MIME_TYPE_DIR)){if(!file.mkdir())throw new IOException("mkdir failed");}else file.createNewFile(); } catch (IOException e) { throw new FileNotFoundException(e.getMessage()); } return root.toPath().relativize(file.toPath()).toString();
        }
        @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException { return ParcelFileDescriptor.open(new File(root, id), ParcelFileDescriptor.parseMode(mode)); }
        @Override public void deleteDocument(String id) throws FileNotFoundException { if (!new File(root, id).delete()) throw new FileNotFoundException(id); deleted++; }
    }
}
