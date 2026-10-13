package com.heathen.persephone;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Private durable annotations. Coordinates always identify reception or a user-captured phone fix. */
final class PingStore {
    private static PingStore instance;
    static synchronized PingStore get(Context context){if(instance==null||!instance.directory.equals(context.getFilesDir()))instance=new PingStore(context);return instance;}
    private final Context context;final File directory,photos;private final AtomicFile file;
    private final LinkedHashMap<String,JSONObject> pings=new LinkedHashMap<>();
    private final ScheduledExecutorService writer=Executors.newSingleThreadScheduledExecutor();private ScheduledFuture<?> pending;
    private PingStore(Context context){this.context=context.getApplicationContext();directory=context.getFilesDir();photos=new File(directory,"ping-photos");if(!photos.isDirectory()&&!photos.mkdirs())throw new IllegalStateException("Photo storage unavailable");file=new AtomicFile(new File(directory,"pings.json"));
        try(FileInputStream in=file.openRead()){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){if(bytes.size()+n>4*1024*1024)throw new IOException("Annotation file too large");bytes.write(buffer,0,n);}JSONArray saved=new JSONObject(bytes.toString("UTF-8")).getJSONArray("pings");for(int i=0;i<saved.length();i++){JSONObject row=saved.getJSONObject(i);pings.put(row.getString("id"),row);}}catch(FileNotFoundException empty){}catch(Exception error){file.getBaseFile().renameTo(new File(directory,"pings-corrupt-"+System.currentTimeMillis()+".json"));pings.clear();android.util.Log.e("Persephone","Preserved unreadable annotation file",error);}
    }
    static String id(String key){return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();}
    synchronized String observe(JSONObject observation)throws JSONException{
        String key=(observation.optString("source").equals("DEMO")?"DEMO/":"")+observation.optString("radio")+"/"+observation.optString("address"),id=id(key);JSONObject ping=pings.get(id);
        if(ping==null){if(pings.size()>=512){String victim=null;int rank=Integer.MAX_VALUE;for(Map.Entry<String,JSONObject> old:pings.entrySet()){JSONObject row=old.getValue();if(row.optString("nickname").isEmpty()&&row.optString("note").isEmpty()&&!row.has("phone_fix")&&row.optJSONArray("photos").length()==0&&row.optInt("priority")<rank){victim=old.getKey();rank=row.optInt("priority");}}if(victim==null||rank>observation.optInt("priority"))return null;pings.remove(victim);}ping=new JSONObject().put("id",id).put("key",key).put("nickname","").put("note","").put("photos",new JSONArray());pings.put(id,ping);}
        for(String field:new String[]{"radio","address","name","tags","confidence","priority","source","time","rssi","rule_ids","evidence"})if(observation.has(field))ping.put(field,observation.get(field));
        SignalMapModel.Point point=DetectionAnnotator.point(observation);
        if(point!=null&&(!ping.has("reception")||point.rssi>=ping.getJSONObject("reception").optInt("rssi",-127))){ping.put("reception",new JSONObject().put("latitude",point.lat).put("longitude",point.lon).put("accuracy",point.accuracy).put("rssi",point.rssi).put("time",observation.optString("time")).put("position_role","strongest_receiver_observation").put("source",observation.optString("source")));}
        schedule();return id;
    }
    synchronized JSONObject getPing(String id){JSONObject ping=pings.get(id);if(ping==null)return null;try{return new JSONObject(ping.toString());}catch(JSONException impossible){throw new IllegalStateException(impossible);}}
    synchronized List<JSONObject> list(){List<JSONObject> list=new ArrayList<>();for(String id:pings.keySet())list.add(getPing(id));Collections.reverse(list);return list;}
    synchronized void nickname(String id,String name,String note)throws JSONException{JSONObject ping=require(id);ping.put("nickname",name.trim().substring(0,Math.min(80,name.trim().length()))).put("note",note.trim().substring(0,Math.min(1000,note.trim().length())));schedule();}
    synchronized void phoneFix(String id,double lat,double lon,double accuracy,boolean mock)throws JSONException{
        if(mock||!OverlayProjection.validGeo(lat,lon)||!Double.isFinite(accuracy)||accuracy<=0||accuracy>100)throw new IllegalArgumentException("A fresh real phone fix with ≤100 m accuracy is required");
        require(id).put("phone_fix",new JSONObject().put("latitude",lat).put("longitude",lon).put("accuracy",accuracy).put("time",Instant.now().toString()).put("position_role","user_captured_phone_position").put("source","PHONE_GPS_NOTE"));schedule();
    }
    synchronized void photo(String id,String name)throws JSONException{if(!name.matches("[0-9a-f-]{36}\\.jpg")||!new File(photos,name).isFile())throw new IllegalArgumentException("Invalid private photo");JSONArray list=require(id).getJSONArray("photos");if(list.length()>=8)throw new IllegalStateException("Maximum eight photos per ping");list.put(name);schedule();}
    synchronized void removePhoto(String id,String name)throws JSONException {
        JSONArray before=require(id).getJSONArray("photos"),after=new JSONArray();for(int i=0;i<before.length();i++)if(!before.getString(i).equals(name))after.put(before.getString(i));require(id).put("photos",after);schedule();
        boolean referenced=false;for(JSONObject ping:pings.values())if(ping.optJSONArray("photos").toString().contains(name))referenced=true;
        if(!referenced&&name.matches("[0-9a-f-]{36}\\.jpg"))new File(photos,name).delete();
    }
    synchronized void remove(String id){pings.remove(id);schedule();}
    private JSONObject require(String id){JSONObject row=pings.get(id);if(row==null)throw new IllegalArgumentException("Ping no longer exists");return row;}
    synchronized void flush()throws IOException{JSONObject snapshot=new JSONObject();try{JSONArray rows=new JSONArray();for(JSONObject row:pings.values())rows.put(new JSONObject(row.toString()));snapshot.put("schema",1).put("pings",rows);}catch(JSONException e){throw new IOException(e);}FileOutputStream out=null;try{out=file.startWrite();out.write(snapshot.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}catch(IOException e){if(out!=null)file.failWrite(out);throw e;}}
    private synchronized void schedule(){if(pending==null||pending.isDone())pending=writer.schedule(()->{try{flush();}catch(IOException e){android.util.Log.e("Persephone","Annotation persistence failed",e);}},1,TimeUnit.SECONDS);}
    static SignalMapModel.Point point(JSONObject ping){JSONObject fix=ping.optJSONObject("phone_fix");if(fix==null)fix=ping.optJSONObject("reception");if(fix==null)return null;long time;try{time=Instant.parse(fix.optString("time")).toEpochMilli();}catch(Exception e){return null;}SignalMapModel.Point p=new SignalMapModel.Point(fix.optDouble("latitude"),fix.optDouble("longitude"),fix.optDouble("accuracy"),ping.optInt("rssi",-100),ping.optString("key"),ping.optString("tags"),time,ping.optString("source").equals("DEMO"));return p.valid()?p:null;}
    void export(Uri tree)throws Exception{
        List<JSONObject> snapshot=list();flush();Uri parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));Uri folder=DocumentsContract.createDocument(context.getContentResolver(),parent,DocumentsContract.Document.MIME_TYPE_DIR,"persephone-pings-"+System.currentTimeMillis());if(folder==null)throw new IOException("Cannot create annotation folder");
        try{JSONArray records=new JSONArray(),features=new JSONArray();Set<String> photosToCopy=new HashSet<>();
            for(JSONObject ping:snapshot){records.put(ping);JSONArray names=ping.optJSONArray("photos");if(names!=null)for(int i=0;i<names.length();i++)photosToCopy.add(names.getString(i));SignalMapModel.Point point=point(ping);if(point!=null)features.put(new JSONObject().put("type","Feature").put("geometry",new JSONObject().put("type","Point").put("coordinates",new JSONArray().put(point.lon).put(point.lat))).put("properties",ping));}
            writeDocument(folder,"application/json","pings.json",new ByteArrayInputStream(new JSONObject().put("schema",1).put("pings",records).toString().getBytes(StandardCharsets.UTF_8)));
            writeDocument(folder,"application/geo+json","pings.geojson",new ByteArrayInputStream(new JSONObject().put("type","FeatureCollection").put("features",features).toString().getBytes(StandardCharsets.UTF_8)));
            for(String name:photosToCopy){if(!name.matches("[0-9a-f-]{36}\\.jpg"))throw new IOException("Invalid attachment path");writeDocument(folder,"image/jpeg",name,new FileInputStream(new File(photos,name)));}
        }catch(Exception failure){try{DocumentsContract.deleteDocument(context.getContentResolver(),folder);}catch(Exception ignored){}throw failure;}
    }
    private void writeDocument(Uri parent,String mime,String name,InputStream input)throws IOException{
        try(InputStream in=input){Uri uri=DocumentsContract.createDocument(context.getContentResolver(),parent,mime,name);if(uri==null)throw new IOException("Cannot create "+name);try(OutputStream out=context.getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("Cannot open "+name);byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}}
    }
}
