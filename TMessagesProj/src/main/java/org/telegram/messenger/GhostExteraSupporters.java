package org.telegram.messenger;

import android.content.SharedPreferences;
import org.telegram.tgnet.TLRPC;
import java.io.*;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;

/** Optional third-party data; kept separate from the AstraGram signed registry. */
public final class GhostExteraSupporters {
    private static final String ENDPOINT="https://api.exteragram.app/api/v1/profiles";
    private static final long TTL = GhostBadgeSyncJob.DAY_MS;
    private static volatile GhostExteraSupporterRegistry registry;
    private static volatile long fetchedAt;
    private static SharedPreferences prefs(){return ApplicationLoader.applicationContext.getSharedPreferences("veyl_extera_supporters_v1",0);}
    private static synchronized GhostExteraSupporterRegistry registry(){
        if(registry==null)try{
            fetchedAt=prefs().getLong("updated",0);
            registry=GhostExteraSupporterRegistry.parse(prefs().getString("profiles","[]"));
        }catch(Exception ignored){try{registry=GhostExteraSupporterRegistry.parse("[]");}catch(Exception impossible){throw new IllegalStateException(impossible);}fetchedAt=0;}
        return registry;
    }
    public static GhostBadgeRegistry.Badge badge(TLRPC.User user){
        if(!GhostOptionalFeatures.enabled(GhostOptionalFeatures.EXTERA_SUPPORTERS)||user.scam||user.fake)return null;
        GhostExteraSupporterRegistry data=registry();
        long age=System.currentTimeMillis()-fetchedAt;if(age<0||age>=TTL)return null;
        Long document=data.documents.get(user.id);
        return document==null?null:new GhostBadgeRegistry.Badge(GhostBadgeRegistry.Type.EXTERA_SUPPORTER,document);
    }
    public static void onPreferenceChanged(boolean enabled) {
        GhostBadgeSyncJob.setExteraEnabled(enabled);
        GhostBadges.notifyChanged();
    }

    static long syncIfDue() {
        registry();
        long age = System.currentTimeMillis() - fetchedAt;
        if (fetchedAt > 0 && age >= 0 && age < TTL) return TTL - age;
        if (!GhostOptionalFeatures.enabled(GhostOptionalFeatures.EXTERA_SUPPORTERS)) return TTL;
        HttpsURLConnection connection = null;
        try {
            connection = (HttpsURLConnection) new URL(ENDPOINT).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(false);
            if (connection.getResponseCode() != 200) throw new IOException("Profiles service unavailable");
            String text;
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 4 * 1024 * 1024) throw new IOException("Profiles response too large");
                    output.write(buffer, 0, count);
                }
                text = output.toString("UTF-8");
            }
            GhostExteraSupporterRegistry next = GhostExteraSupporterRegistry.parse(text);
            synchronized (GhostExteraSupporters.class) {
                if (!GhostOptionalFeatures.enabled(GhostOptionalFeatures.EXTERA_SUPPORTERS)) return TTL;
                long now = System.currentTimeMillis();
                prefs().edit().putString("profiles", text).putLong("updated", now).apply();
                registry = next;
                fetchedAt = now;
            }
            GhostBadges.notifyChanged();
            return TTL;
        } catch (Exception error) {
            android.util.Log.w("VeylExteraSupporters", "Profiles sync failed: " + error.getClass().getSimpleName());
            return GhostBadgeSyncJob.RETRY_MS;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
    private GhostExteraSupporters(){}
}
