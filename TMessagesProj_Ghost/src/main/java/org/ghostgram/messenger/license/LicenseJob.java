package org.ghostgram.messenger.license;
import android.app.job.*;
public class LicenseJob extends JobService {
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean stopped;
    @Override public boolean onStartJob(JobParameters params) {
        stopped = false;
        if (!LicenseManager.ready) { handler.postDelayed(() -> { if (!stopped) onStartJob(params); }, 1000); return true; }
        OwnerControl.sync(true, () -> {
            if (stopped) return;
            if (LicenseManager.allowed() || LicenseManager.licenseId.isEmpty()) { jobFinished(params, false); return; }
            LicenseManager.refresh(null, (ok, error) -> { if (!stopped) jobFinished(params, !ok); });
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) { stopped = true; handler.removeCallbacksAndMessages(null); return true; }
}
