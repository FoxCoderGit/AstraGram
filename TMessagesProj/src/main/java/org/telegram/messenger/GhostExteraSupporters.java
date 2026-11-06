package org.telegram.messenger;

import android.content.SharedPreferences;
import org.telegram.tgnet.TLRPC;
import java.io.*;
import java.net.URL;
import java.util.concurrent.atomic.*;
import javax.net.ssl.HttpsURLConnection;

/** Optional third-party data; kept separate from the AstraGram signed registry. */
public final class GhostExteraSupporters {
    private static final String ENDPOINT="https://api.exteragram.app/api/v1/profiles";
    private static final long TTL=24*60*60*1000L;
    private static final DispatchQueue queue=new DispatchQueue("exteraSupporterSync");
    private static final AtomicBoolean loading=new AtomicBoolean();
    private static final AtomicInteger generation=new AtomicInteger();
    private static volatile GhostExteraSupporterRegistry registry;
    private static volatile long fetchedAt,nextCheck;
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
        GhostExteraSupporterRegistry data=registry();refresh();
        long age=System.currentTimeMillis()-fetchedAt;if(age<0||age>=TTL)return null;
        Long document=data.documents.get(user.id);
        return document==null?null:new GhostBadgeRegistry.Badge(GhostBadgeRegistry.Type.EXTERA_SUPPORTER,document);
    }
    public static void onPreferenceChanged(boolean enabled){
        generation.incrementAndGet();loading.set(false);nextCheck=0;
        if(enabled)refresh();GhostBadges.notifyChanged();
    }
    public static void refresh(){
        if(!GhostOptionalFeatures.enabled(GhostOptionalFeatures.EXTERA_SUPPORTERS)||android.os.SystemClock.elapsedRealtime()<nextCheck||!loading.compareAndSet(false,true))return;
        int token=generation.get();nextCheck=android.os.SystemClock.elapsedRealtime()+60000;
        queue.postRunnable(()->{
            HttpsURLConnection connection=null;
            try{
                connection=(HttpsURLConnection)new URL(ENDPOINT).openConnection();
                connection.setConnectTimeout(10000);connection.setReadTimeout(20000);connection.setInstanceFollowRedirects(false);
                if(connection.getResponseCode()!=200)throw new IOException("Profiles service unavailable");
                String text;
                try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){
                    byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1){if(output.size()+count>4*1024*1024)throw new IOException("Profiles response too large");output.write(buffer,0,count);}text=output.toString("UTF-8");
                }
                GhostExteraSupporterRegistry next=GhostExteraSupporterRegistry.parse(text);
                synchronized(GhostExteraSupporters.class){
                    if(token!=generation.get()||!GhostOptionalFeatures.enabled(GhostOptionalFeatures.EXTERA_SUPPORTERS))return;
                    long now=System.currentTimeMillis();
                    prefs().edit().putString("profiles",text).putLong("updated",now).apply();registry=next;fetchedAt=now;
                    nextCheck=android.os.SystemClock.elapsedRealtime()+6*60*60*1000L;
                }
                android.util.Log.i("VeylExteraSupporters","Supporter snapshot accepted; users="+next.documents.size());
                GhostBadges.notifyChanged();
            }catch(Exception error){android.util.Log.w("VeylExteraSupporters","Profiles sync failed: "+error.getClass().getSimpleName());}
            finally{if(connection!=null)connection.disconnect();if(token==generation.get())loading.set(false);}
        });
    }
    private GhostExteraSupporters(){}
}
