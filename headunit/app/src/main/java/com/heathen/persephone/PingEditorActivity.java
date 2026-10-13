package com.heathen.persephone;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.location.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

@android.annotation.SuppressLint("SetTextI18n")
public final class PingEditorActivity extends Activity {
    private PingStore store;private String id;private EditText nickname,note;private TextView info;private LinearLayout photos;
    private final Handler ui=new Handler(Looper.getMainLooper());private final ExecutorService io=Executors.newSingleThreadExecutor();
    private LocationManager locations;private LocationListener listener;private Runnable timeout;private String cameraFile;private boolean automaticActionDone;
    @Override public void onCreate(Bundle saved){super.onCreate(saved);if(!PinGate.require(this))return;getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);OverlayService.hideForEditor(this);
        store=PingStore.get(this);id=getIntent().getStringExtra("ping_id");JSONObject ping=store.getPing(id);if(ping==null){finish();return;}
        if(saved!=null){cameraFile=saved.getString("camera_file");automaticActionDone=saved.getBoolean("automatic_action_done");}
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(40),dp(18),dp(22));root.setBackground(new HudBackground());
        root.addView(text("PING // FIELD NOTES",22,0xffffde43));root.addView(text(ping.optString("radio")+" / "+ping.optString("address"),14,0xff36f4e3));
        root.addView(text("GPS marks your phone position. It does not verify the transmitter's location or identity.",13,0xffafc4ce));
        nickname=input("Nickname (80 characters)");nickname.setText(saved==null?ping.optString("nickname"):saved.getString("nickname"));note=input("Observation note");note.setSingleLine(false);note.setText(saved==null?ping.optString("note"):saved.getString("note"));root.addView(nickname);root.addView(note);
        info=text("",13,0xffafc4ce);root.addView(info);root.addView(button("Capture fresh phone GPS",this::captureGps));root.addView(button("Attach existing photo",this::pickPhoto));root.addView(button("Take a photo",this::camera));
        photos=new LinearLayout(this);photos.setOrientation(LinearLayout.VERTICAL);root.addView(photos);root.addView(button("Save nickname / note",this::save));root.addView(button("Done / return to map",()->{save();OverlayService.showAfterEditor(this);finish();}));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(root);setContentView(scroll);refresh();
    }
    @Override protected void onResume(){super.onResume();if(!PinGate.require(this)||nickname==null)return;if(!automaticActionDone){automaticActionDone=true;String action=getIntent().getStringExtra("action");ui.post(()->{if("gps".equals(action))captureGps();else if("photo".equals(action))pickPhoto();else if("name".equals(action))nickname.requestFocus();});}}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);if(nickname==null)return;state.putString("camera_file",cameraFile);state.putBoolean("automatic_action_done",automaticActionDone);state.putString("nickname",nickname.getText().toString());state.putString("note",note.getText().toString());}
    @Override protected void onStop(){cancelGps();super.onStop();}
    @Override protected void onDestroy(){io.shutdown();super.onDestroy();}
    private void save(){if(!PinGate.require(this))return;try{store.nickname(id,nickname.getText().toString(),note.getText().toString());io.execute(()->{try{store.flush();}catch(IOException e){ui.post(()->info.setText("Save failed: "+e.getMessage()));}});info.setText("Saved locally");}catch(Exception e){info.setText(e.getMessage());}}
    private void captureGps(){
        if(!PinGate.require(this))return;
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},30);return;}
        cancelGps();info.setText("Acquiring a fresh real GPS fix (up to 15 seconds)…");locations=(LocationManager)getSystemService(LOCATION_SERVICE);
        listener=new LocationListener(){public void onLocationChanged(Location fix){if(!PinGate.require(PingEditorActivity.this)){cancelGps();return;}long age=SystemClock.elapsedRealtime()-fix.getElapsedRealtimeNanos()/1000000;if(age<0||age>10000||!fix.hasAccuracy()||fix.isFromMockProvider()||fix.getAccuracy()>100)return;try{store.phoneFix(id,fix.getLatitude(),fix.getLongitude(),fix.getAccuracy(),false);store.flush();cancelGps();refresh();}catch(Exception e){info.setText("GPS note failed: "+e.getMessage());}}public void onProviderEnabled(String p){}public void onProviderDisabled(String p){}public void onStatusChanged(String p,int status,Bundle extras){}};
        try{boolean requested=false;for(String provider:new String[]{LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER})if(locations.isProviderEnabled(provider)){locations.requestLocationUpdates(provider,1000,0,listener,Looper.getMainLooper());requested=true;}if(!requested){cancelGps();info.setText("Enable Android Location / GPS first");return;}timeout=()->{cancelGps();info.setText("No fresh real fix with ≤100 m accuracy. Try outdoors.");};ui.postDelayed(timeout,15000);}catch(SecurityException e){cancelGps();info.setText("Location permission missing/revoked");}
    }
    private void cancelGps(){if(timeout!=null)ui.removeCallbacks(timeout);timeout=null;if(locations!=null&&listener!=null){try{locations.removeUpdates(listener);}catch(SecurityException ignored){}}listener=null;}
    private void pickPhoto(){if(!PinGate.require(this))return;try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),31);}catch(ActivityNotFoundException e){info.setText("No image document picker available");}}
    private void camera(){if(!PinGate.require(this))return;cameraFile=UUID.randomUUID()+".jpg";Uri output=PhotoProvider.uri(this,cameraFile);Intent intent=new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT,output).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newRawUri("Persephone photo",output));try{startActivityForResult(intent,32);}catch(ActivityNotFoundException e){cameraFile=null;info.setText("No camera app available; attach an existing photo");}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==32&&cameraFile!=null)revokeUriPermission(PhotoProvider.uri(this,cameraFile),Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);if(result!=RESULT_OK||!new PinGate(this).unlocked()){if(request==32&&cameraFile!=null){new File(store.photos,cameraFile).delete();cameraFile=null;}return;}if(request==31&&data!=null&&data.getData()!=null)importPhoto(data.getData(),null);else if(request==32&&cameraFile!=null){String temporary=cameraFile;cameraFile=null;importPhoto(PhotoProvider.uri(this,temporary),temporary);}}
    private void importPhoto(Uri source,String temporary){info.setText("Copying a private, resized JPEG attachment…");io.execute(()->{
        File raw=new File(getCacheDir(),"photo-"+UUID.randomUUID()+".tmp"),output=new File(store.photos,UUID.randomUUID()+".jpg");
        try{long totalPhotos=0;File[] existing=store.photos.listFiles();if(existing!=null)for(File file:existing)totalPhotos+=file.length();if(totalPhotos>128L*1024*1024)throw new IOException("Private photos reached 128 MiB; export/remove photos first");
            try(InputStream in=getContentResolver().openInputStream(source);OutputStream out=new FileOutputStream(raw)){if(in==null)throw new IOException("Image unavailable");byte[] bytes=new byte[8192];int n,total=0;while((n=in.read(bytes))!=-1){total+=n;if(total>24*1024*1024)throw new IOException("Image exceeds 24 MiB");out.write(bytes,0,n);}}
            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(raw.getAbsolutePath(),options);if(options.outWidth<=0||options.outHeight<=0||options.outWidth>100000||options.outHeight>100000)throw new IOException("Unsupported image");options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>2048)options.inSampleSize*=2;options.inJustDecodeBounds=false;Bitmap bitmap=BitmapFactory.decodeFile(raw.getAbsolutePath(),options);if(bitmap==null)throw new IOException("Image decode failed");bitmap=orient(bitmap,raw);try(OutputStream out=new FileOutputStream(output)){if(!bitmap.compress(Bitmap.CompressFormat.JPEG,90,out))throw new IOException("JPEG encode failed");}finally{bitmap.recycle();}
            if(totalPhotos+output.length()>128L*1024*1024)throw new IOException("Private photos reached 128 MiB; export/remove photos first");
            if(!new PinGate(this).unlocked())throw new IOException("Passcode unlock expired; renew in the app");
            store.photo(id,output.getName());store.flush();ui.post(this::refresh);
        }catch(Exception e){output.delete();ui.post(()->info.setText("Photo failed: "+e.getMessage()));}finally{raw.delete();if(temporary!=null)new File(store.photos,temporary).delete();}
    });}
    private static Bitmap orient(Bitmap source,File file)throws IOException {
        androidx.exifinterface.media.ExifInterface exif=new androidx.exifinterface.media.ExifInterface(file);
        int orientation=exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,1);Matrix transform=new Matrix();
        switch(orientation){case 2:transform.setScale(-1,1);break;case 3:transform.setRotate(180);break;case 4:transform.setScale(1,-1);break;case 5:transform.setRotate(90);transform.postScale(-1,1);break;case 6:transform.setRotate(90);break;case 7:transform.setRotate(-90);transform.postScale(-1,1);break;case 8:transform.setRotate(-90);break;default:return source;}
        Bitmap result=Bitmap.createBitmap(source,0,0,source.getWidth(),source.getHeight(),transform,true);if(result!=source)source.recycle();return result;
    }
    private void refresh(){JSONObject ping=store.getPing(id);if(ping==null)return;JSONObject fix=ping.optJSONObject("phone_fix");info.setText(fix==null?"No phone GPS note captured yet":String.format(Locale.ROOT,"PHONE FIX %.6f, %.6f ±%.0f m\n%s",fix.optDouble("latitude"),fix.optDouble("longitude"),fix.optDouble("accuracy"),fix.optString("time")));photos.removeAllViews();JSONArray names=ping.optJSONArray("photos");if(names!=null)for(int i=0;i<names.length();i++){String name=names.optString(i);if(!name.matches("[0-9a-f-]{36}\\.jpg"))continue;ImageView image=new ImageView(this);BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=4;Bitmap bitmap=BitmapFactory.decodeFile(new File(store.photos,name).getAbsolutePath(),options);image.setImageBitmap(bitmap);image.setAdjustViewBounds(true);image.setContentDescription("Private photo attachment. Long press to remove this copy.");image.setOnLongClickListener(v->{new AlertDialog.Builder(this).setTitle("Remove private attachment?").setMessage("The original gallery image is unchanged. This removes the private copy for this ping.").setPositiveButton("Remove",(d,w)->{try{if(!PinGate.require(this))return;store.removePhoto(id,name);refresh();}catch(Exception error){info.setText(error.getMessage());}}).setNegativeButton("Cancel",null).show();return true;});photos.addView(image,new LinearLayout.LayoutParams(-1,dp(180)));}}
    private TextView text(String value,int size,int color){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setTypeface(Typeface.MONOSPACE);view.setPadding(0,dp(6),0,dp(6));return view;}
    private EditText input(String hint){EditText edit=new EditText(this);edit.setHint(hint);edit.setHintTextColor(0xffafc4ce);edit.setTextColor(Color.WHITE);edit.setSingleLine(true);return edit;}
    private Button button(String title,Runnable action){Button button=new Button(this);button.setText(title);button.setTextColor(0xff36f4e3);button.setOnClickListener(v->{if(PinGate.require(this))action.run();});HudBackground.style(button);return button;}
    private int dp(int n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
}
