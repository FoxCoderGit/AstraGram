package org.telegram.messenger;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.provider.MediaStore;
import java.util.HashMap;

/** Persisted one-shot jobs. Android can stop them; native outbox and cloud queue survive. */
public final class GhostCloudJobs extends JobService {
    private static boolean observing;
    private final HashMap<Integer,GhostCloud> sessions=new HashMap<>();
    public static void bootstrap() {
        for(int account=0;account<UserConfig.MAX_ACCOUNT_COUNT;account++)schedule(account,15000);
        if(!observing) {
            observing=true;
            try{ApplicationLoader.applicationContext.getContentResolver().registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,true,new ContentObserver(new Handler(Looper.getMainLooper())){
                @Override public void onChange(boolean selfChange){for(int account=0;account<UserConfig.MAX_ACCOUNT_COUNT;account++)schedule(account,10000);}
            });}catch(Exception error){FileLog.e(error);}
        }
    }
    public static void schedule(int account,long delay) {
        if(!UserConfig.getInstance(account).isClientActivated())return;
        GhostCloud cloud=GhostCloud.get(account);if(cloud==null)return;
        JobScheduler scheduler=(JobScheduler)ApplicationLoader.applicationContext.getSystemService(JOB_SCHEDULER_SERVICE);
        GhostCloudStore.Config c=cloud.store.config();
        int id=9400+account;
        boolean cleanup=cloud.store.hasDeletions();
        if((!c.enabled||c.paused)&&!cleanup){scheduler.cancel(id);cloud.stop();return;}
        if(cloud.isRunning())return;
        PersistableBundle extras=new PersistableBundle();extras.putInt("account",account);extras.putLong("owner",cloud.store.owner);
        JobInfo.Builder builder=new JobInfo.Builder(id,new ComponentName(ApplicationLoader.applicationContext,GhostCloudJobs.class))
            .setExtras(extras).setPersisted(true).setMinimumLatency(Math.max(0,delay)).setRequiresCharging(c.enabled&&!c.paused&&c.charging)
            .setRequiredNetworkType(c.mobile?JobInfo.NETWORK_TYPE_ANY:JobInfo.NETWORK_TYPE_UNMETERED)
            .setBackoffCriteria(30000,JobInfo.BACKOFF_POLICY_EXPONENTIAL);
        try{scheduler.schedule(builder.build());}catch(Exception error){FileLog.e(error);}
    }
    @Override public boolean onStartJob(JobParameters parameters) {
        ApplicationLoader.postInitApplication();
        int account=parameters.getExtras().getInt("account",-1);
        if(account<0||account>=UserConfig.MAX_ACCOUNT_COUNT)return false;
        GhostCloud cloud=GhostCloud.get(account);
        if(cloud==null||cloud.store.owner!=parameters.getExtras().getLong("owner"))return false;
        if(cloud.isRunning())return false;
        String reason=cloud.waitReason();
        if(reason!=null&&cloud.store.config().enabled&&!cloud.store.config().paused){cloud.status=reason;schedule(account,15*60*1000);return false;}
        sessions.put(parameters.getJobId(),cloud);
        cloud.start(()->{
            sessions.remove(parameters.getJobId());jobFinished(parameters,false);
            schedule(account,15*60*1000);
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters parameters) {
        GhostCloud cloud=sessions.remove(parameters.getJobId());if(cloud!=null)cloud.stopWithoutCompletion();
        return true;
    }
}
