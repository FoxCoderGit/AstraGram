package org.telegram.messenger;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;

/** Refreshes the two public badge feeds independently, including after process restarts. */
public final class GhostBadgeSyncJob extends JobService {
    static final long DAY_MS = 24 * 60 * 60 * 1000L;
    static final long RETRY_MS = 30 * 60 * 1000L;
    private static final int ASTRA_JOB = 47043;
    private static final int EXTERA_JOB = 47044;
    private static final DispatchQueue QUEUE = new DispatchQueue("badgeSync");
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SparseArray<JobParameters> active = new SparseArray<>();

    public static void initialize(Context context) {
        ensureScheduled(context, ASTRA_JOB);
        setExteraEnabled(GhostOptionalFeatures.enabled(GhostOptionalFeatures.EXTERA_SUPPORTERS));
    }

    static void setExteraEnabled(boolean enabled) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) return;
        if (enabled) ensureScheduled(context, EXTERA_JOB);
        else context.getSystemService(JobScheduler.class).cancel(EXTERA_JOB);
    }

    private static void ensureScheduled(Context context, int id) {
        if (context.getSystemService(JobScheduler.class).getPendingJob(id) == null) schedule(context, id, 0);
    }

    private static void schedule(Context context, int id, long delayMs) {
        JobInfo job = new JobInfo.Builder(id, new ComponentName(context, GhostBadgeSyncJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setMinimumLatency(delayMs)
                .build();
        context.getSystemService(JobScheduler.class).schedule(job);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        int id = params.getJobId();
        if (id != ASTRA_JOB && id != EXTERA_JOB) return false;
        if (id == EXTERA_JOB && !GhostOptionalFeatures.enabled(GhostOptionalFeatures.EXTERA_SUPPORTERS)) return false;
        active.put(id, params);
        QUEUE.postRunnable(() -> {
            long delay = id == ASTRA_JOB ? GhostBadges.syncIfDue() : GhostExteraSupporters.syncIfDue();
            main.post(() -> {
                if (active.get(id) != params) return;
                active.remove(id);
                jobFinished(params, false);
                if (id == ASTRA_JOB || GhostOptionalFeatures.enabled(GhostOptionalFeatures.EXTERA_SUPPORTERS)) {
                    schedule(this, id, delay);
                }
            });
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (active.get(params.getJobId()) == params) active.remove(params.getJobId());
        return params.getJobId() == ASTRA_JOB || GhostOptionalFeatures.enabled(GhostOptionalFeatures.EXTERA_SUPPORTERS);
    }
}
