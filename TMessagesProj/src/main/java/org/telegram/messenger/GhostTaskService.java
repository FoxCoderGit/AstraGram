package org.telegram.messenger;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** Keeps local media work alive when the user switches chats or apps. */
public final class GhostTaskService extends Service {
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        GhostBackgroundTasks.Task task=GhostBackgroundTasks.running();
        if(task==null){stopSelf();return START_NOT_STICKY;}
        if(Build.VERSION.SDK_INT>=35)startForeground(60999,GhostBackgroundTasks.notification(task),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
        else if(Build.VERSION.SDK_INT>=29)startForeground(60999,GhostBackgroundTasks.notification(task),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(60999,GhostBackgroundTasks.notification(task));
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onTimeout(int startId,int type){for(GhostBackgroundTasks.Task task:GhostBackgroundTasks.list())if(task.running)task.cancel();stopSelf();}
    @Override public void onDestroy(){stopForeground(true);super.onDestroy();}
    public static final class CancelReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context,Intent intent){int id=intent.getIntExtra("task",0);for(GhostBackgroundTasks.Task task:GhostBackgroundTasks.list())if(task.id==id){task.cancel();break;}}
    }
}
