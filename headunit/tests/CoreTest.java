package com.heathen.persephone;
import java.util.*;
public final class CoreTest {
    private static int count;
    static void check(boolean value, String message) { count++; if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        check(RfCore.csv("a,\"b\"\n").equals("\"a,\"\"b\"\"\n\""), "CSV quoting");
        check(RfCore.csv(" \t=HYPERLINK(\"x\")").startsWith("\"'"), "SSID formula guard");
        check(RfCore.csv("@SUM(1)").startsWith("\"'"), "formula guard");
        check(RfCore.evidence("24:6F:28:00:00:01", "", List.of(), Set.of()).isEmpty(), "generic chip vendor must not imply surveillance");
        check(RfCore.evidence("B4:1E:52:00:00:01", "", List.of(), Set.of()).contains("does not establish"), "vendor uncertainty");
        check(RfCore.evidence("02:00:00:00:00:01", "Penguin-123", List.of(), Set.of()).contains("not proof"), "name evidence for randomized address");
        check(RfCore.evidence("", "", List.of("e8ccbb38-9532-46a8-9fe5-1814df172e6f"), Set.of()).contains("accessory"), "service evidence");
        check(RfCore.evidence("", "", List.of("0x3100"), Set.of()).contains("Raven"), "USB short UUID evidence");
        check(RfCore.evidence("", "", List.of(), Set.of(0x09c8)).contains("weak"), "shared manufacturer uncertainty");
        check(!RfCore.matches(" , ", "AA:BB:CC:11:22:33", "hello", "", List.of()), "empty watchlist");
        check(RfCore.matches(" xx, AA:BB:CC ", "aa:bb:cc:11:22:33", "hello", "", List.of()), "case insensitive prefix");
        check(!RfCore.allowedTarget("bad", 100, 200, false, true), "malformed target");
        check(!RfCore.allowedTarget("AA:BB:CC:11:22:33", 100, 200, true, true), "demo cannot connect");
        check(!RfCore.allowedTarget("AA:BB:CC:11:22:33", 100, 200, false, false), "consent required");
        check(!RfCore.allowedTarget("AA:BB:CC:11:22:33", 100, 30101, false, true), "stale target");
        check(!RfCore.allowedTarget("AA:BB:CC:11:22:33", 100, 99, false, true), "clock reversal");
        check(RfCore.allowedTarget("AA:BB:CC:11:22:33", 100, 30100, false, true), "fresh exact target");
        byte[] salt = RfCore.salt(), hash = RfCore.hash("9818".toCharArray(), salt);
        check(RfCore.verify("9818".toCharArray(), salt, hash), "default verifies");
        check(!RfCore.verify("9819".toCharArray(), salt, hash), "wrong PIN rejects");
        check(!Arrays.equals(hash, RfCore.hash("9818".toCharArray(), RfCore.salt())), "per-install salt");
        check(Math.abs(RfCore.smooth(-80, -60) - -73) < .01, "RSSI smoothing");
        DetectorTags.Result falcon=DetectorTags.classify("02:00:00:00:00:01","Falcon-test",List.of(),Set.of(),false,false);
        check(falcon.tags.contains("FALCON")&&falcon.tags.contains("LPR")&&falcon.confidence.equals("LOW"),"Falcon model hint, not confirmation");
        check(DetectorTags.classify("","",List.of("180a","1809","1819"),Set.of(),false,false).tags.isEmpty(),"generic services never identify Raven");
        check(DetectorTags.classify("","",List.of("3100","3200"),Set.of(),false,false).confidence.equals("MEDIUM"),"custom Raven corroboration");
        check(!DetectorTags.classify("70:C9:4E:00:00:01","",List.of(),Set.of(),false,false).tags.contains("FLOCK"),"shared vendor alone does not identify Flock");
        check(DetectorTags.classify("70:C9:4E:00:00:01","",List.of(),Set.of(),true,true).confidence.equals("CORROBORATED"),"ordered probe signature plus prefix");
        check(DetectorTags.classify("24:6F:28:00:00:01","",List.of(),Set.of(),true,true).tags.isEmpty(),"probe fingerprint without corroborating prefix");
        check(DetectorTags.classify("","myfalconmusic",List.of(),Set.of(),false,false).tags.isEmpty(),"name token boundaries");
        List<SignalMapModel.Point> route=new ArrayList<>();double originLat=36.1699,originLon=-115.1398,lonScale=111320*Math.cos(Math.toRadians(originLat));
        for(int i=0;i<24;i++){double a=i*Math.PI/12,x=(70+(i%3)*25)*Math.cos(a),y=(55+(i%4)*18)*Math.sin(a),distance=Math.hypot(x-45,y-20);int rssi=(int)Math.round(-28-22*Math.log10(distance));route.add(new SignalMapModel.Point(originLat+y/111320,originLon+x/lonScale,5,rssi,"target","FLOCK",i*1000,true));}
        SignalMapModel.Estimate estimate=SignalMapModel.estimate(route,"target");
        check(estimate!=null&&estimate.demo,"synthetic signal model fit");
        check(SignalMapModel.meters(estimate.lat,estimate.lon,originLat+20/111320,originLon+45/lonScale)<25,"model recovers controlled synthetic emitter");
        check(SignalMapModel.estimate(route.subList(0,3),"target")==null,"sparse data must not locate emitter");
        List<SignalMapModel.Point> straight=new ArrayList<>();for(int i=0;i<12;i++)straight.add(new SignalMapModel.Point(originLat,originLon+i*.0001,5,-40-i*2,"target","",i*1000,false));
        check(SignalMapModel.estimate(straight,"target")==null,"straight driving route is underconstrained");
        check(!new SignalMapModel.Point(Double.NaN,0,5,-40,"","",0,false).valid(),"invalid coordinate rejection");
        check(DetectorTags.classify("","",List.of("3100","0x3100"),Set.of(),false,false).confidence.equals("LOW"),"duplicate service IDs must not increase confidence");
        OverlayProjection projection=OverlayProjection.calibrate(36,-115,100,200,36,-114.999,300,250);
        double[] anchor=projection.screen(36,-114.999),middle=projection.screen(36,-114.9995);
        check(Math.abs(anchor[0]-300)<.001&&Math.abs(anchor[1]-250)<.001,"aligned rotated second anchor");
        check(Math.abs(middle[0]-200)<.001&&Math.abs(middle[1]-225)<.001,"Mercator midpoint projection");
        boolean invalid=false;try{OverlayProjection.calibrate(36,-115,100,100,36,-115,300,300);}catch(IllegalArgumentException expected){invalid=true;}
        check(invalid,"coincident landmarks rejected");
        invalid=false;try{OverlayProjection.calibrate(36,-115,100,100,36,-114.999,120,120);}catch(IllegalArgumentException expected){invalid=true;}
        check(invalid,"insufficient screen separation rejected");
        check(!OverlayProjection.validGeo(Double.NaN,0)&&!OverlayProjection.validGeo(89,0),"unsupported map coordinates rejected");
        System.out.println(count + " core policy/export checks passed");
    }
}
