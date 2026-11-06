package org.ghostgram.messenger.plugins;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import org.telegram.messenger.*;
import java.io.File;
import java.util.Locale;

/** Recognizes known local fixtures; never reads chats or sends messages. */
public class GhostWhisperDiagnostics extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle report = new Bundle();
        File installed = LocalWhisper.getModelFile();
        File backup = new File(installed.getParentFile(), installed.getName() + ".before-bundled-test");
        boolean moved = false;
        int code = 0;
        try {
            runOnMainSync(() -> ApplicationLoader.postInitApplication());
            long readyDeadline = SystemClock.elapsedRealtime() + 15000;
            while (!org.ghostgram.messenger.license.LicenseManager.ready && SystemClock.elapsedRealtime() < readyDeadline) Thread.sleep(20);
            if (!GhostLicenseBridge.allowed()) throw new AssertionError("License not active");
            if (backup.exists()) throw new AssertionError("Previous model backup exists");
            if (installed.exists()) {
                if (!installed.renameTo(backup)) throw new AssertionError("Cannot preserve existing model");
                moved = true;
            }
            if (!LocalWhisper.isAvailable()) throw new AssertionError("Bundled model is not available on a fresh install");
            File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "ghost_media_tests");
            int attempt = 0;
            for (String name : new String[]{"russian.ogg", "russian.ogg", "russian.mp4"}) {
                long begin = SystemClock.elapsedRealtime();
                String text = LocalWhisper.transcribeFile(new File(dir, name));
                if (installed.length() != 574041195L) throw new AssertionError("Bundled model was not extracted");
                long elapsed = SystemClock.elapsedRealtime() - begin;
                String normalized = text.toLowerCase(Locale.ROOT);
                if (text.startsWith("ERROR:") || !normalized.contains("\u0437\u0430\u0432\u0442\u0440\u0430") || !(normalized.contains("\u0441\u0435\u043c\u044c") || normalized.contains("7"))) throw new AssertionError(name + ": " + text);
                String key = name + (attempt++ == 0 ? "_cold" : "_warm");
                report.putString(key, elapsed + "ms: " + text);
            }
            String empty = LocalWhisper.transcribeFile(new File(dir, "silence.ogg"));
            if (!empty.contains("NO_SPEECH")) throw new AssertionError("Silence hallucination: " + empty);
            report.putString("silence", "PASS: no hallucination");
            report.putString("bundled_model", "PASS: no preinstalled model, automatic extraction, verified hash, voice and round-video audio");
            code = -1;
        } catch (Throwable error) { report.putString("error", android.util.Log.getStackTraceString(error)); }
        finally {
            if (moved && backup.exists()) {
                if (installed.length() == 574041195L) backup.delete();
                else { if (installed.exists()) installed.delete(); backup.renameTo(installed); }
            }
        }
        finish(code, report);
    }
}
