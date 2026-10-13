package com.heathen.persephone;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

final class PinGate {
    private final SharedPreferences prefs;
    private final android.content.ContentResolver contextResolver;
    PinGate(Context context) {
        contextResolver=context.getContentResolver();
        prefs = context.getSharedPreferences("gate", Context.MODE_PRIVATE);
        if (!prefs.contains("hash")) set("9818", true);
    }
    static boolean require(android.app.Activity activity) {
        if(new PinGate(activity).unlocked())return true;
        activity.startActivity(new android.content.Intent(activity,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP));
        activity.finish();return false;
    }
    boolean isDefault() { return prefs.getBoolean("default", true); }
    static final long UNLOCK_MS=60L*60*1000;
    boolean unlocked(){long now=android.os.SystemClock.elapsedRealtime();return prefs.getInt("unlockBoot",-1)==boot()&&now>=prefs.getLong("unlockIssued",Long.MAX_VALUE)&&now<prefs.getLong("unlockUntil",0);}
    long unlockRemainingMs(){return unlocked()?prefs.getLong("unlockUntil",0)-android.os.SystemClock.elapsedRealtime():0;}
    void lock(){if(!prefs.edit().remove("unlockUntil").remove("unlockIssued").remove("unlockBoot").commit())throw new IllegalStateException("Cannot persist lock");}
    private int boot(){return android.provider.Settings.Global.getInt(contextResolver,"boot_count",0);}
    long remainingMs() { return Math.max(0, prefs.getLong("blockedUntil", 0) - System.currentTimeMillis()); }
    boolean verify(String pin) {
        if (remainingMs() > 0) return false;
        boolean ok = RfCore.verify(pin.toCharArray(), decode("salt"), decode("hash"));
        int failures = ok ? 0 : prefs.getInt("failures", 0) + 1;
        SharedPreferences.Editor editor = prefs.edit().putInt("failures", failures);
        if(ok){long now=android.os.SystemClock.elapsedRealtime();editor.putLong("unlockIssued",now).putLong("unlockUntil",now+UNLOCK_MS).putInt("unlockBoot",boot());}
        if (!ok && failures >= 5) editor.putLong("blockedUntil", System.currentTimeMillis() + 60_000).putInt("failures", 0).remove("unlockUntil").remove("unlockIssued").remove("unlockBoot");
        if (!editor.commit()) throw new IllegalStateException("Cannot persist PIN attempts");
        return ok;
    }
    void set(String pin, boolean initial) {
        byte[] salt = RfCore.salt();
        byte[] hash = RfCore.hash(pin.toCharArray(), salt);
        if (!prefs.edit().putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hash", Base64.encodeToString(hash, Base64.NO_WRAP))
            .remove("unlockUntil").remove("unlockIssued").remove("unlockBoot").putBoolean("default", initial).putInt("failures", 0).putLong("blockedUntil", 0).commit())
            throw new IllegalStateException("Cannot persist PIN");
    }
    private byte[] decode(String key) { return Base64.decode(prefs.getString(key, ""), Base64.NO_WRAP); }
}
