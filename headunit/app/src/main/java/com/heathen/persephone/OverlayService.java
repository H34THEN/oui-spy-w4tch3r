package com.heathen.persephone;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Explicitly approved foreground overlay. No exported controls or auto-start/restart. */
@android.annotation.SuppressLint("SetTextI18n")
public final class OverlayService extends Service implements RadioScanner.Sink {
    static final String START="persephone.overlay.START",STOP="persephone.overlay.STOP",HIDE="persephone.overlay.HIDE",SHOW="persephone.overlay.SHOW",PLACE="persephone.overlay.PLACE";
    private static volatile OverlayService instance;private static String ticket;
    static boolean active(){return instance!=null&&instance.started;}
    static void startApproved(Context context,boolean live,boolean demo){startApproved(context,live,demo,false);}
    static void startApproved(Context context,boolean live,boolean demo,boolean refresh){if(!new PinGate(context).unlocked())throw new SecurityException("Passcode unlock required");ticket=UUID.randomUUID().toString();context.startForegroundService(new Intent(context,OverlayService.class).setAction(START).putExtra("ticket",ticket).putExtra("live",live).putExtra("demo",demo).putExtra("wifi_auto",refresh));}
    static void hideForEditor(Context context){if(active())context.startService(new Intent(context,OverlayService.class).setAction(HIDE));}
    static void showAfterEditor(Context context){if(active())context.startService(new Intent(context,OverlayService.class).setAction(SHOW));}
    private final Handler ui=new Handler(Looper.getMainLooper());private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final List<View> windows=new ArrayList<>(),dots=new ArrayList<>();private WindowManager manager;private PingStore pings;private RadioScanner scanner;private SessionStore store;private SessionStore.Session session;private TileCache tiles;
    private boolean touchingMarker;
    private boolean started,live,demo,hidden,placing,mini=false,dirty=true;private LinearLayout legend;private SignalMapView map;private TextView state;private View menu,anchorA,anchorB;
    private OverlayProjection projection;private long calibrationExpires;private double latA,lonA,latB,lonB;private int legendX=16,legendY=80,offsetX,offsetY;private String radioStatus="";
    private final Runnable tick=new Runnable(){public void run(){if(!started)return;if(!new PinGate(OverlayService.this).unlocked()){stopSelf();return;}if(projection!=null&&SystemClock.elapsedRealtime()>calibrationExpires)pauseDots("Calibration expired; recalibrate a static map");if(!hidden&&(dirty||mini)){refresh();dirty=false;}ui.postDelayed(this,1000);}};
    @Override public void onCreate(){super.onCreate();instance=this;manager=(WindowManager)getSystemService(WINDOW_SERVICE);pings=PingStore.get(this);store=new SessionStore(this);scanner=new RadioScanner(this,this);tiles=new TileCache(this);}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent==null){stopSelf();return START_NOT_STICKY;}String action=intent.getAction();
        if(STOP.equals(action)){stopSelf();return START_NOT_STICKY;}
        if(!new PinGate(this).unlocked()){stopSelf();return START_NOT_STICKY;}
        if(START.equals(action)){
            if(started||ticket==null||!ticket.equals(intent.getStringExtra("ticket"))||!Settings.canDrawOverlays(this)||!new PinGate(this).unlocked()){stopSelf();return START_NOT_STICKY;}ticket=null;
            live=intent.getBooleanExtra("live",false);demo=intent.getBooleanExtra("demo",false);if(demo)live=false;
            if(live&&checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){stopSelf();return START_NOT_STICKY;}
            try{NotificationManager notifications=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);notifications.createNotificationChannel(new NotificationChannel("overlay","Persephone map overlay",NotificationManager.IMPORTANCE_LOW));
                PendingIntent stop=PendingIntent.getService(this,10,new Intent(this,OverlayService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
                PendingIntent open=PendingIntent.getActivity(this,11,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
                Notification notification=new Notification.Builder(this,"overlay").setSmallIcon(R.drawable.ic_launcher).setContentTitle("Persephone overlay "+(demo?"DEMO":live?"RF / GPS ACTIVE":"SAVED DOTS"))
                    .setContentText("Tap dots for private GPS/photo/nickname notes. Stop ends overlay/discovery.").setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Stop overlay",stop).build()).build();
                if(android.os.Build.VERSION.SDK_INT>=34)startForeground(41,notification,live?ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION:ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(41,notification);
                started=true;if(live){session=store.new Session(false);scanner.start();if(intent.getBooleanExtra("wifi_auto",false)){
                    if(new PinGate(this).isDefault())throw new SecurityException("Set a personal passcode for Wi-Fi refresh");
                    io.execute(()->{try{store.audit("wifi_refresh_30s","local scan area","Operator approved periodic standard scans in overlay setup");ui.post(()->{if(started&&new PinGate(this).unlocked())scanner.automaticWifi(true);});}catch(Exception error){ui.post(this::stopSelf);}});
                }}showLegend();ui.post(tick);
            }catch(Exception error){android.util.Log.e("Persephone","Overlay start failed",error);stopSelf();}
        }else if(!started){stopSelf();}
        else if(HIDE.equals(action)){hidden=true;projection=null;clearWindows();}
        else if(SHOW.equals(action)){hidden=false;placing=false;mini=false;projection=null;showLegend();dirty=true;}
        else if(PLACE.equals(action)){hidden=false;placing=true;mini=false;projection=null;latA=intent.getDoubleExtra("lat_a",Double.NaN);lonA=intent.getDoubleExtra("lon_a",Double.NaN);latB=intent.getDoubleExtra("lat_b",Double.NaN);lonB=intent.getDoubleExtra("lon_b",Double.NaN);if(!OverlayProjection.validGeo(latA,lonA)||!OverlayProjection.validGeo(latB,lonB)){placing=false;mini=false;}showLegend();if(placing)placeAnchors();}
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onTaskRemoved(Intent rootIntent){stopSelf();}
    @Override public void onDestroy(){started=false;ui.removeCallbacks(tick);scanner.stop();clearWindows();tiles.close();instance=null;
        SessionStore.Session completed=session;session=null;if(completed!=null)io.execute(()->{try{completed.close();String saved=getSharedPreferences("settings",MODE_PRIVATE).getString("tree","");if(!saved.isEmpty())store.export(completed.file,android.net.Uri.parse(saved));pings.flush();}catch(Exception e){android.util.Log.e("Persephone","Overlay save/export failed; private files retained",e);}});else io.execute(()->{try{pings.flush();}catch(Exception ignored){}});io.shutdown();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
    @Override public void observation(JSONObject row){if(!started||!live)return;try{DetectionAnnotator.annotate(row);pings.observe(row);dirty=true;final SessionStore.Session recording=session;io.execute(()->{try{recording.append(row);}catch(Exception e){ui.post(this::stopSelf);}});}catch(Exception error){status("Observation rejected: "+error.getMessage());}}
    @Override public void status(String message){radioStatus=message;dirty=true;}
    @android.annotation.SuppressLint("ClickableViewAccessibility") // Outside-touch observer never consumes clicks.
    private void showLegend(){clearWindows();legend=new LinearLayout(this);legend.setOrientation(LinearLayout.VERTICAL);legend.setPadding(dp(8),dp(6),dp(8),dp(6));legend.setBackgroundColor(0xed07131c);
        TextView title=text("PERSEPHONE // "+(demo?"DEMO":live?"LIVE":"SAVED"),13,0xffffde43);legend.addView(title);WindowManager.LayoutParams position=params(dp(230),WindowManager.LayoutParams.WRAP_CONTENT,legendX,legendY,true);drag(title,legend,position);
        legend.addView(text("● Flock   ● Raven   ● Falcon/LPR",11,0xff36f4e3));android.text.SpannableString colored=new android.text.SpannableString("● Flock  ● Raven  ● Falcon/LPR\n● Camera/Wi-Fi  ● BLE");int[] colors={0xff36f4e3,0xffde69ff,0xffffde43,0xff749fba,0xff79a6ff};int at=0;for(int color:colors){int next=colored.toString().indexOf('●',at);if(next>=0){colored.setSpan(new android.text.style.ForegroundColorSpan(color),next,next+1,0);at=next+1;}}TextView keys=text("",11,0xffd4e5ec);keys.setText(colored);legend.removeViewAt(1);legend.addView(keys);
        state=text("",11,0xffb0c6d0);legend.addView(state);
        LinearLayout controls=new LinearLayout(this);controls.addView(button(placing?"LOCK":"ALIGN",()->{if(placing)lock();else openCalibration();}),new LinearLayout.LayoutParams(0,dp(34),1));controls.addView(button("PINGS",()->{hidden=true;projection=null;clearWindows();startActivity(new Intent(this,PingListActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}),new LinearLayout.LayoutParams(0,dp(34),1));controls.addView(button("STOP",this::stopSelf),new LinearLayout.LayoutParams(0,dp(34),1));legend.addView(controls);map=null;
        legend.setOnTouchListener((view,event)->{if(event.getAction()==MotionEvent.ACTION_OUTSIDE&&projection!=null&&!ownsTouch(event.getRawX(),event.getRawY()))pauseDots("Map touched; dots paused. Recalibrate before showing.");return false;});add(legend,position);legend.post(()->{if(legend!=null){int[] origin=new int[2];legend.getLocationOnScreen(origin);offsetX=origin[0]-position.x;offsetY=origin[1]-position.y;dirty=true;}});refresh();
    }
    @android.annotation.SuppressLint("ClickableViewAccessibility") // Tracks held touches; the view still handles its own click.
    private void refresh(){if(legend==null||hidden)return;List<JSONObject> visible=visiblePings();List<SignalMapModel.Point> points=new ArrayList<>();for(JSONObject row:visible){SignalMapModel.Point p=PingStore.point(row);if(p!=null)points.add(p);}state.setText((placing?"Place A/B over landmarks; then LOCK":projection==null?"DOTS PAUSED // map projection unknown":"STATIC SNAPSHOT // expires in "+Math.max(0,(calibrationExpires-SystemClock.elapsedRealtime())/1000)+"s")+"\n"+points.size()+" located pings"+(radioStatus.isEmpty()?"":"\n"+radioStatus));if(placing||projection==null||menu!=null||touchingMarker)return;
        removeDots();int[] screen=screenSize();int count=0;
        for(JSONObject ping:visible){SignalMapModel.Point point=PingStore.point(ping);if(point==null)continue;double[] xy=projection.screen(point.lat,point.lon);if(xy[0]<dp(16)||xy[1]<dp(16)||xy[0]>screen[0]-dp(16)||xy[1]>screen[1]-dp(16))continue;if(++count>24)break;
            String title=ping.optString("nickname");LinearLayout marker=new LinearLayout(this);marker.setOrientation(LinearLayout.VERTICAL);
            TextView dot=text("● "+ping.optInt("rssi")+" dBm",13,color(ping));dot.setGravity(Gravity.CENTER);marker.addView(dot,new LinearLayout.LayoutParams(-1,dp(26)));
            TextView coordinates=text(String.format(Locale.ROOT,"%.5f  %.5f",point.lat,point.lon),9,color(ping));coordinates.setGravity(Gravity.CENTER);marker.addView(coordinates);
            marker.setContentDescription((title.isEmpty()?ping.optString("name"):title)+" / "+ping.optString("tags")+" receiver ping; tap for GPS, photo, nickname");marker.setOnTouchListener((v,event)->{if(event.getAction()==MotionEvent.ACTION_DOWN)touchingMarker=true;else if(event.getAction()==MotionEvent.ACTION_UP||event.getAction()==MotionEvent.ACTION_CANCEL)touchingMarker=false;return false;});marker.setOnClickListener(v->openMenu(ping,marker));
            add(marker,params(dp(120),dp(46),(int)xy[0]-dp(60)-offsetX,(int)xy[1]-dp(13)-offsetY,false));dots.add(marker);

        }
    }
    private List<JSONObject> visiblePings(){List<JSONObject> rows=new ArrayList<>();for(JSONObject row:pings.list())if(row.optString("source").equals("DEMO")==demo)rows.add(row);rows.sort((a,b)->Integer.compare(b.optInt("priority"),a.optInt("priority")));return rows;}
    private int color(JSONObject ping){String tags=ping.optString("tags");if(tags.contains("RAVEN")||tags.contains("FALCON")||tags.contains("LPR")||tags.contains("FLOCK"))return SignalMapView.color(tags);return ping.optString("radio").equals("BLE")?0xff79a6ff:0xff749fba;}
    private void openMenu(JSONObject ping,View dot){if(menu!=null)remove(menu);LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(8),dp(8),dp(8),dp(8));panel.setBackgroundColor(0xff07131c);String name=ping.optString("nickname");panel.addView(text(name.isEmpty()?ping.optString("name","Unnamed ping"):name,14,color(ping)));panel.addView(text("RECEIVER / PHONE NOTE\n"+ping.optString("confidence")+" candidate",11,0xffb0c6d0));
        for(String[] action:new String[][]{{"CAPTURE GPS","gps"},{"ATTACH PHOTO","photo"},{"NICKNAME / NOTES","name"},{"DETAILS",""}})panel.addView(button(action[0],()->openEditor(ping.optString("id"),action[1])));panel.addView(button("CLOSE",()->{remove(menu);menu=null;}));
        int[] size=screenSize();int x=legendX+dp(20),y=legendY+dp(40);if(dot!=null){WindowManager.LayoutParams p=(WindowManager.LayoutParams)dot.getLayoutParams();x=p.x+dp(30);y=p.y;}menu=panel;add(panel,params(dp(220),WindowManager.LayoutParams.WRAP_CONTENT,Math.max(0,Math.min(x,size[0]-dp(220))),Math.max(0,Math.min(y,size[1]-dp(330))),false));}
    private void openEditor(String id,String action){hidden=true;projection=null;clearWindows();startActivity(new Intent(this,PingEditorActivity.class).putExtra("ping_id",id).putExtra("action",action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
    private void openCalibration(){hidden=true;projection=null;clearWindows();startActivity(new Intent(this,OverlayCalibrationActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
    private void placeAnchors(){int[] size=screenSize();anchorA=anchor("A",size[0]/3,size[1]/2);anchorB=anchor("B",size[0]*2/3,size[1]/2);}
    private View anchor(String title,int x,int y){TextView view=text(title+" ⊕",20,0xffffde43);view.setBackgroundColor(0xc407131c);view.setGravity(Gravity.CENTER);WindowManager.LayoutParams p=params(dp(64),dp(52),x-dp(32),y-dp(26),false);drag(view,view,p);add(view,p);return view;}
    private void lock(){try{WindowManager.LayoutParams a=(WindowManager.LayoutParams)anchorA.getLayoutParams(),b=(WindowManager.LayoutParams)anchorB.getLayoutParams();int[] first=new int[2],second=new int[2];anchorA.getLocationOnScreen(first);anchorB.getLocationOnScreen(second);projection=OverlayProjection.calibrate(latA,lonA,first[0]+anchorA.getWidth()/2.0,first[1]+anchorA.getHeight()/2.0,latB,lonB,second[0]+anchorB.getWidth()/2.0,second[1]+anchorB.getHeight()/2.0);calibrationExpires=SystemClock.elapsedRealtime()+30000;placing=false;remove(anchorA);remove(anchorB);anchorA=null;anchorB=null;mini=false;showLegend();dirty=true;}catch(Exception error){state.setText(error.getMessage());}}
    private void pauseDots(String reason){projection=null;removeDots();if(menu!=null){remove(menu);menu=null;}if(state!=null)state.setText(reason);dirty=true;}
    private boolean ownsTouch(float x,float y){for(View view:windows){int[] at=new int[2];view.getLocationOnScreen(at);if(x>=at[0]&&y>=at[1]&&x<=at[0]+view.getWidth()&&y<=at[1]+view.getHeight())return true;}return false;}
    @android.annotation.SuppressLint("RtlHardcoded") // Calibrated pixel coordinates use the physical left screen edge in every locale.
    private WindowManager.LayoutParams params(int width,int height,int x,int y,boolean watch){WindowManager.LayoutParams p=new WindowManager.LayoutParams(width,height,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_SECURE|(watch?WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH:0),PixelFormat.TRANSLUCENT);p.gravity=Gravity.TOP|Gravity.LEFT;p.x=x;p.y=y;return p;}
    private void add(View view,WindowManager.LayoutParams p){try{manager.addView(view,p);windows.add(view);}catch(Exception e){android.util.Log.e("Persephone","Overlay window rejected",e);stopSelf();}}
    private void remove(View view){if(view==null)return;try{manager.removeView(view);}catch(IllegalArgumentException ignored){}windows.remove(view);dots.remove(view);}
    private void removeDots(){for(View dot:new ArrayList<>(dots))remove(dot);dots.clear();}
    private void clearWindows(){for(View view:new ArrayList<>(windows))remove(view);windows.clear();dots.clear();legend=null;menu=null;map=null;anchorA=null;anchorB=null;}
    private void drag(View handle,View window,WindowManager.LayoutParams p){handle.setOnTouchListener(new View.OnTouchListener(){float x,y;int originalX,originalY;public boolean onTouch(View view,MotionEvent event){if(event.getAction()==MotionEvent.ACTION_DOWN){x=event.getRawX();y=event.getRawY();originalX=p.x;originalY=p.y;return true;}if(event.getAction()==MotionEvent.ACTION_MOVE){p.x=originalX+(int)(event.getRawX()-x);p.y=originalY+(int)(event.getRawY()-y);manager.updateViewLayout(window,p);if(window==legend){legendX=p.x;legendY=p.y;}return true;}if(event.getAction()==MotionEvent.ACTION_UP){view.performClick();return true;}return false;}});}
    private int[] screenSize(){android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();manager.getDefaultDisplay().getRealMetrics(metrics);return new int[]{metrics.widthPixels,metrics.heightPixels};}
    private int dp(int n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
    private TextView text(String text,int size,int color){TextView view=new TextView(this);view.setText(text);view.setTextSize(size);view.setTextColor(color);view.setTypeface(Typeface.MONOSPACE);return view;}
    private Button button(String title,Runnable action){Button button=new Button(this);button.setText(title);button.setTextSize(11);button.setTextColor(0xff36f4e3);button.setPadding(0,0,0,0);button.setMinimumWidth(0);button.setMinWidth(0);button.setOnClickListener(v->{if(new PinGate(this).unlocked())action.run();else stopSelf();});HudBackground.style(button);return button;}
}
