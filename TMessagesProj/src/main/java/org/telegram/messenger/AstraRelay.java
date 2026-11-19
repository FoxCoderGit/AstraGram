package org.telegram.messenger;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import androidx.annotation.Keep;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.HttpsURLConnection;

/** Private application transport policy. No UI, account credentials or device-wide interception. */
@Keep
public final class AstraRelay {
    private static final long DAY=86400000L,HOUR=3600000L;
    private static final String CONTROL="https://verification.codebyfox.ru:9446";
    private static final DispatchQueue queue=new DispatchQueue("AstraRelayPolicy");
    private static final AtomicInteger denialSequence=new AtomicInteger();
    private static Context context;
    private static SharedPreferences prefs;
    private static PublicKey policyKey;
    private static volatile boolean initialized;
    private static volatile long serverOffsetMs;
    private static int permission;
    private static long accessDeadline,tokenDeadline,retryDeadline,deniedDeadline;
    private static JSONObject endpoint;
    private static String token="";
    private static boolean busy;
    private static long accessFailureSince=-1;
    private static long lastSuccessfulRefresh=-10000;
    private static final Runnable wakeTask=()->wake(null);

    public static synchronized void initialize(Context application) {
        if(initialized)return;
        context=application.getApplicationContext();prefs=context.getSharedPreferences("astra_relay",Context.MODE_PRIVATE);
        initialized=true;
        // Install the native gate before Telegram starts any connection.
        nativeConfigure(0,0,0,"","",0,"","","",networkAvailable());
        queue.postRunnable(()->{
            try{
                try(InputStream stream=context.getAssets().open("license/lease-public.der")){
                    policyKey=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(read(stream,8192)));
                }
                restore();
                applyNative();
            }catch(Throwable ignored){permission=0;}
            refresh(false);
        });
        try{
            ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            manager.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback(){
                @Override public void onAvailable(Network network){networkChanged();}
                @Override public void onLost(Network network){networkChanged();}
                @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities capabilities){networkChanged();}
            });
        }catch(Throwable ignored){}
    }

    private static void networkChanged(){
        if(!initialized)return;
        queue.postRunnable(()->{applyNative();if(networkAvailable())refresh(false);});
    }

    public static void onLicenseChanged(){if(initialized)queue.postRunnable(()->refresh(true));}

    public static void wake(Runnable finished){
        if(!initialized){if(finished!=null)finished.run();return;}
        queue.postRunnable(()->{try{applyNative();refresh(false);}finally{if(finished!=null)finished.run();}});
    }

    private static boolean networkAvailable(){
        try{ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);android.net.NetworkInfo info=manager.getActiveNetworkInfo();return info!=null&&info.isConnected();}catch(Throwable ignored){return false;}
    }
    private static int boot(){return Settings.Global.getInt(context.getContentResolver(),Settings.Global.BOOT_COUNT,-1);}
    private static long remaining(String key){
        long elapsed=SystemClock.elapsedRealtime();
        int currentBoot=boot();
        if(currentBoot>=0&&prefs.getInt("clock_boot",-2)==currentBoot)return Math.max(0,Math.min(DAY,prefs.getLong(key+"_elapsed",0)-elapsed));
        return Math.max(0,Math.min(DAY,prefs.getLong(key+"_wall",0)-System.currentTimeMillis()));
    }
    private static void saveDeadlines(){
        long elapsed=SystemClock.elapsedRealtime(),wall=System.currentTimeMillis();
        SharedPreferences.Editor editor=prefs.edit().putInt("clock_boot",boot());
        for(Object[] item:new Object[][]{{"access",accessDeadline},{"token",tokenDeadline},{"retry",retryDeadline},{"denied",deniedDeadline}}){
            String key=(String)item[0];long deadline=(Long)item[1];
            editor.putLong(key+"_elapsed",deadline).putLong(key+"_wall",wall+Math.max(0,deadline-elapsed));
        }
        editor.apply();
    }
    private static void restore()throws Exception{
        long now=SystemClock.elapsedRealtime();
        deniedDeadline=now+remaining("denied");retryDeadline=now+Math.min(HOUR,remaining("retry"));
        String saved=prefs.getString("grant","");
        if(!saved.isEmpty()){
            JSONObject grant=verify(new JSONObject(saved),null);
            long accessLeft=remaining("access"),tokenLeft=remaining("token");
            if(accessLeft>0){
                permission=grant.getBoolean("allowed")?1:2;accessDeadline=now+accessLeft;
                if(permission==1&&tokenLeft>0){endpoint=validateEndpoint(grant.getJSONObject("endpoint"));token=grant.getString("token");tokenDeadline=now+tokenLeft;}
                else if(permission==1)permission=0;
                serverOffsetMs=grant.getLong("issued_at")*1000L+Math.max(0,(grant.getLong("until")-grant.getLong("issued_at"))*1000L-accessLeft)-now;
            }
        }
        if(deniedDeadline>now){permission=2;accessDeadline=deniedDeadline;token="";}
    }

    private static void refresh(boolean force){
        long now=SystemClock.elapsedRealtime();
        if(busy||!networkAvailable()){schedule();return;}
        if(deniedDeadline>now){permission=2;applyNative();schedule();return;}
        if(!force&&retryDeadline>now){schedule();return;}
        if(!force&&permission==1&&Math.min(accessDeadline,tokenDeadline)-now>60000){schedule();return;}
        if(!GhostLicenseBridge.allowed()||policyKey==null){scheduleAfter(60000);return;}
        busy=true;int sequence=denialSequence.get();
        try{
            String key=GhostLicenseBridge.relayDeviceKey();if(key==null)throw new SecurityException("Device key unavailable");
            JSONObject challenge=post("/v1/challenge",new JSONObject().put("public_key",key));
            String nonce=challenge.getString("nonce");if(!nonce.matches("[0-9a-f]{64}"))throw new SecurityException("Invalid challenge");
            String proof=GhostLicenseBridge.relayPolicyProof(nonce);if(proof==null)throw new SecurityException("Device proof unavailable");
            JSONObject response=post("/v1/access",new JSONObject(proof));
            JSONObject grant=verify(response,nonce);
            if(sequence!=denialSequence.get())return;
            now=SystemClock.elapsedRealtime();
            serverOffsetMs=grant.getLong("issued_at")*1000L-now;
            accessDeadline=now+(grant.getLong("until")-grant.getLong("issued_at"))*1000L;
            permission=grant.getBoolean("allowed")?1:2;
            if(permission==1){
                endpoint=validateEndpoint(grant.getJSONObject("endpoint"));token=grant.getString("token");
                if(token.length()>4096||!token.matches("[A-Za-z0-9_.-]+"))throw new SecurityException("Invalid token");
                long duration=(grant.getLong("token_until")-grant.getLong("issued_at"))*1000L;
                if(duration<=0||duration>DAY)throw new SecurityException("Invalid token lifetime");
                tokenDeadline=now+duration;deniedDeadline=0;
            }else{
                token="";endpoint=null;deniedDeadline=accessDeadline;retryDeadline=0;
            }
            prefs.edit().putString("grant",response.toString()).putLong("revision",grant.getLong("revision")).apply();
            accessFailureSince=-1;
            lastSuccessfulRefresh=now;
            saveDeadlines();applyNative();
        }catch(Throwable ignored){
            // A network/TLS/authorization error is never an administrative denial.
            // tgnet owns the initial 30-second recovery interval.
            if(accessDeadline<=SystemClock.elapsedRealtime()||tokenDeadline<=SystemClock.elapsedRealtime())permission=0;
            applyNative();
            long failedAt=SystemClock.elapsedRealtime();
            if(accessFailureSince<0)accessFailureSince=failedAt;
            if(failedAt-accessFailureSince>=30000){retryDeadline=failedAt+HOUR;accessFailureSince=-1;saveDeadlines();applyNative();}
            scheduleAfter(retryDeadline>failedAt?retryDeadline-failedAt:Math.max(1000,Math.min(5000,30000-(failedAt-accessFailureSince))));
            return;
        }finally{busy=false;}
        schedule();
    }

    private static JSONObject verify(JSONObject envelope,String nonce)throws Exception{
        String payloadText=envelope.getString("payload"),signatureText=envelope.getString("signature");
        if(payloadText.length()>16384||signatureText.length()>2048||!payloadText.matches("[A-Za-z0-9_-]+")||!signatureText.matches("[A-Za-z0-9_-]+"))throw new SecurityException("Invalid policy encoding");
        byte[] payload=decode(payloadText);
        Signature signature=Signature.getInstance("SHA256withRSA");signature.initVerify(policyKey);signature.update(payload);
        if(!signature.verify(decode(signatureText)))throw new SecurityException("Invalid policy signature");
        JSONObject value=new JSONObject(new String(payload,StandardCharsets.UTF_8));
        String key=GhostLicenseBridge.relayDeviceKey();if(key==null)throw new SecurityException("Identity unavailable");
        String device=hex(MessageDigest.getInstance("SHA-256").digest(decode(key)));
        long issued=value.getLong("issued_at"),until=value.getLong("until"),revision=value.getLong("revision");
        if(value.getInt("v")!=1||!"astrarelay".equals(value.getString("kind"))||!context.getPackageName().equals(value.getString("app"))
            ||!device.equals(value.getString("device"))||issued<=0||until<=issued||until-issued>86400||revision<prefs.getLong("revision",0)
            ||nonce!=null&&!nonce.equals(value.getString("nonce")))throw new SecurityException("Policy binding or lifetime invalid");
        value.getBoolean("allowed");return value;
    }
    private static JSONObject validateEndpoint(JSONObject value)throws Exception{
        String host=value.getString("host"),path=value.getString("path");int port=value.getInt("port");
        if(!host.matches("(?i)[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?")||!"/v1/tunnel".equals(path)||port<1||port>65535)throw new SecurityException("Invalid endpoint");
        JSONArray ips=value.getJSONArray("ips"),pins=value.getJSONArray("pins");
        if(ips.length()<1||ips.length()>8||pins.length()<1||pins.length()>4)throw new SecurityException("Invalid endpoint limits");
        for(int i=0;i<ips.length();i++){
            String ip=ips.getString(i);if(ip.length()>64||!ip.matches("[0-9a-fA-F:.]+"))throw new SecurityException("IP literal required");
            InetAddress address=InetAddress.getByName(ip);
            if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress())throw new SecurityException("Public endpoint required");
        }
        for(int i=0;i<pins.length();i++)if(!pins.getString(i).matches("[0-9a-f]{64}"))throw new SecurityException("Invalid pin");
        return value;
    }
    private static JSONObject post(String path,JSONObject value)throws Exception{
        HttpsURLConnection connection=(HttpsURLConnection)new URL(CONTROL+path).openConnection();
        connection.setConnectTimeout(8000);connection.setReadTimeout(8000);connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("POST");connection.setRequestProperty("Content-Type","application/json");connection.setDoOutput(true);
        byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(bytes.length);
        try{
            try(OutputStream stream=connection.getOutputStream()){stream.write(bytes);}
            if(connection.getResponseCode()!=200)throw new java.io.IOException("Relay control unavailable");
            try(InputStream stream=connection.getInputStream()){return new JSONObject(new String(read(stream,32768),StandardCharsets.UTF_8));}
        }finally{connection.disconnect();}
    }
    private static byte[] read(InputStream stream,int limit)throws Exception{
        ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] block=new byte[2048];int count;
        while((count=stream.read(block))!=-1){if(output.size()+count>limit)throw new java.io.IOException("Response limit");output.write(block,0,count);}return output.toByteArray();
    }
    private static byte[] decode(String text){return Base64.decode(text,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);}
    private static String hex(byte[] bytes){StringBuilder result=new StringBuilder();for(byte value:bytes)result.append(String.format(java.util.Locale.US,"%02x",value&255));return result.toString();}
    private static String join(JSONArray values)throws Exception{ArrayList<String> result=new ArrayList<>();for(int i=0;i<values.length();i++)result.add(values.getString(i));return android.text.TextUtils.join(",",result);}

    private static void applyNative(){
        try{
            long now=SystemClock.elapsedRealtime();int state=permission;
            long duration=state==2?deniedDeadline-now:Math.min(accessDeadline,tokenDeadline)-now;
            if(duration<=0){state=0;duration=0;}
            nativeConfigure(state,duration,Math.max(0,retryDeadline-now),endpoint==null?"":endpoint.getString("host"),endpoint==null?"":join(endpoint.getJSONArray("ips")),
                endpoint==null?0:endpoint.getInt("port"),endpoint==null?"":endpoint.getString("path"),endpoint==null?"":join(endpoint.getJSONArray("pins")),token,networkAvailable());
        }catch(Throwable ignored){}
    }
    private static void schedule(){
        long now=SystemClock.elapsedRealtime(),delay;
        if(deniedDeadline>now)delay=deniedDeadline-now;
        else if(retryDeadline>now)delay=retryDeadline-now;
        else if(permission==1&&Math.min(accessDeadline,tokenDeadline)>now)delay=Math.max(10000,Math.min(accessDeadline,tokenDeadline)-now-30000);
        else delay=30000;
        scheduleAfter(delay);
    }
    private static void scheduleAfter(long delay){
        delay=Math.max(1000,Math.min(DAY,delay));queue.cancelRunnable(wakeTask);queue.postRunnable(wakeTask,delay);
        try{
            JobScheduler scheduler=(JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            scheduler.schedule(new JobInfo.Builder(47042,new ComponentName(context,AstraRelayJob.class)).setMinimumLatency(delay).setOverrideDeadline(delay+HOUR)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build());
        }catch(Throwable ignored){}
    }

    @Keep
    public static String nativeConnectionHeaders(String token,String ip,int port){
        try{
            long time=serverOffsetMs!=0?(serverOffsetMs+SystemClock.elapsedRealtime())/1000L:System.currentTimeMillis()/1000L;
            return GhostLicenseBridge.relayConnectionProof(token,ip,port,time);
        }catch(Throwable ignored){return "";}
    }
    @Keep
    public static void onNativeRelayEvent(int type,long delay){
        if(!initialized)return;
        if(type==2)denialSequence.incrementAndGet();
        queue.postRunnable(()->{
            long now=SystemClock.elapsedRealtime();
            if(type==2){permission=2;deniedDeadline=now+DAY;accessDeadline=deniedDeadline;retryDeadline=0;token="";saveDeadlines();applyNative();schedule();}
            else if(type==1){retryDeadline=now+Math.max(1,Math.min(HOUR,delay));saveDeadlines();schedule();}
            else if(type==3){retryDeadline=0;prefs.edit().remove("retry_elapsed").remove("retry_wall").apply();schedule();}
            else if(type==0&&now-lastSuccessfulRefresh>=5000)refresh(true);
        });
    }
    private static native void nativeConfigure(int permission,long validMs,long retryMs,String host,String ips,int port,String path,String pins,String token,boolean network);
    private AstraRelay(){}
}
