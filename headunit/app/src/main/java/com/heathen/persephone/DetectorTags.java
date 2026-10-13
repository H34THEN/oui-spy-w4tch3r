package com.heathen.persephone;

import java.util.*;

/** Explainable community signatures: candidates, never proof of camera/model identity. */
final class DetectorTags {
    static final String RULESET = "2026-10-13.2";
    // Public hardware-prefix facts from CYD-Flock-You / flock-back. Shared vendors are weak.
    private static final Set<String> COMMUNITY = new HashSet<>(Arrays.asList(
        "70:C9:4E","3C:91:80","D8:F3:BC","80:30:49","B8:35:32","14:5A:FC","74:4C:A1","08:3A:88","9C:2F:9D","C0:35:32",
        "94:08:53","E4:AA:EA","F4:6A:DD","F8:A2:D6","24:B2:B9","00:F4:8D","D0:39:57","E8:D0:FC","E0:4F:43","B8:1E:A4",
        "70:08:94","58:8E:81","EC:1B:BD","3C:71:BF","58:00:E3","90:35:EA","5C:93:A2","64:6E:69","48:27:EA","A4:CF:12",
        "82:6B:F2","E0:0A:F6","04:0D:84","F0:82:C0","1C:34:F1","38:5B:44","94:34:69","B4:E3:F9"));
    static final class Result {
        final LinkedHashSet<String> tags = new LinkedHashSet<>();
        final List<String> reasons = new ArrayList<>(), rules = new ArrayList<>();
        int priority;
        String confidence = "NONE";
        void add(String tag, String rule, String reason, int rank) {
            tags.add(tag); if (!rules.contains(rule)) { rules.add(rule); reasons.add(reason); }
            priority = Math.max(priority, rank);
        }
        String label() { return String.join(" / ", tags) + (tags.isEmpty() ? "" : " · " + confidence + " · CANDIDATE"); }
    }
    static String uuid(String raw) {
        String s = raw.toLowerCase(Locale.ROOT); if (s.startsWith("0x")) s = s.substring(2);
        if (s.matches("[0-9a-f]{4}")) s = "0000" + s + "-0000-1000-8000-00805f9b34fb";
        return s;
    }
    static Result classify(String address, String name, Collection<String> services, Set<Integer> manufacturers, boolean wildcard, boolean fingerprint) {
        Result result = new Result(); String n = name.trim().toLowerCase(Locale.ROOT);
        boolean namedFlock = n.matches("^(flock|flocksafety|flockos)(?:[-_ ].*)?$");
        if (namedFlock) result.add("FLOCK", "name.flock", "Advertised Flock name; spoofable, model unknown", 3);
        if (n.matches("^falcon(?:[-_ ].*)?$")) {
            result.add("FALCON", "name.falcon", "Falcon name is a model hint only; unrelated devices can use it", 4);
            result.tags.add("FLOCK"); result.tags.add("LPR");
        }
        if (n.matches("^raven(?:[-_ ].*)?$")) { result.add("RAVEN", "name.raven", "Raven name; sensor identity unverified", 4); result.tags.add("FLOCK"); result.tags.add("AUDIO SENSOR"); }
        if (n.equals("fs ext battery") || n.matches("^penguin(?:[-_ ].*)?$")) result.add("FLOCK ACCESSORY", "name.battery", "Community battery/accessory name; not proof of a camera", 3);
        if (n.matches("^pigvision(?:[-_ ].*)?$")) result.add("CAMERA", "name.pigvision", "Community Pigvision name signature; function unverified", 3);
        if (n.matches("^(condor|sparrow)(?:[-_ ].*)?$")) result.add("CAMERA", "name.flock_family", "Product-family name hint only", 3);
        if (n.matches("^(hikvision|dahua|axis|reolink|wyze|ring|eufy)(?:[-_ ].*)?$")) result.add("CAMERA VENDOR", "name.camera_vendor", "Camera vendor name hint; may be another product or spoofed", 2);
        if (n.matches("^(lpr|alpr)(?:[-_ ].*)?$")) result.add("LPR", "name.lpr", "Advertised LPR/ALPR label; unverified", 3);
        String prefix = RfCore.validMac(address) ? address.substring(0, 8).toUpperCase(Locale.ROOT) : "";
        boolean direct = prefix.equals("B4:1E:52"), shared = COMMUNITY.contains(prefix);
        if (direct) result.add("FLOCK", "oui.flock_direct", "Flock vendor prefix; does not establish model or function", 3);
        int ravenServices = 0; Set<String> uniqueServices=new HashSet<>();
        for (String service : services) {
            String s = uuid(service); if(!uniqueServices.add(s)) continue;
            if (s.matches("00003[1-5]00-0000-1000-8000-00805f9b34fb")) ravenServices++;
            if (s.equals("e8ccbb38-9532-46a8-9fe5-1814df172e6f")) result.add("FLOCK ACCESSORY", "service.accessory", "Community Flock accessory service; unverified", 3);
        }
        if (ravenServices > 0) { result.add("RAVEN", "service.raven", ravenServices + " advertised custom Raven-family service(s); unverified", 4); result.tags.add("FLOCK"); result.tags.add("AUDIO SENSOR"); }
        // 180A / 1809 / 1819 / Nordic UART are generic, never Raven evidence by themselves.
        if (manufacturers.contains(0x09c8)) result.add("SHARED HARDWARE", "manufacturer.xuntong", "XUNTONG manufacturer ID; shared vendor, weak evidence", 1);
        if (shared) result.add("SHARED HARDWARE", "oui.community", "Community hardware prefix; common vendor or local prefix, not camera proof", 1);
        if ((direct || shared) && wildcard) result.add("FLOCK", "wifi.wildcard_prefix", "Known hardware prefix plus wildcard probe; firmware-dependent candidate", 3);
        if ((direct || shared) && wildcard && fingerprint) { result.add("FLOCK", "wifi.ordered_ie", "Prefix + wildcard + ordered community IE fingerprint; corroborated, still unverified", 5); result.confidence = "CORROBORATED"; }
        else if (ravenServices >= 2 || (direct && namedFlock)) result.confidence = "MEDIUM";
        else if (!result.tags.isEmpty()) result.confidence = "LOW";
        return result;
    }
}
