package org.telegram.messenger;

import android.content.SharedPreferences;
import org.json.*;
import org.telegram.tgnet.*;
import org.telegram.ui.ActionBar.BaseFragment;
import java.util.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import javax.net.ssl.HttpsURLConnection;

/** Always-on project badges authenticated by the project's RSA key. */
public final class GhostBadges {
    private static volatile GhostBadgeRegistry registry;
    private static volatile long validUntil, nextCheck;
    private static final DispatchQueue network = new DispatchQueue("veylBadgeSync");
    private static final java.util.concurrent.atomic.AtomicBoolean loading = new java.util.concurrent.atomic.AtomicBoolean();
    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("ghost_project_badges_v1", 0);
    }
    public static GhostBadgeRegistry registry() {
        if (registry == null) synchronized (GhostBadges.class) {
            if (registry == null) {
                try { String saved=prefs().getString("signed_snapshot", "");registry=saved.isEmpty()?new GhostBadgeRegistry(Collections.emptyMap()):authenticate(saved); }
                catch (Exception e) { FileLog.e(e); registry = new GhostBadgeRegistry(Collections.emptyMap()); }
            }
        }
        return registry;
    }
    /** Full replacement reflects revocations too. Invalid responses preserve the working cache. */
    public static GhostBadgeRegistry parseSnapshot(String text) throws JSONException {
        if (text == null || text.length() > 2 * 1024 * 1024) throw new JSONException("Badge response too large");
        JSONObject root = new JSONObject(text);
        if (root.getInt("version") != 1) throw new JSONException("Unsupported badge schema");
        JSONArray entries = root.getJSONArray("badges");
        if (entries.length() > 20000) throw new JSONException("Too many badges");
        Map<Long, GhostBadgeRegistry.Type> users = new HashMap<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            String rawId = entry.getString("user_id");
            if (!rawId.matches("[1-9][0-9]{0,18}")) throw new JSONException("Invalid badge user ID");
            long id;
            try { id = Long.parseLong(rawId); } catch (NumberFormatException e) { throw new JSONException("Invalid badge user ID"); }
            String role = entry.getString("type");
            GhostBadgeRegistry.Type type;
            if ("developer".equals(role) || "creator".equals(role)) type = GhostBadgeRegistry.Type.DEVELOPER;
            else if ("supporter".equals(role)) type = GhostBadgeRegistry.Type.SUPPORTER;
            else throw new JSONException("Unsupported badge type");
            if (users.put(id, type) != null) throw new JSONException("Duplicate badge user ID");
        }
        return new GhostBadgeRegistry(users);
    }
    private static GhostBadgeRegistry authenticate(String text) throws Exception {
        if(text.length()>3*1024*1024)throw new SecurityException("Badge envelope too large");
        JSONObject envelope=new JSONObject(text);
        byte[] raw=android.util.Base64.decode(envelope.getString("payload"),android.util.Base64.URL_SAFE|android.util.Base64.NO_WRAP);
        byte[] key;
        try(InputStream input=ApplicationLoader.applicationContext.getAssets().open("license/lease-public.der");ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[2048];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);key=output.toByteArray();
        }
        Signature signature=Signature.getInstance("SHA256withRSA");
        signature.initVerify(KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(key)));signature.update(raw);
        if(!signature.verify(android.util.Base64.decode(envelope.getString("signature"),android.util.Base64.URL_SAFE|android.util.Base64.NO_WRAP)))throw new SecurityException("Invalid badge signature");
        String payload=new String(raw,StandardCharsets.UTF_8);JSONObject data=new JSONObject(payload);
        long now=System.currentTimeMillis()/1000,issued=data.getLong("issued_at"),until=data.getLong("until"),revision=data.getLong("revision");
        if(!"badges".equals(data.getString("kind"))||!ApplicationLoader.applicationContext.getPackageName().equals(data.getString("app"))||until<=now||issued>now+300||until-issued<=0||until-issued>86400||revision<prefs().getLong("revision",0))throw new SecurityException("Expired or stale badge response");
        GhostBadgeRegistry next=parseSnapshot(payload);validUntil=until*1000;
        return next;
    }
    private static synchronized void applySignedSnapshot(String text) throws Exception {
        GhostBadgeRegistry next=authenticate(text);
        JSONObject envelope=new JSONObject(text);
        JSONObject data=new JSONObject(new String(android.util.Base64.decode(envelope.getString("payload"),android.util.Base64.URL_SAFE|android.util.Base64.NO_WRAP),StandardCharsets.UTF_8));
        prefs().edit().putString("signed_snapshot",text).putLong("revision",data.getLong("revision")).apply();registry=next;
        android.util.Log.i("VeylBadges","Signed snapshot accepted; badges="+data.getJSONArray("badges").length()+", revision="+data.getLong("revision"));
        notifyChanged();
    }
    public static void notifyChanged(){
        AndroidUtilities.runOnUIThread(() -> {
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                if (UserConfig.getInstance(account).isClientActivated()) {
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
                }
            }
        });
    }
    public static void refresh() {
        long now=android.os.SystemClock.elapsedRealtime();
        if(ApplicationLoader.applicationContext==null||now<nextCheck||!loading.compareAndSet(false,true))return;
        nextCheck=now+30000;
        network.postRunnable(()->{
            HttpsURLConnection connection=null;
            try {
                connection=(HttpsURLConnection)new URL(GhostServiceEndpoints.VERIFICATION+"/v1/badges").openConnection(Proxy.NO_PROXY);
                connection.setConnectTimeout(10000);connection.setReadTimeout(15000);connection.setInstanceFollowRedirects(false);
                if(connection.getResponseCode()!=200)throw new IOException("Badge service unavailable");
                try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){
                    byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){if(output.size()+n>3*1024*1024)throw new IOException("Badge response too large");output.write(buffer,0,n);}
                    applySignedSnapshot(output.toString("UTF-8"));
                }
                nextCheck=android.os.SystemClock.elapsedRealtime()+5*60000;
            }catch(Exception error){android.util.Log.w("VeylBadges","Verification sync failed: "+error.getClass().getSimpleName()+": "+error.getMessage());FileLog.e(error);}finally{if(connection!=null)connection.disconnect();loading.set(false);}
        });
    }
    public static GhostBadgeRegistry.Badge badge(int account, TLObject peer) {
        refresh();
        if (!(peer instanceof TLRPC.User)) return null;
        TLRPC.User user = (TLRPC.User) peer;
        GhostBadgeRegistry data=registry();
        if(user.scam||user.fake)return null;
        GhostBadgeRegistry.Badge local=System.currentTimeMillis()<validUntil?data.user(user.id):null;
        return local!=null?local:GhostExteraSupporters.badge(user);
    }
    public static long documentId(int account, TLObject peer) {
        GhostBadgeRegistry.Badge badge = badge(account, peer); return badge == null ? 0 : badge.documentId;
    }
    public static String description(GhostBadgeRegistry.Badge badge) {
        if(badge==null)return "";
        if(badge.type==GhostBadgeRegistry.Type.EXTERA_SUPPORTER)return "Поддержал exteraGram";
        return badge.type == GhostBadgeRegistry.Type.DEVELOPER ? "Создатель AstraGram" : "Поддержал AstraGram";
    }
    public static void showInfo(BaseFragment fragment, TLObject peer) {
        GhostBadgeRegistry.Badge badge = badge(fragment.getCurrentAccount(), peer);
        if (badge != null && fragment.getParentActivity() != null)
            fragment.showDialog(new org.telegram.ui.Components.GhostBadgeSheet(fragment, badge));
    }
    private GhostBadges() {}
}
