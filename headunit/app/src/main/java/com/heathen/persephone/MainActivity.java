package com.heathen.persephone;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity implements RadioScanner.Sink {
    private static final int BG = Color.rgb(5, 12, 19), PANEL = Color.rgb(10, 27, 35), MINT = Color.rgb(54, 244, 227), TEXT = Color.rgb(232, 241, 246), MUTED = Color.rgb(155, 176, 190), AMBER = Color.rgb(255, 222, 67);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final LinkedHashMap<String, JSONObject> live = new LinkedHashMap<>();
    private final Map<String, Float> smoothed = new HashMap<>();
    private final ArrayList<Float> trace = new ArrayList<>();
    private PinGate gate;
    private SessionStore store;
    private SessionStore.Session session;
    private RadioScanner scanner;
    private UsbReceiver usb;
    private android.hardware.usb.UsbDevice pendingUsb;
    private final BroadcastReceiver usbPermission = new BroadcastReceiver() {
        public void onReceive(Context c, Intent intent) {
            android.hardware.usb.UsbDevice device = pendingUsb; pendingUsb = null;
            if (device == null || !visible || !running || demo || replay) return;
            if (intent.getBooleanExtra(android.hardware.usb.UsbManager.EXTRA_PERMISSION_GRANTED, false) && usb.manager().hasPermission(device)) usb.start(device);
            else status("USB permission declined");
        }
    };
    private android.content.SharedPreferences prefs;
    private LinearLayout content;
    private TextView statusLine, headline;
    private final Button[] navButtons = new Button[5];
    private final ArrayDeque<SignalMapModel.Point> mapPoints = new ArrayDeque<>();
    private TileCache tiles;
    private SignalMapView mapView;
    private int tab;
    private volatile int lifecycle;
    private boolean running, demo, replay, visible, permissionsAsked, dirty = true;
    private String status = "Ready · select a session mode", track = "", filter = "", watchlist = "";
    private int events;
    private Uri tree;
    private BluetoothGatt gatt;
    private Runnable labTimeout;
    private final Runnable tick = new Runnable() {
        public void run() {
            if (!visible) return;
            if (running && !gate.unlocked()) { stopSession(false); status("One-hour unlock expired; discovery stopped"); }
            if (running && demo) generateDemo();
            if (dirty || (!track.isEmpty() && tab == 1)) { render(); dirty = false; }
            ui.postDelayed(this, 1000);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        gate = new PinGate(this); store = new SessionStore(this); scanner = new RadioScanner(this, this);
        usb = new UsbReceiver(this, new UsbReceiver.Sink() { public void row(JSONObject value) { try { scanner.addGps(value); } catch (JSONException ignored) {} observation(value); } public void status(String value) { MainActivity.this.status(value); } });
        IntentFilter usbFilter = new IntentFilter(getPackageName() + ".USB_PERMISSION");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbPermission, usbFilter, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(usbPermission, usbFilter);
        tiles = new TileCache(this); mapView = new SignalMapView(this, tiles, key -> { track = key; dirty = true; render(); });
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        watchlist = prefs.getString("watchlist", "flock,raven,falcon,penguin,fs ext battery,lpr,alpr");
        String saved = prefs.getString("tree", ""); if (!saved.isEmpty()) tree = Uri.parse(saved);
        LinearLayout root = column(); root.setBackground(new HudBackground()); root.setPadding(dp(14), dp(8), dp(14), dp(8));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(14) + insets.getSystemWindowInsetLeft(), dp(8) + insets.getSystemWindowInsetTop(), dp(14) + insets.getSystemWindowInsetRight(), dp(8) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout top = row();
        top.addView(text("PERSEPHONE", 23, MINT), new LinearLayout.LayoutParams(0, -2, 1));
        headline = text("RF FIELD DESK", 13, MUTED); top.addView(headline); root.addView(top);
        statusLine = text(status, 13, AMBER); statusLine.setMaxLines(2); root.addView(statusLine);
        LinearLayout navigation = row();
        String[] names = {"HUD", "Feed", "Map", "Logs", "System"};
        for (int i = 0; i < names.length; i++) { final int n = i; navButtons[i] = button(names[i], () -> { tab = n; dirty = true; render(); }); navigation.addView(navButtons[i], new LinearLayout.LayoutParams(0, dp(52), 1)); }
        root.addView(navigation);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); content = column(); scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(root); render();
    }
    @Override protected void onStart() { super.onStart(); visible = true; dirty = true; ui.post(tick); }
    @Override protected void onStop() {
        visible = false; lifecycle++; tiles.online(false); pendingUsb = null; usb.stop(); ui.removeCallbacks(tick); stopSession(true); closeLab(); super.onStop();
    }
    @Override protected void onDestroy() { unregisterReceiver(usbPermission); tiles.close(); io.shutdown(); super.onDestroy(); }
    private void render() {
        if (content == null) return;
        content.removeAllViews(); statusLine.setText(status);
        for (int i = 0; i < navButtons.length; i++) if (navButtons[i] != null) styleButton(navButtons[i], i == tab);
        headline.setText(OverlayService.active()?"OVERLAY // ACTIVE":running ? (demo ? "DEMO · NO RADIOS" : "LIVE · ANDROID") : replay ? "REPLAY · NO RADIOS" : "LOCKED · LOCAL ONLY");
        if (tab == 0) overview(); else if (tab == 1) signals(); else if (tab == 2) mapScreen(); else if (tab == 3) sessions(); else settings();
    }
    private void overview() {
        LinearLayout hero = card();
        String face = running ? (events > 0 ? "( ^‿^ )" : "( •_• )") : "( -‿- )";
        hero.addView(text("RF_SENTINEL // " + (running ? "OBSERVING" : "STANDBY"), 22, AMBER));
        hero.addView(text(face + "   " + (demo ? "SIMULATION / SYNTHETIC" : "SURVEILLANCE AWARENESS"), 14, MINT));

        int wifi = 0, ble = 0, matches = 0;
        for (JSONObject r : live.values()) { if (r.optString("radio").equals("BLE")) ble++; else wifi++; if (matches(r)) matches++; }
        hero.addView(text(events + " observations   /   " + wifi + " Wi-Fi   /   " + ble + " BLE   /   " + matches + " matches", 18, TEXT));
        hero.addView(text("Live device counts cover the latest " + RfCore.MAX_LIVE + " addresses. Epoch " + events / 25 + " · observation epochs, no handshake collection.", 13, MUTED));
        int flocks=0,ravens=0,falcons=0;for(JSONObject r:live.values()){String tags=r.optString("tags");if(tags.contains("FLOCK"))flocks++;if(tags.contains("RAVEN"))ravens++;if(tags.contains("FALCON"))falcons++;}
        hero.addView(text("FLOCK "+flocks+"   RAVEN "+ravens+"   FALCON "+falcons+"  // CANDIDATES",15,AMBER));
        content.addView(hero);
        LinearLayout actions = row();
        actions.addView(button(running ? "Stop & save" : "Start discovery", () -> { if (running) stopSession(false); else authenticate("Start discovery", this::requestDiscovery); }), new LinearLayout.LayoutParams(0, dp(58), 1));
        actions.addView(button("Demo", () -> { if (running) stopSession(false); authenticate("Start a simulated session", () -> startSession(true)); }), new LinearLayout.LayoutParams(0, dp(58), 1));
        content.addView(actions);
        LinearLayout radios = row();
        radios.addView(button(scanner.automaticWifi()?"Stop 30s Wi-Fi refresh":"Wi-Fi refresh / scan…", this::wifiConsent), new LinearLayout.LayoutParams(0, dp(56), 1));
        radios.addView(button("Passive USB receiver…", () -> authenticate("Approve passive USB receiver", this::attachUsb)), new LinearLayout.LayoutParams(0, dp(56), 1));
        content.addView(radios);
        if(running&&!demo)note(scanner.wifiStatus());
        note("Discovery listens for Android Wi-Fi result broadcasts and scans BLE advertisements. Android / Bluetooth firmware can send standard discovery traffic; this is not guaranteed RF-silent. No auto-connect, password attacks, deauthentication, or packet injection.");
        note("GPS fields mark the receiver's position, not the transmitter. RSSI tracking shows relative strength, not distance or a precise device location.");
        if (gate.isDefault()) note("Initial passcode: 9818. Change it in System before connected training. A successful unlock lasts one hour. Connected actions still need informed-consent confirmation.");
    }
    private void signals() {
        if(running&&!demo){note(scanner.wifiStatus());content.addView(button(scanner.automaticWifi()?"Stop 30s Wi-Fi refresh":"Wi-Fi refresh / scan…",this::wifiConsent));}
        LinearLayout choices = row();
        for (String value : new String[]{"Priority", "Wi-Fi", "BLE", "All"}) choices.addView(button(value, () -> { filter = value; dirty = true; render(); }), new LinearLayout.LayoutParams(0, dp(50), 1));
        content.addView(choices);
        if (!track.isEmpty()) {
            JSONObject target = live.get(track);
            LinearLayout hunt = card();
            long age = target == null ? Long.MAX_VALUE : SystemClock.elapsedRealtime() - target.optLong("seen_elapsed", 0);
            boolean fresh = !replay && target != null && age >= 0 && age <= RfCore.FRESH_MS;
            hunt.addView(text("TRACK  " + track, 19, MINT));
            hunt.addView(text(fresh ? String.format(Locale.ROOT, "Smoothed %.0f dBm · relative strength", smoothed.getOrDefault(track, -100f)) : "STALE / REPLAY · no current signal estimate", 16, fresh ? TEXT : AMBER));
            hunt.addView(new TraceView(), new LinearLayout.LayoutParams(-1, dp(70)));
            hunt.addView(button("Clear target", () -> { track = ""; trace.clear(); render(); })); content.addView(hunt);
        }
        List<JSONObject> list = new ArrayList<>(live.values());
        list.sort((a,b)->{int rank=Integer.compare(b.optInt("priority"),a.optInt("priority"));return rank!=0?rank:Integer.compare(b.optInt("rssi"),a.optInt("rssi"));});
        int shown = 0;
        for (JSONObject r : list) {
            if ((filter.equals("Priority") || filter.isEmpty()) && r.optInt("priority")==0 && !matches(r)) continue;
            if ((filter.equals("Wi-Fi") || filter.equals("BLE")) && !filter.equals(r.optString("radio"))) continue;
            if (++shown > 100) break;
            LinearLayout item = card();
            String name = r.optString("name"); if (name.isEmpty()) name = "Unnamed / hidden";
            String tagLabel=r.optString("tags").replace("[", "").replace("]", "").replace("\"", "");
            if(!tagLabel.isEmpty())item.addView(text(tagLabel+" // "+r.optString("confidence")+" CANDIDATE",13,SignalMapView.color(tagLabel)));
            item.addView(text(r.optString("radio") + "  ·  " + name, 18, TEXT));
            long age = SystemClock.elapsedRealtime() - r.optLong("seen_elapsed", 0);
            item.addView(text(r.optString("address") + "   " + r.optInt("rssi") + " dBm" + (replay ? "   REPLAY" : age > RfCore.FRESH_MS ? "   STALE" : "   " + Math.max(0, age / 1000) + "s ago"), 14, MINT));
            String evidence = r.optString("evidence"); if (!evidence.isEmpty()) item.addView(text(evidence, 13, AMBER));
            item.setOnClickListener(v -> detail(r)); content.addView(item);
        }
        if (shown == 0) note("No observations in this view. Enable Location and the radios in Android settings, or use Demo to rehearse offline.");
        else note("Tap a signal to track it or inspect its evidence. Showing up to 100 rows by strength; full observations remain in session logs.");
    }
    private void detail(JSONObject row) {
        String[] options = row.optString("radio").equals("BLE") ? new String[]{"Track signal", "View recorded data", "Consented service inventory…", "Show on map", "GPS / photo / nickname"} : new String[]{"Track signal", "View recorded data", "Review advertised security", "Show on map", "GPS / photo / nickname"};
        new AlertDialog.Builder(this).setTitle(row.optString("address")).setItems(options, (d, index) -> {
            if (index == 0) { track = row.optString("radio") + "/" + row.optString("address"); trace.clear(); tab = 1; render(); }
            else if(index==4){authenticate("Open private ping notes",()->{try{String id=PingStore.get(this).observe(row);if(id!=null)startActivity(new Intent(this,PingEditorActivity.class).putExtra("ping_id",id));else message("Annotation limit","Export/remove saved ping notes before adding more.");}catch(Exception e){status(e.getMessage());}});}
            else if(index==3){track=row.optString("radio")+"/"+row.optString("address");tab=2;render();}
            else if (index == 1) { try { message("Recorded observation", row.toString(2)); } catch (Exception ignored) {} }
            else if (row.optString("radio").equals("BLE")) labConsent(row);
            else message("Advertised Wi-Fi security", row.optString("security", "No capability data") + "\n\nThis reviews public AP capability flags. It does not validate passwords, inspect traffic, or attempt access.");
        }).setNegativeButton("Close", null).show();
    }
    private boolean matches(JSONObject row) {
        return RfCore.matches(watchlist, row.optString("address"), row.optString("name"), row.optString("evidence"), serviceList(row));
    }
    private List<String> serviceList(JSONObject row) {
        List<String> result = new ArrayList<>(); JSONArray array = row.optJSONArray("services");
        if (array != null) for (int i = 0; i < array.length(); i++) result.add(array.optString(i)); return result;
    }
    private void mapScreen() {
        content.addView(button(OverlayService.active()?"Stop Maps overlay":"Overlay on Google / other Maps…",()->{if(OverlayService.active())stopService(new Intent(this,OverlayService.class).setAction(OverlayService.STOP));else authenticate("Approve Maps overlay",this::overlaySetup);}));
        content.addView(button("Ping field notes / photos",()->authenticate("Open private ping notes",()->startActivity(new Intent(this,PingListActivity.class)))));
        content.addView(text("SIGNAL CARTOGRAPHY // "+(demo?"DEMO":replay?"HISTORICAL":"LOCAL"),19,AMBER));
        LinearLayout tools=row();
        tools.addView(button("+",()->mapView.zoom(1)),new LinearLayout.LayoutParams(0,dp(50),1));
        tools.addView(button("−",()->mapView.zoom(-1)),new LinearLayout.LayoutParams(0,dp(50),1));
        tools.addView(button("Center",mapView::center),new LinearLayout.LayoutParams(0,dp(50),2));
        tools.addView(button("All",()->{track="";render();}),new LinearLayout.LayoutParams(0,dp(50),1));content.addView(tools);
        mapView.setData(new ArrayList<>(mapPoints),track);
        content.addView(mapView,new LinearLayout.LayoutParams(-1,dp(getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE?260:380)));
        content.addView(button(tiles.isOnline()?"Disable street tiles":"Enable street tiles…",()->{
            if(tiles.isOnline()){tiles.online(false);mapView.invalidate();render();return;}
            new AlertDialog.Builder(this).setTitle("Online street basemap").setMessage("OpenStreetMap receives your IP address and requested map tile area. Device addresses, names and session logs are not sent. Downloads cover only this visible map; offline observations remain local.")
                .setPositiveButton("Approve with passcode",(d,w)->authenticate("Enable OSM tiles for this foreground visit",()->{tiles.online(true);status(tiles.isOnline()?"Street tiles enabled for current app visit":"Map cache unavailable; staying offline");render();})).setNegativeButton("Cancel",null).show();
        }));
        SignalMapModel.Estimate estimate=mapView.estimate();
        note(track.isEmpty()?"Tap a receiver observation to select a signal. Cyan = Flock, magenta = Raven, yellow = Falcon/LPR. No transmitter location is assumed.":"TARGET "+track+" // points mark reception, not the camera.");
        note(estimate==null?"Origin unresolved. A tentative model needs 8+ accurate GPS positions around a stationary source, ≥60 m coverage, turns, and varied RSSI. Straight routes, weak fits, mocked GPS, and sparse data produce no estimate.":String.format(Locale.ROOT,"%sRSSI MODEL SEARCH AREA // ±%.0f m footprint · %.1f dBm fit residual. Assumes a stationary transmitter and simple propagation; NOT measured coordinates or a statistical confidence interval.",estimate.demo?"DEMO ":"",estimate.radius,estimate.rms));
        if(mapPoints.isEmpty())note("No GPS-tagged observations yet. Grant precise location and scan outdoors, or run the synthetic demo route.");
    }
    private boolean notificationsAsked;
    @android.annotation.SuppressLint("SetTextI18n")
    private void overlaySetup() {
        if(!Settings.canDrawOverlays(this)){message("Allow drawing over other apps","The overlay is built. Grant Android's display-over-other-apps permission in System, then return to MAP and start the overlay.");startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));return;}
        LinearLayout box=column();box.setPadding(dp(18),dp(8),dp(18),dp(8));box.addView(text("Colored reception pings float above Maps. Only a compact legend, transparent dots, RSSI, and coordinates float above your map. Dots need two manually aligned landmarks on a flat, static map and pauses after outside touches / 30 seconds. No Maps camera-state API is assumed.",14,TEXT));
        CheckBox scan=new CheckBox(this);scan.setText("Continue Android discovery + GPS while Maps is visible");scan.setTextColor(TEXT);scan.setChecked(false);scan.setEnabled(!demo);box.addView(scan);
        CheckBox refresh=new CheckBox(this);refresh.setText("Refresh Wi-Fi every 30 seconds during overlay discovery");refresh.setTextColor(TEXT);refresh.setChecked(!demo&&!gate.isDefault());refresh.setEnabled(!demo&&!gate.isDefault());box.addView(refresh);
        box.addView(text("Periodic standard scans can transmit ordinary probes; approve only in your authorized training area. Android may throttle background scans.",13,TEXT));
        if(demo)box.addView(text("DEMO overlay: synthetic pings; no radios will start",14,AMBER));
        new AlertDialog.Builder(this).setTitle("Maps overlay / foreground service").setView(box).setPositiveButton("Start approved overlay",(d,w)->{
            if(!gate.unlocked()){authenticate("Renew overlay approval",this::overlaySetup);return;}
            if(scan.isChecked()&&checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){message("Location permission required","Grant precise Location in System, then approve the overlay again.");return;}
            if(Build.VERSION.SDK_INT>=33&&!notificationsAsked&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){notificationsAsked=true;requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},40);message("Return after notification setup","Approve the overlay again after choosing your notification preference.");return;}
            stopSession(false);try{OverlayService.startApproved(this,scan.isChecked(),demo,scan.isChecked()&&refresh.isChecked());status("Overlay approved. Open your Maps app; use floating ALIGN for calibration.");}catch(Exception e){status("Overlay unavailable: "+e.getMessage());}
        }).setNegativeButton("Cancel",null).show();
    }
    private void sessions() {
        content.addView(button("Choose save folder / SD card…", () -> authenticate("Choose recording destination", this::pickFolder)));
        note(tree == null ? "Save destination: private app storage. Select a writable SD folder to automatically copy completed sessions there." : "Save destination: " + tree + "\nCompleted sessions are copied as JSONL + CSV + GeoJSON. Private recovery copies remain on the radio.");
        content.addView(button("Export ping notes + photos…",()->authenticate("Export private annotations",()->{if(tree==null){message("Choose a save folder","Select a destination first.");return;}Uri target=tree;io.execute(()->{try{PingStore.get(this).export(target);ui.post(()->status("Exported ping notes, GeoJSON, and private photos"));}catch(Exception e){ui.post(()->status("Annotation export failed; private copies retained: "+e.getMessage()));}});})));
        content.addView(button("Export consent audit…", () -> authenticate("Export consent audit", this::exportAudit)));
        List<File> files = store.files();
        if (files.isEmpty()) note("No saved sessions yet.");
        for (File file : files) {
            LinearLayout item = card(); item.addView(text(file.getName(), 16, TEXT)); item.addView(text(file.length() / 1024 + " KB · private recovery copy", 13, MUTED));
            item.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(file.getName()).setItems(new String[]{"Replay latest 512 devices", "Export JSONL + CSV + GeoJSON", "Delete private copy"}, (dialog, index) -> {
                if (index == 0) authenticate("Replay saved observations", () -> replay(file));
                else if (index == 1) authenticate("Export saved session", () -> { stopSession(false); export(file); });
                else authenticate("Delete private session", () -> { if (running) { message("Stop first", "Stop the current recording before deleting sessions."); return; } io.execute(() -> { boolean deleted = file.delete(); ui.post(() -> { if (!deleted) status("Delete failed"); render(); }); }); });
            }).setNegativeButton("Close", null).show()); content.addView(item);
        }
    }
    private void settings() {
        content.addView(button("Lock now",()->{gate.lock();stopSession(false);stopService(new Intent(this,OverlayService.class).setAction(OverlayService.STOP));status("Locked; overlay and discovery stopped");render();}));
        note(gate.unlocked()?"UNLOCKED // "+(gate.unlockRemainingMs()/60000+1)+" minutes remaining. Consent confirmation is still required for connected training.":"LOCKED // enter the passcode once for a one-hour unlock");
        content.addView(button("Change passcode…", () -> authenticate("Change passcode", this::changePin)));
        content.addView(button("Edit watchlist…", () -> authenticate("Change watchlist", () -> {
            final int authorizedEpoch = lifecycle;
            EditText edit = edit("Comma-separated names, MAC prefixes, service UUIDs"); edit.setText(watchlist);
            new AlertDialog.Builder(this).setTitle("Watchlist / substring match").setView(edit).setPositiveButton("Save", (d, w) -> {
                if (!visible || authorizedEpoch != lifecycle || !gate.unlocked()) { status("Approval expired; enter the passcode again"); return; }
                watchlist = edit.getText().toString(); prefs.edit().putString("watchlist", watchlist).apply(); dirty = true; render();
            }).setNegativeButton("Cancel", null).show();
        })));
        content.addView(button("Allow drawing over other apps…",()->{try{startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));}catch(ActivityNotFoundException e){message("Overlay settings unavailable","This ROM does not expose Android overlay permission settings.");}}));
        content.addView(button("Radio & permission diagnostics", this::diagnostics));
        content.addView(button("Grant discovery permissions…", () -> authenticate("Approve permission setup", () -> { permissionsAsked = false; requestDiscovery(); })));
        content.addView(button("Open app permissions", () -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())))));
        content.addView(button("Open Android radio settings", () -> startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS))));
        content.addView(button("Use private storage", () -> authenticate("Change destination", () -> { tree = null; prefs.edit().remove("tree").apply(); status("Private recording storage selected"); })));
        note("No upload service, exported scanner, BLE writes, or arbitrary firmware commands. App-only discovery stops on exit. Explicitly approved Maps-overlay discovery uses a foreground notification until Stop, lock expiry, or task removal. Connected testing stops on exit. Sessions remain in private storage until explicitly deleted.");
        note("The USB adapter supports direct ESP32-S3 CDC-ACM with the bundled passive firmware. CP210x/CH340 bridges are not supported. Built-in Android radios cannot expose raw 802.11 frames, monitor mode, or all client-only devices. Flock signature matches are hypotheses that can be spoofed; missed detections are possible.");
        note("Persephone RF 0.4 · Android API " + Build.VERSION.SDK_INT + " · " + Build.MANUFACTURER + " " + Build.MODEL);
    }
    private void authenticate(String reason, Runnable action) {
        if (!visible) return;
        if(gate.unlocked()){action.run();return;}
        if (gate.remainingMs() > 0) { message("Passcode cooldown", "Try again in " + (gate.remainingMs() / 1000 + 1) + " seconds."); return; }
        final int epoch = lifecycle;
        EditText pin = edit("Passcode"); pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(reason).setView(pin).setPositiveButton("Approve", null).setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
            String candidate = pin.getText().toString(); pin.setText(""); dialog.getButton(-1).setEnabled(false);
            io.execute(() -> {
                boolean accepted = false; String error = "";
                try { accepted = gate.verify(candidate); } catch (Exception e) { error = e.getMessage(); }
                boolean ok = accepted; String failure = error;
                ui.post(() -> {
                    if (!visible || epoch != lifecycle) { dialog.dismiss(); return; }
                    if (ok) { dialog.dismiss(); action.run(); }
                    else { pin.setError(failure.isEmpty() ? "Incorrect passcode" : failure); dialog.getButton(-1).setEnabled(true); if (gate.remainingMs() > 0) { dialog.dismiss(); message("Passcode cooldown", "Five failed attempts. Locked for 60 seconds."); } }
                });
            });
        })); dialog.show();
    }
    private void changePin() {
        final int authorizedEpoch = lifecycle;
        LinearLayout box = column(); box.setPadding(dp(18), dp(4), dp(18), dp(4));
        EditText first = edit("New passcode (6–12 digits)"), second = edit("Repeat passcode");
        first.setInputType(18); second.setInputType(18); box.addView(first); box.addView(second);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Set a personal passcode").setView(box).setPositiveButton("Save", null).setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
            if (!visible || authorizedEpoch != lifecycle || !gate.unlocked()) { dialog.dismiss(); status("Approval expired; enter the passcode again"); return; }
            String pin = first.getText().toString();
            if (!pin.matches("[0-9]{6,12}") || !pin.equals(second.getText().toString())) { second.setError("Use 6–12 matching digits"); return; }
            final int epoch = lifecycle; dialog.getButton(-1).setEnabled(false);
            io.execute(() -> { try { if (epoch != lifecycle || !gate.unlocked()) return; gate.set(pin, false); ui.post(() -> { dialog.dismiss(); status("Personal passcode saved"); }); }
                catch (Exception e) { ui.post(() -> { dialog.getButton(-1).setEnabled(true); second.setError(e.getMessage()); }); } });
        })); dialog.show();
    }
    @android.annotation.SuppressLint("SetTextI18n")
    private void requestDiscovery() {
        List<String> permissions = new ArrayList<>(Arrays.asList(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION));
        if (Build.VERSION.SDK_INT >= 31) { permissions.add(Manifest.permission.BLUETOOTH_SCAN); permissions.add(Manifest.permission.BLUETOOTH_CONNECT); }
        List<String> missing = new ArrayList<>(); for (String p : permissions) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        if (!missing.isEmpty() && !permissionsAsked) { permissionsAsked = true; requestPermissions(missing.toArray(new String[0]), 7); return; }
        LinearLayout box=column();box.setPadding(dp(18),dp(8),dp(18),dp(8));
        CheckBox refresh=new CheckBox(this);refresh.setText("Refresh Wi-Fi every 30 seconds");refresh.setTextColor(TEXT);refresh.setChecked(!gate.isDefault()&&prefs.getBoolean("wifiAutomatic",true));refresh.setEnabled(!gate.isDefault());box.addView(refresh);
        box.addView(text("Standard scans may send ordinary probes in your scan area. Approve only in your authorized training setting. Android can throttle scans, especially while Maps is foreground. Refresh stops with this session or unlock expiry."+(gate.isDefault()?" Set a personal passcode in System to enable automatic Wi-Fi.":""),14,TEXT));
        new AlertDialog.Builder(this).setTitle("Approve discovery / Wi-Fi refresh").setView(box).setPositiveButton("Start approved session",(dialog,which)->{
            if(!gate.unlocked()){authenticate("Renew discovery approval",this::requestDiscovery);return;}
            automaticWifiForSession=refresh.isChecked();prefs.edit().putBoolean("wifiAutomatic",automaticWifiForSession).apply();startSession(false);
        }).setNegativeButton("Cancel",null).show();
    }
    private boolean automaticWifiForSession;
    @Override public void onRequestPermissionsResult(int request, String[] names, int[] grants) {
        super.onRequestPermissionsResult(request, names, grants);
        if (request == 7) { status("Permissions updated. Start discovery to approve a session; missing capabilities are listed in System."); diagnostics(); }
    }
    private void startSession(boolean simulated) {
        if(OverlayService.active()){message("Overlay discovery active","Stop the floating overlay before starting an app-only session.");return;}
        stopSession(false);
        try { session = store.new Session(simulated); }
        catch (IOException e) { status("Cannot record: " + e.getMessage()); return; }
        running = true; demo = simulated; replay = false; events = 0; live.clear(); mapPoints.clear(); smoothed.clear(); trace.clear(); track = "";
        status = simulated ? "Demo recording · synthetic data only" : "Recording · Wi-Fi result listener + BLE discovery";
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!simulated){scanner.start();if(automaticWifiForSession)audited("wifi_refresh_30s","local scan area","Operator approved periodic standard scans for this session",()->scanner.automaticWifi(true));} dirty = true; render();
    }
    private void stopSession(boolean leaving) {
        if (!running) return;
        running = false; scanner.stop(); usb.stop(); pendingUsb = null; closeLab(); getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        SessionStore.Session completed = session; session = null;
        status = leaving ? "Session saved; discovery stopped when app left foreground" : "Session saved · radios stopped";
        Uri destination = tree;
        io.execute(() -> {
            try { completed.close(); if (destination != null) { store.export(completed.file, destination); ui.post(() -> status("Saved JSONL + CSV + GeoJSON to selected folder")); } }
            catch (Exception e) { ui.post(() -> status("SD/export failed; private copy retained: " + e.getMessage())); }
        });
        dirty = true; if (visible) render();
    }
    @Override public void observation(JSONObject row) {
        if (!running) return;
        try { DetectionAnnotator.annotate(row); } catch(JSONException invalid) { status("Invalid detector metadata"); return; }
        try { PingStore.get(this).observe(row); } catch(JSONException invalid) { status("Ping metadata rejected"); }
        SignalMapModel.Point location=DetectionAnnotator.point(row);if(location!=null){mapPoints.addLast(location);while(mapPoints.size()>2000)mapPoints.removeFirst();}
        String key = row.optString("radio") + "/" + row.optString("address");
        live.remove(key); live.put(key, row); while (live.size() > RfCore.MAX_LIVE) { String oldest = live.keySet().iterator().next(); live.remove(oldest); smoothed.remove(oldest); }
        float smooth = smoothed.containsKey(key) ? RfCore.smooth(smoothed.get(key), row.optInt("rssi")) : row.optInt("rssi"); smoothed.put(key, smooth);
        if (key.equals(track)) { trace.add(smooth); if (trace.size() > 60) trace.remove(0); }
        events++; dirty = true; final SessionStore.Session recording = session;
        io.execute(() -> { try { recording.append(row); } catch (IOException error) { ui.post(() -> { if (session == recording) { stopSession(false); status("Recording stopped: " + error.getMessage()); } }); } });
    }
    @Override public void status(String message) { status = message; dirty = true; if (statusLine != null) statusLine.setText(message); }
    private void wifiConsent() {
        if(scanner.automaticWifi()){scanner.automaticWifi(false);status("Automatic Wi-Fi refresh stopped");return;}
        if (!running || demo) { message("Start discovery first", "A real Android discovery session is required."); return; }
        if (gate.isDefault()) { message("Change passcode first", "Set a personal passcode in System before requesting radio transmissions."); return; }
        new AlertDialog.Builder(this).setTitle("Request a standard Wi-Fi scan?")
            .setMessage("Android may transmit ordinary probe requests. This affects the radio's scan area and cannot be restricted to one AP. Use only within your authorized training setting. Android may throttle this request.")
            .setNeutralButton("One scan", (d, w) -> authenticate("Approve one Wi-Fi scan", () -> audited("wifi_scan", "local scan area", "Operator confirmed authorized training setting", () -> {
                status(scanner.requestWifiScan() ? "Wi-Fi scan requested; waiting for Android results" : "Wi-Fi scan rejected / throttled. No new results claimed.");
            }))).setPositiveButton("Every 30 seconds",(d,w)->authenticate("Approve periodic Wi-Fi scans",()->audited("wifi_refresh_30s","local scan area","Operator approved standard scans every 30 seconds until session end or unlock expiry",()->{scanner.automaticWifi(true);status("Wi-Fi refresh approved every 30 seconds; Android may throttle");}))).setNegativeButton("Cancel", null).show();
    }
    private void labConsent(JSONObject row) {
        if (!running || demo || replay || !row.optBoolean("connectable")) { message("Training action unavailable", "Use a current, connectable BLE device during a real discovery session."); return; }
        if (gate.isDefault()) { message("Change passcode first", "Set a personal passcode before connected training."); return; }
        LinearLayout box = column(); box.setPadding(dp(18), 0, dp(18), 0);
        box.addView(text("Target: " + row.optString("address") + "\nConnect once, enumerate GATT service UUIDs and characteristic properties, then disconnect. No characteristic reads or writes.", 16, TEXT));
        EditText scope = edit("Device owner / consent reference (required)"); box.addView(scope);
        CheckBox consent = new CheckBox(this); consent.setText(com.heathen.persephone.R.string.consent_label); consent.setTextColor(TEXT); box.addView(consent);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Read-only BLE training").setView(box).setPositiveButton("Approve with passcode", null).setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
            if (!consent.isChecked() || scope.getText().toString().trim().isEmpty()) { scope.setError("Consent and reference required"); return; }
            String reference = scope.getText().toString().trim(); dialog.dismiss();
            authenticate("Approve inventory of " + row.optString("address"), () -> {
                if (!RfCore.allowedTarget(row.optString("address"), row.optLong("seen_elapsed"), SystemClock.elapsedRealtime(), demo || replay, true) || !running) { message("Target expired", "Observe the device again and reapprove the action."); return; }
                audited("ble_service_inventory", row.optString("address"), reference, () -> { if (RfCore.allowedTarget(row.optString("address"), row.optLong("seen_elapsed"), SystemClock.elapsedRealtime(), demo || replay, true)) inventory(row.optString("address")); else status("Target expired; approve again after observing it"); });
            });
        })); dialog.show();
    }
    private void audited(String action, String target, String reference, Runnable approved) {
        final int epoch = lifecycle;
        io.execute(() -> {
            try { store.audit(action, target, reference); ui.post(() -> { if (visible && epoch == lifecycle && gate.unlocked() && running && !demo && !replay) approved.run(); }); }
            catch (Exception e) { ui.post(() -> message("Action blocked", "Cannot persist approval audit: " + e.getMessage())); }
        });
    }
    private void inventory(String address) {
        closeLab();
        try {
            BluetoothAdapter adapter = scanner.adapter();
            if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) throw new SecurityException("Bluetooth permission missing");
            if (adapter == null || !adapter.isEnabled()) throw new IllegalStateException("BLE radio unavailable");
            status("Approved BLE inventory in progress: " + address);
            final int epoch = lifecycle;
            BluetoothGattCallback callback = new BluetoothGattCallback() {
                @Override public void onConnectionStateChange(BluetoothGatt device, int result, int state) { ui.post(() -> {
                    if (device != gatt || epoch != lifecycle) { safeCloseGatt(device); return; }
                    if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) { closeLab(); status("Bluetooth permission revoked"); return; }
                    if (result != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) { closeLab(); status("BLE inventory disconnected / failed (" + result + ")"); }
                    else if (state == BluetoothProfile.STATE_CONNECTED) { try { if (!device.discoverServices()) { closeLab(); status("Service discovery rejected"); } } catch (SecurityException e) { closeLab(); status("Bluetooth permission revoked"); } }
                }); }
                @Override public void onServicesDiscovered(BluetoothGatt device, int result) { ui.post(() -> {
                    if (device != gatt || epoch != lifecycle) { safeCloseGatt(device); return; }
                    if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) { closeLab(); status("Bluetooth permission revoked"); return; }
                    StringBuilder report = new StringBuilder("Target: " + address + "\nStatus: " + result + "\n");
                    if (result == BluetoothGatt.GATT_SUCCESS) for (BluetoothGattService service : device.getServices()) {
                        report.append("\nService ").append(service.getUuid()).append('\n');
                        for (BluetoothGattCharacteristic c : service.getCharacteristics()) report.append("  ").append(c.getUuid()).append(" properties=0x").append(Integer.toHexString(c.getProperties())).append('\n');
                    }
                    closeLab(); status("BLE inventory complete; disconnected");
                    io.execute(() -> { try { store.audit("ble_inventory_result", address, report.toString()); } catch (Exception e) { ui.post(() -> status("Could not save result audit: " + e.getMessage())); } });
                    message("Service inventory (read-only)", report.toString());
                }); }
            };
            gatt = adapter.getRemoteDevice(address).connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) throw new IllegalStateException("Connection request failed");
            labTimeout = () -> { closeLab(); status("BLE inventory timed out after 15 seconds; disconnected"); }; ui.postDelayed(labTimeout, 15_000);
        } catch (SecurityException e) { closeLab(); status("Bluetooth permission revoked"); }
        catch (Exception e) { closeLab(); status("BLE inventory unavailable: " + e.getMessage()); }
    }
    private void closeLab() { if (labTimeout != null) ui.removeCallbacks(labTimeout); labTimeout = null; if (gatt != null) { BluetoothGatt old = gatt; gatt = null; try { old.disconnect(); } catch (SecurityException | IllegalStateException ignored) {} safeCloseGatt(old); } }
    private void safeCloseGatt(BluetoothGatt device) { try { device.close(); } catch (SecurityException ignored) {} }
    private void pickFolder() {
        stopSession(false);
        try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 9); }
        catch (ActivityNotFoundException e) { message("Folder picker unavailable", "This ROM has no Android document provider. Sessions remain in private storage."); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 9 && result == RESULT_OK && data != null && data.getData() != null) {
            Uri chosen = data.getData();
            try {
                int grants = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                if ((grants & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) throw new SecurityException("Folder is read-only");
                if ((grants & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) getContentResolver().takePersistableUriPermission(chosen, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                else getContentResolver().takePersistableUriPermission(chosen, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                tree = chosen; prefs.edit().putString("tree", chosen.toString()).apply(); status("Save folder selected. Completed sessions copy there automatically.");
            } catch (Exception e) { status("Could not retain destination: " + e.getMessage()); }
        }
    }
    private void export(File file) {
        if (tree == null) { message("Choose a save folder", "Select an SD-card folder or another Android document provider in Sessions, then export again."); return; }
        Uri destination = tree; status("Exporting…");
        io.execute(() -> { try { store.export(file, destination); ui.post(() -> status("Exported JSONL + CSV + GeoJSON to selected folder")); } catch (Exception e) { ui.post(() -> status("Export failed; private copy retained: " + e.getMessage())); } });
    }
    private void exportAudit() {
        if (tree == null) { message("Choose a save folder", "Select a destination in Sessions first."); return; }
        File file = new File(getFilesDir(), "audit.jsonl");
        if (!file.exists()) { message("No connected approvals yet", "The audit is created when a radio request or BLE inventory is approved."); return; }
        Uri destination = tree;
        io.execute(() -> { try {
            Uri parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(destination, android.provider.DocumentsContract.getTreeDocumentId(destination));
            Uri output = android.provider.DocumentsContract.createDocument(getContentResolver(), parent, "application/x-ndjson", "consent-audit-" + System.currentTimeMillis() + ".jsonl");
            if (output == null) throw new IOException("Cannot create audit document");
            try (InputStream in = new FileInputStream(file); OutputStream out = getContentResolver().openOutputStream(output, "wt")) { if (out == null) throw new IOException("Cannot open audit destination"); byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); }
            ui.post(() -> status("Consent audit exported"));
        } catch (Exception e) { ui.post(() -> status("Audit export failed: " + e.getMessage())); } });
    }
    private void replay(File file) {
        stopSession(false); final int epoch = lifecycle; status("Loading replay…");
        io.execute(() -> {
            LinkedHashMap<String, JSONObject> rows = new LinkedHashMap<>(); ArrayDeque<SignalMapModel.Point> locations=new ArrayDeque<>(); int count = 0;
            try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line; while ((line = in.readLine()) != null) { try { JSONObject r = new JSONObject(line); DetectionAnnotator.annotate(r);SignalMapModel.Point p=DetectionAnnotator.point(r);if(p!=null){locations.addLast(p);while(locations.size()>2000)locations.removeFirst();} rows.put(r.optString("radio") + "/" + r.optString("address"), r); while (rows.size() > RfCore.MAX_LIVE) rows.remove(rows.keySet().iterator().next()); count++; } catch (JSONException partial) {} }
                final int total = count;
                ui.post(() -> { if (!visible || epoch != lifecycle || running) return; live.clear(); live.putAll(rows); mapPoints.clear();mapPoints.addAll(locations); smoothed.clear(); trace.clear(); track = ""; events = total; demo = false; replay = true; tab = 1; status("Replay loaded · no radios · " + file.getName()); render(); });
            } catch (Exception e) { ui.post(() -> status("Replay failed: " + e.getMessage())); }
        });
    }
    private void attachUsb() {
        if (!running || demo || replay) { message("Start discovery first", "Start a real session before attaching the USB receiver."); return; }
        List<android.hardware.usb.UsbDevice> devices = usb.devices();
        if (devices.isEmpty()) { message("No CDC-ACM receiver found", "Connect an ESP32-S3 using its direct USB port and an OTG/data cable. Flash the bundled passive receiver firmware first. The radio must support Android USB host."); return; }
        String[] names = new String[devices.size()]; for (int i = 0; i < names.length; i++) names[i] = devices.get(i).getDeviceName();
        final int epoch = lifecycle;
        new AlertDialog.Builder(this).setTitle("Passive USB receiver").setItems(names, (d, w) -> {
            if (!visible || epoch != lifecycle || !running || demo || replay) return;
            android.hardware.usb.UsbDevice device = devices.get(w);
            if (usb.manager().hasPermission(device)) usb.start(device);
            else {
                pendingUsb = device;
                Intent intent = new Intent(getPackageName() + ".USB_PERMISSION").setPackage(getPackageName());
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                usb.manager().requestPermission(device, PendingIntent.getBroadcast(this, 0, intent, flags));
            }
        }).setNegativeButton("Cancel", null).show();
    }
    private void diagnostics() {
        StringBuilder report = new StringBuilder("Android " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT + "\n" + Build.MANUFACTURER + " " + Build.MODEL + "\n" + getResources().getDisplayMetrics().widthPixels + " × " + getResources().getDisplayMetrics().heightPixels + " pixels\n\n");
        List<String> required = new ArrayList<>(); required.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 31) { required.add(Manifest.permission.BLUETOOTH_SCAN); required.add(Manifest.permission.BLUETOOTH_CONNECT); }
        for (String p : required) {
            report.append(p.substring(p.lastIndexOf('.') + 1)).append(": ").append(checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED ? "granted" : "missing").append('\n');
        }
        report.append("BLE feature: ").append(getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE));
        report.append("\nWi-Fi feature: ").append(getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI));
        try { android.location.LocationManager lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE); report.append("\nLocation enabled: ").append(lm != null && (Build.VERSION.SDK_INT < 28 ? lm.isProviderEnabled("gps") || lm.isProviderEnabled("network") : lm.isLocationEnabled())); } catch (Exception ignored) {}
        report.append("\n\nWi-Fi relies on system scans by default. New scan requests are explicitly approved; Android normally permits only four foreground scans in two minutes. BLE discovery may issue scan requests. No raw packet capture / monitor mode via standard Android APIs.");
        message("Head-unit diagnostics", report.toString());
    }
    private void generateDemo() {
        try {
            int n=events%4;String[] names={"Flock-DEMO","Raven-DEMO","Falcon-DEMO","Training beacon"};
            int leg=events/4%24;double angle=leg*Math.PI/12, x=(70+(leg%3)*25)*Math.cos(angle), y=(55+(leg%4)*18)*Math.sin(angle);
            double distance=Math.hypot(x-45,y-20);int rssi=(int)Math.round(-28-22*Math.log10(Math.max(3,distance)));
            JSONObject row=new JSONObject().put("schema",1).put("source","DEMO").put("time",Instant.now().toString()).put("radio",n%2==0?"Wi-Fi":"BLE")
                .put("address",String.format(Locale.ROOT,"02:00:00:00:00:%02X",n)).put("name",names[n]).put("rssi",rssi).put("seen_elapsed",SystemClock.elapsedRealtime())
                .put("latitude",36.1699+y/111320).put("longitude",-115.1398+x/(111320*Math.cos(Math.toRadians(36.1699)))).put("accuracy",5).put("gps_age_ms",0)
                .put("security",n%2==0?"[WPA2-PSK-CCMP][ESS]":"").put("frequency",n%2==0?2412:0).put("connectable",false);
            if(n==1)row.put("services",new JSONArray(Arrays.asList("00003100-0000-1000-8000-00805f9b34fb","00003200-0000-1000-8000-00805f9b34fb")));
            observation(row);
        } catch (Exception e) { status("Demo error: " + e.getMessage()); }
    }
    private void message(String title, String body) {
        if (!visible) return;
        TextView label = text(body, 15, TEXT); label.setPadding(dp(20), dp(10), dp(20), dp(10)); label.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this); scroll.addView(label);
        new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("Close", null).show();
    }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private LinearLayout card() {
        LinearLayout view = column(); view.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable background = new GradientDrawable(); background.setColor(PANEL); background.setCornerRadius(dp(3)); background.setStroke(dp(1),Color.rgb(30,73,84)); view.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, dp(8), 0, dp(4)); view.setLayoutParams(params); return view;
    }
    private TextView text(String value, int size, int color) { TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(color); text.setTypeface(android.graphics.Typeface.MONOSPACE); text.setPadding(0, dp(3), 0, dp(3)); return text; }
    private Button button(String value, Runnable action) { Button button = new Button(this); button.setText(value); button.setTextSize(16); button.setTypeface(null, android.graphics.Typeface.BOLD); button.setAllCaps(true); button.setTypeface(android.graphics.Typeface.MONOSPACE,android.graphics.Typeface.BOLD); button.setAutoSizeTextTypeUniformWithConfiguration(11,16,1,android.util.TypedValue.COMPLEX_UNIT_SP); button.setTextColor(MINT); button.setMinHeight(dp(50)); button.setOnClickListener(v -> action.run()); styleButton(button, false); return button; }
    private void styleButton(Button button, boolean selected) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(selected ? AMBER : PANEL); shape.setCornerRadius(dp(2)); shape.setStroke(dp(1), selected ? AMBER : Color.rgb(38,92,103));
        android.graphics.drawable.RippleDrawable ripple = new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.rgb(48, 94, 86)), shape, null);
        button.setBackground(new android.graphics.drawable.InsetDrawable(ripple, dp(3))); button.setTextColor(selected ? BG : MINT);
    }
    private EditText edit(String hint) { EditText edit = new EditText(this); edit.setHint(hint); edit.setTextColor(TEXT); edit.setHintTextColor(MUTED); edit.setSingleLine(true); return edit; }
    private void note(String value) { TextView note = text(value, 14, MUTED); note.setPadding(dp(4), dp(12), dp(4), dp(8)); content.addView(note); }
    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private final class TraceView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        TraceView() { super(MainActivity.this); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); paint.setColor(MUTED); paint.setStrokeWidth(dp(1));
            for (int i = 1; i < 4; i++) canvas.drawLine(0, getHeight() * i / 4f, getWidth(), getHeight() * i / 4f, paint);
            paint.setColor(MINT); paint.setStrokeWidth(dp(3));
            for (int i = 1; i < trace.size(); i++) canvas.drawLine((i - 1) * getWidth() / 59f, y(trace.get(i - 1)), i * getWidth() / 59f, y(trace.get(i)), paint);
        }
        private float y(float signal) { return Math.max(0, Math.min(getHeight(), (-signal - 30) / 70f * getHeight())); }
    }
}
