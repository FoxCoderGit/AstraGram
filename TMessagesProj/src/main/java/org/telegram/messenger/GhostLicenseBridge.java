package org.telegram.messenger;

import android.app.Activity;

/** Fail closed in GhostGram; other Telegram build variants are unaffected. */
public final class GhostLicenseBridge {
    public static String updateInfo(){try{return (String)Class.forName("org.ghostgram.messenger.license.LicenseManager").getMethod("updateInfo").invoke(null);}catch(Throwable ignored){return "{}";}}
    public static void updateOption(String name,boolean value){try{Class.forName("org.ghostgram.messenger.license.LicenseManager").getMethod("updateOption",String.class,boolean.class).invoke(null,name,value);}catch(Throwable ignored){}}
    public static void checkUpdates(Activity activity){try{Class.forName("org.ghostgram.messenger.license.LicenseManager").getMethod("checkUpdates",Activity.class).invoke(null,activity);}catch(Throwable ignored){}}
    public static void configureLicenseConnection(javax.net.ssl.HttpsURLConnection connection) throws java.io.IOException {
        try { Class.forName("org.ghostgram.messenger.license.LicenseManager").getMethod("configureLicenseConnection",javax.net.ssl.HttpsURLConnection.class).invoke(null,connection); }
        catch(Throwable error) { throw new java.io.IOException("Pinned connection unavailable",error); }
    }
    public static void showUpdates(Activity activity) {
        try { Class.forName("org.ghostgram.messenger.license.LicenseManager").getMethod("showUpdates",Activity.class).invoke(null,activity); }
        catch(Throwable ignored) { }
    }
    public static void recordError(String name) {
        GhostDiagnosticLog.record(name);
        if(!enforced())return;
        try { Class.forName("org.ghostgram.messenger.license.LicenseManager").getMethod("recordError",String.class).invoke(null,name); }
        catch(Throwable ignored) { }
    }
    public static String musicRelayProof(String nonce){
        if(!enforced())return null;
        try{return (String)Class.forName("org.ghostgram.messenger.license.LicenseManager").getMethod("musicRelayProof",String.class).invoke(null,nonce);}catch(Throwable ignored){return null;}
    }
    private static boolean enforced() {
        return ApplicationLoader.applicationContext != null && "org.ghostgram.messenger".equals(ApplicationLoader.applicationContext.getPackageName());
    }
    public static boolean allowed() {
        if (!enforced()) return true;
        try { return (Boolean) Class.forName("org.ghostgram.messenger.license.LicenseManager").getMethod("allowed").invoke(null); }
        catch (Throwable ignored) { return false; }
    }
    public static boolean require(Activity activity) {
        if (!enforced()) return true;
        try { return (Boolean) Class.forName("org.ghostgram.messenger.license.LicenseManager").getMethod("require", Activity.class).invoke(null, activity); }
        catch (Throwable ignored) { activity.finish(); return false; }
    }
}
