package org.ghostgram.messenger.license;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.telegram.messenger.*;
import org.telegram.tgnet.*;

/** Synthetic gate/signature/expiry tests, removed from the distributable APK. */
public class LicenseDiagnostics extends Instrumentation {
    private String mode;
    @Override public void onCreate(Bundle args) { super.onCreate(args); mode = args.getString("mode", "security"); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            long end = android.os.SystemClock.elapsedRealtime() + 15000;
            while (!LicenseManager.ready && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(20);
            if (!LicenseManager.ready) throw new AssertionError("Not initialized");
            if (mode.equals("preview")) {
                if (LicenseManager.allowed()) throw new AssertionError("Preview requires locked state");
                LicenseActivity activity = (LicenseActivity) startActivitySync(new Intent(getTargetContext(), LicenseActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                waitForIdleSync();
                runOnMainSync(() -> {
                    android.view.View root = activity.getWindow().getDecorView();
                    Bitmap image = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
                    root.draw(new Canvas(image));
                    try (FileOutputStream out = new FileOutputStream(new File(getTargetContext().getCacheDir(), "license-preview.png"))) {
                        image.compress(Bitmap.CompressFormat.PNG, 100, out);
                    } catch (Exception error) { throw new RuntimeException(error); }
                    finally { image.recycle(); }
                });
                result.putString("result", "PASS: native activation view rendered without other application windows");
            } else if (mode.equals("revoked")) {
                renew(false);
                if (!LicenseManager.error.equals("revoked") || !LicenseManager.licenseId.isEmpty()) throw new AssertionError("Revocation not applied");
                result.putString("result", "PASS: actual server revocation clears access and requires a new key");
            } else {
                if (!LicenseManager.allowed()) throw new AssertionError("Activate first");
                JSONObject saved = new JSONObject(getTargetContext().getSharedPreferences("ghost_license", 0).getString("lease", ""));
                Method verify = LicenseManager.class.getDeclaredMethod("verify", JSONObject.class, String.class); verify.setAccessible(true);
                verify.invoke(null, saved, null);
                JSONObject forged = new JSONObject(saved.toString());
                String payload = forged.getString("payload");
                forged.put("payload", (payload.startsWith("A") ? "B" : "A") + payload.substring(1));
                try { verify.invoke(null, forged, null); throw new AssertionError("Accepted forged lease"); }
                catch (InvocationTargetException expected) { if (!(expected.getCause() instanceof SecurityException)) throw expected; }
                try { verify.invoke(null, saved, "wrong-nonce"); throw new AssertionError("Accepted wrong nonce"); }
                catch (InvocationTargetException expected) { if (!(expected.getCause() instanceof SecurityException)) throw expected; }
                Field deadline = LicenseManager.class.getDeclaredField("deadline"); deadline.setAccessible(true); deadline.setLong(null, 0);
                if (LicenseManager.allowed() || GhostLicenseBridge.allowed()) throw new AssertionError("Expired lease allowed");
                ApplicationLoader.postInitApplication();
                CountDownLatch denied = new CountDownLatch(1);
                final String[] error = {null};
                ConnectionsManager.getInstance(UserConfig.selectedAccount).sendRequest(new TLRPC.TL_help_getConfig(), (response, failure) -> {
                    error[0] = failure == null ? "unexpected_success" : failure.text; denied.countDown();
                });
                if (!denied.await(5, TimeUnit.SECONDS) || !"GHOST_LICENSE_REQUIRED".equals(error[0])) throw new AssertionError("Transport gate failed");
                renew(true);
                if (!LicenseManager.allowed()) throw new AssertionError("Renewal did not unlock");
                result.putString("result", "PASS: pinned server renewal on Android, signed lease, tamper rejection, nonce binding, expiry, RPC blocked before native transport");
            }
            finish(-1, result);
        } catch (Throwable failure) { result.putString("result", android.util.Log.getStackTraceString(failure)); finish(0, result); }
    }
    private void renew(boolean expected) throws Exception {
        long end = android.os.SystemClock.elapsedRealtime() + 30000;
        while (LicenseManager.busy && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(20);
        CountDownLatch latch = new CountDownLatch(1); final boolean[] ok = {false};
        LicenseManager.refresh(null, (allowed, error) -> { ok[0] = allowed; latch.countDown(); });
        if (!latch.await(30, TimeUnit.SECONDS) || ok[0] != expected) throw new AssertionError("Renewal outcome: " + LicenseManager.error);
    }
}
