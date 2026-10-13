package com.heathen.persephone;
import android.content.Context;
import android.graphics.*;
import android.net.http.HttpResponseCache;
import android.os.*;
import android.util.LruCache;
import java.io.*;
import java.net.*;
import javax.net.ssl.HttpsURLConnection;
import java.util.*;
import java.util.concurrent.*;

/** Requests only currently visible OSM tiles after explicit foreground approval. */
final class TileCache {
    private final ExecutorService workers=Executors.newFixedThreadPool(2);
    private final LruCache<String,Bitmap> memory=new LruCache<>(48);
    private final Set<String> pending=Collections.synchronizedSet(new HashSet<>());
    private final Map<String,Long> failures=new HashMap<>();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private volatile boolean online; private volatile boolean closed; private final boolean cacheReady;
    TileCache(Context context) {
        boolean ready=false;
        try { if(HttpResponseCache.getInstalled()==null) HttpResponseCache.install(new File(context.getCacheDir(),"map-http"),32L*1024*1024);ready=true; } catch(IOException ignored){}
        cacheReady=ready;
    }
    void online(boolean enabled) {online=enabled&&cacheReady;}
    boolean isOnline(){return online;}
    Bitmap get(int z,int x,int y,Runnable redraw) {
        int extent=1<<z; if(y<0||y>=extent) return null; x=((x%extent)+extent)%extent;
        String key=z+"/"+x+"/"+y;Bitmap bitmap=memory.get(key);if(bitmap!=null)return bitmap;
        if(closed||!online||pending.contains(key)||System.currentTimeMillis()-failures.getOrDefault(key,0L)<60000) return null;
        pending.add(key); workers.execute(()->{
            HttpsURLConnection connection=null;
            try {
                if(!online||closed)return;
                connection=(HttpsURLConnection)new URL("https://tile.openstreetmap.org/"+key+".png").openConnection();
                connection.setRequestProperty("User-Agent","PersephoneRF/0.2 (+https://github.com/Eidolon-34/oui-spy-w4tch3r)");
                connection.setConnectTimeout(5000);connection.setReadTimeout(5000);connection.setUseCaches(true);
                try(InputStream stream=connection.getInputStream()) {Bitmap image=BitmapFactory.decodeStream(stream);if(image!=null)memory.put(key,image);else throw new IOException("Invalid tile");}
                ui.post(redraw);
            } catch(Exception failure){ui.post(()->failures.put(key,System.currentTimeMillis()));}
            finally {if(connection!=null)connection.disconnect();pending.remove(key);}
        });return null;
    }
    void close(){closed=true;online=false;workers.shutdownNow();}
}
