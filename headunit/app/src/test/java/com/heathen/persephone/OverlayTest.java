package com.heathen.persephone;
import android.content.*;
import android.content.pm.ProviderInfo;
import android.location.LocationManager;
import android.net.Uri;
import android.net.wifi.*;
import android.os.*;
import android.provider.Settings;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.shadows.*;
import java.io.*;
import java.time.*;
import java.util.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class OverlayTest {
    Context context;
    @Before public void prepare(){context=RuntimeEnvironment.getApplication();context.getSharedPreferences("gate",0).edit().clear().commit();}
    @Test public void hourLeasePersistsExpiresAndLocksOnRebootOrPinChange(){
        PinGate gate=new PinGate(context);assertFalse(gate.unlocked());assertTrue(gate.verify("9818"));assertTrue(new PinGate(context).unlocked());
        ShadowSystemClock.advanceBy(Duration.ofMinutes(59));assertTrue(gate.unlocked());
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1));assertFalse(gate.unlocked());assertEquals(0,gate.unlockRemainingMs());
        assertTrue(gate.verify("9818"));gate.lock();assertFalse(new PinGate(context).unlocked());
        assertTrue(gate.verify("9818"));Settings.Global.putInt(context.getContentResolver(),"boot_count",1);assertFalse(gate.unlocked());
        assertTrue(gate.verify("9818"));gate.set("123456",false);assertFalse(gate.unlocked());
    }
    @Test public void pingNotesSurviveObservationsAndRejectMockedCoordinates()throws Exception{
        PingStore store=PingStore.get(context);JSONObject row=new JSONObject().put("source","ANDROID").put("radio","Wi-Fi").put("address","AA:BB:CC:00:00:04").put("name","Falcon test").put("time",Instant.now().toString()).put("rssi",-60).put("latitude",36).put("longitude",-115).put("accuracy",5);
        DetectionAnnotator.annotate(row);String id=store.observe(row);store.nickname(id,"Workshop camera","Owner consent recorded");
        store.phoneFix(id,36.01,-115.01,6,false);store.observe(row.put("rssi",-55));assertEquals("Workshop camera",store.getPing(id).getString("nickname"));assertEquals(36.01,PingStore.point(store.getPing(id)).lat,.000001);
        assertEquals("user_captured_phone_position",store.getPing(id).getJSONObject("phone_fix").getString("position_role"));
        try{store.phoneFix(id,36,-115,5,true);fail();}catch(IllegalArgumentException expected){}
        String demo=store.observe(row.put("source","DEMO"));assertNotEquals(id,demo);store.flush();
        assertTrue(new File(context.getFilesDir(),"pings.json").length()>0);
    }
    @Test public void annotationsExportWithGpsRoleAndPhotoCopy()throws Exception {
        HeadunitTest.TestDocuments provider=new HeadunitTest.TestDocuments();ProviderInfo info=new ProviderInfo();info.authority="persephone.annotations.documents";info.readPermission="android.permission.MANAGE_DOCUMENTS";info.writePermission="android.permission.MANAGE_DOCUMENTS";info.exported=true;info.grantUriPermissions=true;provider.attachInfo(context,info);ShadowContentResolver.registerProviderInternal(info.authority,provider);
        PingStore store=PingStore.get(context);String id=store.observe(new JSONObject().put("source","ANDROID").put("radio","BLE").put("address","AA:BB:CC:00:00:99"));store.nickname(id,"Export fixture","Phone location only");store.phoneFix(id,36,-115,5,false);
        String photo=UUID.randomUUID()+".jpg";try(FileOutputStream out=new FileOutputStream(new File(store.photos,photo))){out.write(new byte[]{1,2,3});}store.photo(id,photo);
        store.export(android.provider.DocumentsContract.buildTreeDocumentUri(info.authority,"root"));File folder=Arrays.stream(provider.root.listFiles()).filter(File::isDirectory).findFirst().get();assertTrue(new File(folder,"pings.json").exists());assertTrue(new File(folder,photo).exists());
        JSONObject geo=new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(folder,"pings.geojson").toPath()),java.nio.charset.StandardCharsets.UTF_8));JSONArray features=geo.getJSONArray("features");boolean found=false;for(int i=0;i<features.length();i++){JSONObject feature=features.getJSONObject(i);if(feature.getJSONObject("properties").optString("id").equals(id)){found=true;assertEquals(-115,feature.getJSONObject("geometry").getJSONArray("coordinates").getDouble(0),.001);assertEquals("user_captured_phone_position",feature.getJSONObject("properties").getJSONObject("phone_fix").getString("position_role"));}}assertTrue(found);assertTrue(new File(store.photos,photo).exists());
    }
    @Test public void photoProviderRejectsTraversalAndKeepsAttachmentPrivate()throws Exception{
        PhotoProvider provider=new PhotoProvider();ProviderInfo info=new ProviderInfo();info.authority=context.getPackageName()+".photos";info.grantUriPermissions=true;provider.attachInfo(context,info);
        String name=UUID.randomUUID()+".jpg";Uri chosen=PhotoProvider.uri(context,name);
        try(ParcelFileDescriptor descriptor=provider.openFile(chosen,"w")){assertNotNull(descriptor);}
        try{provider.openFile(PhotoProvider.uri(context,"../gate.xml"),"r");fail();}catch(FileNotFoundException expected){}
        PingStore store=PingStore.get(context);String id=store.observe(new JSONObject().put("radio","BLE").put("address","AA:BB:CC:00:00:05"));store.photo(id,name);assertEquals(1,store.getPing(id).getJSONArray("photos").length());store.removePhoto(id,name);assertFalse(new File(store.photos,name).exists());
    }
    @Test @Config(sdk=35) public void approvedSavedOverlayNeverStartsRadioAndRejectsUnknownTicket()throws Exception{
        assertTrue(new PinGate(context).verify("9818"));ShadowSettings.setCanDrawOverlays(true);
        OverlayService.startApproved(context,false,false);Intent start=Shadows.shadowOf((android.app.Application)context).getNextStartedService();assertNotNull(start);
        ServiceController<OverlayService> controller=Robolectric.buildService(OverlayService.class).create();try{
            OverlayService service=controller.get();service.onStartCommand(start,0,1);assertTrue(OverlayService.active());
            assertFalse((boolean)field(service,"live"));assertNull(field(service,"map"));assertFalse((boolean)field(field(service,"scanner"),"running"));
            assertNotNull(Shadows.shadowOf(service).getLastForegroundNotification());
            new PinGate(context).lock();service.onStartCommand(new Intent().setAction(OverlayService.SHOW),0,2);assertTrue(Shadows.shadowOf(service).isStoppedBySelf());
        }finally{controller.destroy();}
        controller=Robolectric.buildService(OverlayService.class).create();try{
            controller.get().onStartCommand(new Intent().setAction(OverlayService.START).putExtra("ticket","invented"),0,1);assertFalse(OverlayService.active());
        }finally{controller.destroy();}
    }
    @Test @Config(sdk=35) public void wifiListenerReportsStaleCacheAndConsumesFreshBroadcast()throws Exception{
        WifiManager wifi=(WifiManager)context.getSystemService(Context.WIFI_SERVICE);wifi.setWifiEnabled(true);
        LocationManager locations=(LocationManager)context.getSystemService(Context.LOCATION_SERVICE);Shadows.shadowOf(locations).setProviderEnabled(LocationManager.GPS_PROVIDER,true);
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2));ScanResult old=new ScanResult();old.BSSID="AA:BB:CC:00:00:06";old.SSID="Training AP";old.timestamp=1;old.level=-60;old.capabilities="[ESS]";
        Shadows.shadowOf(wifi).setScanResults(Collections.singletonList(old));List<JSONObject> rows=new ArrayList<>();RadioScanner scanner=new RadioScanner(context,new RadioScanner.Sink(){public void observation(JSONObject row){rows.add(row);}public void status(String text){}});
        scanner.start();assertTrue(scanner.wifiStatus().contains("stale"));assertEquals(0,rows.size());
        old.timestamp=SystemClock.elapsedRealtime()*1000;context.sendBroadcast(new Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION).putExtra(WifiManager.EXTRA_RESULTS_UPDATED,true));Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(1,rows.size());assertTrue(scanner.wifiStatus().contains("1 fresh"));scanner.stop();
    }
    @Test @Config(sdk=35) public void periodicWifiRequestsThirtySecondsAndStopsWithLease()throws Exception {
        PinGate gate=new PinGate(context);gate.set("123456",false);assertTrue(gate.verify("123456"));
        WifiManager wifi=(WifiManager)context.getSystemService(Context.WIFI_SERVICE);wifi.setWifiEnabled(true);Shadows.shadowOf(wifi).setStartScanSucceeds(true);
        List<String> messages=new ArrayList<>();List<JSONObject> observations=new ArrayList<>();RadioScanner scanner=new RadioScanner(context,new RadioScanner.Sink(){public void observation(JSONObject row){observations.add(row);}public void status(String text){messages.add(text);}});
        scanner.start();messages.clear();scanner.automaticWifi(true);Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(1,messages.stream().filter(x->x.startsWith("Wi-Fi fresh scan requested")).count());messages.clear();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(29));assertTrue(messages.isEmpty());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));assertEquals(1,messages.size());messages.clear();
        Shadows.shadowOf(wifi).setStartScanSucceeds(false);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30));assertTrue(messages.get(0).contains("throttled"));assertTrue(observations.isEmpty());messages.clear();
        gate.lock();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30));assertFalse(scanner.automaticWifi());assertTrue(messages.get(0).contains("stopped"));messages.clear();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2));assertTrue(messages.isEmpty());scanner.stop();
    }
    private static Object field(Object value,String name)throws Exception{java.lang.reflect.Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
}
