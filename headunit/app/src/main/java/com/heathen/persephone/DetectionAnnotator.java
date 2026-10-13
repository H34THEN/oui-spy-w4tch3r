package com.heathen.persephone;
import org.json.*;
import java.util.*;

final class DetectionAnnotator {
    static DetectorTags.Result annotate(JSONObject row) throws JSONException {
        List<String> services=new ArrayList<>();Set<Integer> manufacturers=new HashSet<>();
        JSONArray uuid=row.optJSONArray("services"),ids=row.optJSONArray("manufacturer_ids");
        if(uuid!=null)for(int i=0;i<uuid.length();i++)services.add(uuid.optString(i));
        if(ids!=null)for(int i=0;i<ids.length();i++)manufacturers.add(ids.optInt(i));
        JSONObject data=row.optJSONObject("manufacturer_data");if(data!=null){Iterator<String> keys=data.keys();while(keys.hasNext()){try{manufacturers.add(Integer.parseInt(keys.next(),16));}catch(NumberFormatException ignored){}}}
        DetectorTags.Result result=DetectorTags.classify(row.optString("address"),row.optString("name"),services,manufacturers,row.optBoolean("wildcard_probe"),row.optBoolean("ie_fingerprint"));
        row.put("tags",new JSONArray(result.tags)).put("confidence",result.confidence).put("priority",result.priority).put("ruleset",DetectorTags.RULESET)
            .put("rule_ids",new JSONArray(result.rules)).put("evidence",String.join("; ",result.reasons));return result;
    }
    static SignalMapModel.Point point(JSONObject row){
        if(!row.has("latitude")||!row.has("longitude")||row.optBoolean("gps_mock"))return null;
        long time;try{time=java.time.Instant.parse(row.optString("time")).toEpochMilli();}catch(Exception invalid){return null;}
        SignalMapModel.Point point=new SignalMapModel.Point(row.optDouble("latitude"),row.optDouble("longitude"),row.optDouble("accuracy"),row.optInt("rssi",100),row.optString("radio")+"/"+row.optString("address"),row.optJSONArray("tags")==null?"":row.optJSONArray("tags").toString(),time,row.optString("source").equals("DEMO"));
        return point.valid()?point:null;
    }
}
