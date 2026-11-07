package com.nodo.messenger;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Periodic version check; only posts a notification and never installs silently. */
public final class NodoUpdateJobService extends JobService {
    @Override public boolean onStartJob(JobParameters params){
        NodeUpdater.checkBackground(this,()->jobFinished(params,false));
        return true;
    }
    @Override public boolean onStopJob(JobParameters params){return true;}
}
