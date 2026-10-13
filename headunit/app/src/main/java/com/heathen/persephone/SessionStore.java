package com.heathen.persephone;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import org.json.JSONObject;
import org.json.JSONException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

final class SessionStore {
    static final long MAX_BYTES = 32L * 1024 * 1024;
    final File directory;
    private final Context context;
    SessionStore(Context context) {
        this.context = context;
        directory = new File(context.getFilesDir(), "sessions");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create session directory");
    }
    final class Session {
        final File file;
        private final BufferedWriter writer;
        private long size, lastFlush;
        Session(boolean demo) throws IOException {
            file = new File(directory, (demo ? "demo-" : "rf-") + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8) + ".jsonl");
            writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
        }
        // Deliberately reserve free space without evicting other apps' caches.
        @android.annotation.SuppressLint("UsableSpace")
        void append(JSONObject row) throws IOException {
            String line = row.toString() + "\n";
            size += line.getBytes(StandardCharsets.UTF_8).length;
            if (size > MAX_BYTES || directory.getUsableSpace() < 10L * 1024 * 1024) throw new IOException("Session limit or low storage; stop and export");
            writer.write(line);
            if (System.currentTimeMillis() - lastFlush > 1000) { writer.flush(); lastFlush = System.currentTimeMillis(); }
        }
        void close() throws IOException { writer.close(); }
    }
    List<File> files() {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".jsonl"));
        List<File> list = files == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(files));
        list.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return list;
    }
    void audit(String action, String target, String consent) throws Exception {
        JSONObject event = new JSONObject().put("time", Instant.now().toString()).put("action", action).put("target", target).put("consent", consent);
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "audit.jsonl"), true)) {
            out.write((event + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }
    void export(File source, Uri tree) throws Exception {
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        Uri json = null, csv = null, geo = null;
        try {
            json = DocumentsContract.createDocument(context.getContentResolver(), parent, "application/x-ndjson", source.getName());
            if (json == null) throw new IOException("Provider could not create JSONL");
            try (InputStream in = new FileInputStream(source); OutputStream out = context.getContentResolver().openOutputStream(json, "wt")) {
                if (out == null) throw new IOException("Provider could not open JSONL");
                byte[] bytes = new byte[8192]; int n;
                while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
            }
            csv = DocumentsContract.createDocument(context.getContentResolver(), parent, "text/csv", source.getName().replace(".jsonl", ".csv"));
            if (csv == null) throw new IOException("Provider could not create CSV");
            try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(source), StandardCharsets.UTF_8));
                 OutputStream stream = context.getContentResolver().openOutputStream(csv, "wt")) {
                if (stream == null) throw new IOException("Provider could not open CSV");
                BufferedWriter out = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8));
                out.write(RfCore.CSV_HEADER);
                String line;
                while ((line = in.readLine()) != null) {
                    JSONObject row;
                    try { row = new JSONObject(line); } catch (Exception truncatedTail) { continue; }
                    String[] keys = {"source", "time", "radio", "address", "name", "rssi", "frequency", "security", "evidence", "latitude", "longitude", "accuracy", "gps_age_ms", "tags", "confidence", "ruleset", "rule_ids"};
                    for (int i = 0; i < keys.length; i++) {
                        if (i > 0) out.write(',');
                        // Numeric fields are emitted numerically; text fields use formula-safe quoting.
                        Object v = row.opt(keys[i]);
                        out.write(v == null || v == JSONObject.NULL ? "" : v instanceof Number ? v.toString() : RfCore.csv(v.toString()));
                    }
                    out.write('\n');
                }
                out.flush();
            }
            geo=DocumentsContract.createDocument(context.getContentResolver(),parent,"application/geo+json",source.getName().replace(".jsonl",".geojson"));
            if(geo==null)throw new IOException("Provider could not create GeoJSON");
            try(BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(source),StandardCharsets.UTF_8));OutputStream stream=context.getContentResolver().openOutputStream(geo,"wt")) {
                if(stream==null)throw new IOException("Provider could not open GeoJSON");
                BufferedWriter out=new BufferedWriter(new OutputStreamWriter(stream,StandardCharsets.UTF_8));out.write("{\"type\":\"FeatureCollection\",\"features\":[");boolean first=true;String line;
                while((line=in.readLine())!=null){JSONObject row;try{row=new JSONObject(line);}catch(JSONException tail){continue;}SignalMapModel.Point point=DetectionAnnotator.point(row);if(point==null)continue;
                    JSONObject properties=new JSONObject(row.toString()).put("position_role","receiver_observation");
                    JSONObject feature=new JSONObject().put("type","Feature").put("geometry",new JSONObject().put("type","Point").put("coordinates",new org.json.JSONArray().put(point.lon).put(point.lat))).put("properties",properties);
                    if(!first)out.write(',');first=false;out.write(feature.toString());
                }
                out.write("]}");out.flush();
            }
        } catch (Exception error) {
            for (Uri uri : new Uri[]{json, csv, geo}) if (uri != null) {
                try { DocumentsContract.deleteDocument(context.getContentResolver(), uri); } catch (Exception ignored) {}
            }
            throw error;
        }
    }
}
