package org.telegram.messenger;

import android.app.job.JobParameters;
import android.app.job.JobService;

public final class AstraRelayJob extends JobService {
    private volatile boolean active;
    @Override public boolean onStartJob(JobParameters parameters){active=true;AstraRelay.wake(()->{if(active)jobFinished(parameters,false);});return true;}
    @Override public boolean onStopJob(JobParameters parameters){active=false;return true;}
}
