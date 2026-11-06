package org.ghostgram.messenger.license;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.os.*;
import android.provider.Settings;
import android.security.keystore.*;
import android.util.Base64;
import org.json.JSONObject;
import org.telegram.messenger.*;
import javax.net.ssl.*;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import java.security.spec.*;

public final class LicenseManager {
    private static final String ALIAS = "ghostgram.license.device.v1";
    private static final long DAY = 86400000L;
    private static final DispatchQueue queue = new DispatchQueue("ghostLicense");
    private static final Handler handler = new Handler(Looper.getMainLooper());
    static Context context;
    private static android.content.SharedPreferences prefs;
    static KeyPair device;
    static PublicKey serverKey;
    static SSLSocketFactory tls;
    private static volatile long deadline;
    private static volatile long lastWall;
    public static volatile boolean ready, busy;
    public static volatile String error = "", licenseId = "";
    private static boolean pluginsStarted;
    private static Activity foreground;
    private static long nextAttempt;
    private static long retryDelay = 60000;
    public interface Callback { void done(boolean allowed, String error); }

    public static synchronized void initialize(Application app) {
        if (context != null) return;
        context = app.getApplicationContext(); prefs = context.getSharedPreferences("ghost_license", Context.MODE_PRIVATE);
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle state) { if (!(a instanceof LicenseActivity)) require(a); }
            public void onActivityResumed(Activity a) {
                foreground = a; if (!(a instanceof LicenseActivity)) require(a);
                handler.postDelayed(()->{
                    if(foreground!=a||a instanceof LicenseActivity)return;
                    if(a.getIntent()!=null&&a.getIntent().getBooleanExtra("ghost_open_updates",false)){
                        a.getIntent().removeExtra("ghost_open_updates");OwnerControl.checkUpdate(a);
                    }else OwnerControl.maybeNotify(a);
                },500);
            }
            public void onActivityPaused(Activity a) { if (foreground == a) foreground = null; if (ready) prefs.edit().putLong("last_wall", lastWall).apply(); }
            public void onActivityStarted(Activity a) {}
            public void onActivityStopped(Activity a) {}
            public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            public void onActivityDestroyed(Activity a) {}
        });
        queue.postRunnable(() -> {
            try {
                KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
                if (!store.containsAlias(ALIAS)) {
                    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore");
                    generator.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                            .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                            .setDigests(KeyProperties.DIGEST_SHA256).build());
                    generator.generateKeyPair();
                }
                device = new KeyPair(store.getCertificate(ALIAS).getPublicKey(), (PrivateKey) store.getKey(ALIAS, null));
                serverKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(asset("license/lease-public.der")));
                X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(asset("license/server.crt")));
                KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType()); trust.load(null); trust.setCertificateEntry("ghostServer", cert);
                TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); factory.init(trust);
                SSLContext ssl = SSLContext.getInstance("TLS"); ssl.init(null, factory.getTrustManagers(), null); tls = ssl.getSocketFactory();
                String saved = prefs.getString("lease", "");
                if (!saved.isEmpty()) {
                    JSONObject claims = verify(new JSONObject(saved), null);
                    licenseId = claims.getString("license_id");
                    long elapsed = SystemClock.elapsedRealtime(), wall = System.currentTimeMillis();
                    long savedElapsed = prefs.getLong("elapsed", -1), savedWall = prefs.getLong("wall", -1);
                    long age = boot() == prefs.getInt("boot", -1) && savedElapsed >= 0 && elapsed >= savedElapsed ? elapsed - savedElapsed : wall - savedWall;
                    lastWall = Math.max(savedWall, prefs.getLong("last_wall", savedWall));
                    long remaining = (claims.getLong("until") - claims.getLong("issued_at")) * 1000L - age;
                    if (age >= 0 && remaining > 0 && remaining <= DAY && wall >= lastWall - 60000) deadline = elapsed + remaining;
                }
            } catch (Throwable problem) { deadline = 0; error = "device_error"; }
            if (tls != null && serverKey != null && device != null) OwnerControl.initialize();
            ready = true;
            if (allowed()) authorized();
            scheduleJob();
            handler.post(() -> { if (!allowed()) closeAccess(); scheduleDeadline(); tick.run(); });
        });
    }

    private static final Runnable tick = new Runnable() {
        @Override public void run() {
            if (ready) OwnerControl.sync(false);
            if (ready && !allowed() && !licenseId.isEmpty() && !busy && SystemClock.elapsedRealtime() >= nextAttempt) refresh(null, null);
            if (ready && !allowed()) {
                Activity activity = foreground;
                if (activity != null && !activity.isFinishing()) require(activity);
            }
            handler.postDelayed(this, 30000);
        }
    };

    private static final Runnable expiration = () -> {
        if (!allowed()) {
            closeAccess();
            if (!licenseId.isEmpty() && !busy) refresh(null, null);
        }
    };
    private static final Runnable renewal = () -> { if (!OwnerControl.bypass() && !licenseId.isEmpty() && !busy) refresh(null, null); };
    private static void scheduleDeadline() {
        handler.removeCallbacks(expiration);
        handler.removeCallbacks(renewal);
        if (allowed()) {
            long remaining = Math.max(deadline - SystemClock.elapsedRealtime(), OwnerControl.remaining());
            handler.postDelayed(expiration, Math.max(1, remaining));
            // Renew quietly just before the old lease expires, keeping the hard 24h deadline.
            // Very short leases mean the key itself is expiring: avoid a renewal loop.
            if (remaining > 120000) handler.postDelayed(renewal, remaining - 60000);
        }
    }
    private static void closeAccess() {
        if (pluginsStarted) {
            MediaController player = MediaController.getInstance();
            MessageObject playing = player.getPlayingMessageObject();
            if (playing != null) player.pauseMessage(playing, false);
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) GhostFloatingChats.close(a);
        android.app.NotificationManager notifications = context.getSystemService(android.app.NotificationManager.class);
        if (notifications != null) notifications.cancelAll();
        Activity activity = foreground;
        if (activity != null && !(activity instanceof LicenseActivity) && !activity.isFinishing()) require(activity);
    }

    public static boolean allowed() {
        if (ready && OwnerControl.bypass()) return true;
        long wall = System.currentTimeMillis();
        if (!ready || deadline <= SystemClock.elapsedRealtime() || wall < lastWall - 60000) return false;
        lastWall = Math.max(lastWall, wall);
        return true;
    }

    public static boolean require(Activity activity) {
        if (activity instanceof LicenseActivity || activity.isFinishing()) return activity instanceof LicenseActivity;
        if (allowed()) return true;
        activity.startActivity(new Intent(activity, LicenseActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        activity.finish();
        return false;
    }

    public static synchronized void refresh(String activationKey, Callback callback) {
        if (!ready || busy) { if (callback != null) handler.post(() -> callback.done(allowed(), "busy")); return; }
        if (OwnerControl.bypass()) { if(callback!=null)handler.post(()->callback.done(true,""));return; }
        if (device == null || tls == null || serverKey == null) { if (callback != null) handler.post(() -> callback.done(false, "device_error")); return; }
        if (activationKey == null && licenseId.isEmpty()) { if (callback != null) handler.post(() -> callback.done(false, "key_required")); return; }
        busy = true;
        queue.postRunnable(() -> {
            try {
                String purpose = activationKey == null ? "validate" : "activate";
                String value = activationKey == null ? licenseId : activationKey.trim().toUpperCase(java.util.Locale.US);
                String publicKey = b64(device.getPublic().getEncoded());
                JSONObject challenge = post("/v1/challenge", new JSONObject().put("public_key", publicKey).put("purpose", purpose));
                String nonce = challenge.getString("nonce");
                Signature proof = Signature.getInstance("SHA256withECDSA"); proof.initSign(device.getPrivate());
                proof.update(("ghostgram-v1\n" + purpose + "\n" + nonce + "\n" + value).getBytes(StandardCharsets.UTF_8));
                JSONObject response = post("/v1/" + purpose, new JSONObject().put("public_key", publicKey).put("nonce", nonce).put("value", value).put("signature", b64(proof.sign())));
                JSONObject claims = verify(response, nonce);
                if (activationKey == null && !value.equals(claims.getString("license_id"))) throw new SecurityException("Wrong license");
                long lifetime = (claims.getLong("until") - claims.getLong("issued_at")) * 1000L;
                if (lifetime <= 0 || lifetime > DAY) throw new SecurityException("Invalid lease lifetime");
                licenseId = claims.getString("license_id");
                long elapsed = SystemClock.elapsedRealtime(), wall = System.currentTimeMillis();
                prefs.edit().putString("lease", response.toString()).putLong("elapsed", elapsed).putLong("wall", wall).putLong("last_wall", wall).putInt("boot", boot()).commit();
                deadline = elapsed + lifetime; lastWall = wall; error = "";
                retryDelay = 60000; nextAttempt = 0;
                handler.post(LicenseManager::scheduleDeadline);
                authorized();
            } catch (Rejected rejection) {
                error = rejection.code;
                if (error.equals("invalid_key") || error.equals("revoked") || error.equals("expired") || error.equals("binding_reset") || error.equals("device_bound")) {
                    deadline = 0; licenseId = ""; prefs.edit().clear().commit();
                }
            } catch (Throwable problem) { error = "network"; OwnerControl.recordError("auth_network"); }
            if (!error.isEmpty()) { nextAttempt = SystemClock.elapsedRealtime() + retryDelay; retryDelay = Math.min(900000, retryDelay * 2); }
            busy = false;
            if (!allowed()) handler.post(LicenseManager::closeAccess);
            if (callback != null) handler.post(() -> callback.done(allowed(), error));
        });
    }

    private static void authorized() {
        synchronized (LicenseManager.class) {
            if (!pluginsStarted) {
                pluginsStarted = true;
                Utilities.globalQueue.postRunnable(() -> { PluginBridge.initialize(); PluginBridge.onAppEvent("START"); });
            }
        }
    }

    /** Domain-separated one-use music proof; no private key or activation code leaves the device. */
    public static synchronized String musicRelayProof(String nonce)throws Exception {
        if(!allowed()||device==null||nonce==null||!nonce.matches("[0-9a-f]{64}"))throw new SecurityException("Music access unavailable");
        Signature signature=Signature.getInstance("SHA256withECDSA");signature.initSign(device.getPrivate());
        signature.update(("ghostgram-music-v1\n"+nonce).getBytes(StandardCharsets.UTF_8));
        return new JSONObject().put("nonce",nonce).put("public_key",b64(device.getPublic().getEncoded())).put("proof",b64(signature.sign()))
                .put("lease",OwnerControl.bypass()?OwnerControl.gameLease():new JSONObject(prefs.getString("lease",""))).toString();
    }
    static JSONObject verify(JSONObject lease, String nonce) throws Exception {
        byte[] payload = unb64(lease.getString("payload"));
        Signature signature = Signature.getInstance("SHA256withRSA"); signature.initVerify(serverKey); signature.update(payload);
        if (!signature.verify(unb64(lease.getString("signature")))) throw new SecurityException("Invalid lease signature");
        JSONObject claims = new JSONObject(new String(payload, StandardCharsets.UTF_8));
        if (claims.getInt("v") != 1 || !claims.getString("app").equals(context.getPackageName())
                || !claims.getString("device").equals(hex(MessageDigest.getInstance("SHA-256").digest(device.getPublic().getEncoded())))
                || !claims.getString("license_id").matches("[a-f0-9]{32}")
                || claims.getLong("until") <= claims.getLong("issued_at") || claims.getLong("until") - claims.getLong("issued_at") > 86400
                || nonce != null && !nonce.equals(claims.getString("nonce"))) throw new SecurityException("Wrong lease");
        return claims;
    }

    static JSONObject post(String path, JSONObject body) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(GhostServiceEndpoints.VERIFICATION + path).openConnection();
        connection.setConnectTimeout(12000); connection.setReadTimeout(12000); connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("POST"); connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        try {
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8); connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream stream = connection.getOutputStream()) { stream.write(bytes); }
            int status = connection.getResponseCode();
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (input == null) throw new IOException("Empty response");
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            try (InputStream stream = input) {
                byte[] buffer = new byte[2048]; int count;
                while ((count = stream.read(buffer)) != -1) { if (result.size() + count > 16384) throw new IOException("Large response"); result.write(buffer, 0, count); }
            }
            JSONObject response = new JSONObject(result.toString("UTF-8"));
            if (status != 200) throw new Rejected(response.optString("error", "network"));
            return response;
        } finally { connection.disconnect(); }
    }
    private static final class Rejected extends Exception { final String code; Rejected(String code) { this.code = code; } }
    static void policyChanged() {
        if (!ready) return;
        if (allowed()) authorized(); else closeAccess();
        scheduleDeadline();
        if(foreground!=null)OwnerControl.maybeNotify(foreground);
    }
    public static void showUpdates(Activity activity) { OwnerControl.show(activity); }
    public static void checkUpdates(Activity activity){OwnerControl.checkUpdate(activity);}
    public static String updateInfo(){return OwnerControl.info();}
    public static void updateOption(String name,boolean value){OwnerControl.option(name,value);}
    public static void configureLicenseConnection(HttpsURLConnection connection) {
        String host=connection.getURL().getHost();
        if(("update.codebyfox.ru".equals(host)||"verification.codebyfox.ru".equals(host))&&connection.getURL().getPort()==9443)return;
        if(tls==null||!"93.88.204.2".equals(connection.getURL().getHost())||connection.getURL().getPort()!=9443)throw new SecurityException();
        connection.setSSLSocketFactory(tls);
    }
    public static void recordError(String name) { OwnerControl.recordError(name); }
    private static byte[] asset(String name) throws Exception { try (InputStream in = context.getAssets().open(name)) { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[2048]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); return out.toByteArray(); } }
    private static String b64(byte[] value) { return Base64.encodeToString(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING); }
    private static byte[] unb64(String value) { return Base64.decode(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING); }
    private static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format(java.util.Locale.US, "%02x", b & 255)); return out.toString(); }
    private static int boot() { return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1); }
    private static void scheduleJob() {
        try { context.getSystemService(JobScheduler.class).schedule(new JobInfo.Builder(47040, new ComponentName(context, LicenseJob.class)).setPeriodic(6 * 60 * 60 * 1000L).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build()); } catch (Exception ignored) {}
    }
}
