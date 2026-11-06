package org.telegram.messenger;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.telegram.ui.LaunchActivity;

/** In-process jobs never resume a send after process death. Notifications contain no chat content. */
public final class GhostBackgroundTasks {
    public static final String ACTION="org.ghostgram.BACKGROUND_TASKS";
    private static final String CHANNEL="ghost_background_tasks";
    private static final ConcurrentSkipListMap<Integer,Task> tasks=new ConcurrentSkipListMap<>();
    private static final AtomicInteger sequence=new AtomicInteger(61000);
    public static final class Task {
        public final int id;public final String title;
        public volatile String stage="Подготовка";public volatile int percent=-1;
        public volatile boolean running=true,finished;private Runnable cancel;private volatile Consumer<Activity> open;
        private long notified;
        private Task(String title,Runnable cancel){id=sequence.incrementAndGet();this.title=title;this.cancel=cancel;}
        public void progress(String stage,int percent){AndroidUtilities.runOnUIThread(()->{
            if(finished)return;this.stage=stage;this.percent=percent;running=true;open=null;
            long now=android.os.SystemClock.elapsedRealtime();if(now-notified>500){notified=now;refresh();}
        });}
        public void ready(String stage,Consumer<Activity> open){AndroidUtilities.runOnUIThread(()->{
            if(finished)return;this.stage=stage;this.percent=100;this.running=false;this.open=open;refresh();
        });}
        public void finish(String stage,Consumer<Activity> open){AndroidUtilities.runOnUIThread(()->{
            this.stage=stage;this.percent=100;this.running=false;this.finished=true;this.cancel=null;this.open=open;refresh();
        });}
        public void cancel(){AndroidUtilities.runOnUIThread(()->{
            if(finished)return;Runnable action=cancel;finish("Отменено",null);if(action!=null)action.run();
        });}
        public boolean canOpen(){return open!=null;}
        public void open(Activity activity){if(open!=null&&!activity.isFinishing())open.accept(activity);}
        public void dismiss(){if(!finished)return;tasks.remove(id);manager().cancel(id);refresh();}
    }
    public static Task create(String title,Runnable cancel){
        Task task=new Task(title,cancel);tasks.put(task.id,task);
        // Retain active jobs; discard only old, completed rows.
        if(tasks.size()>32){for(Task old:new ArrayList<>(tasks.values()))if(old.finished&&old!=task){tasks.remove(old.id);manager().cancel(old.id);if(tasks.size()<=32)break;}}
        refresh();return task;
    }
    public static ArrayList<Task> list(){return new ArrayList<>(tasks.values());}
    static Task running(){for(Task task:tasks.values())if(task.running&&!task.finished)return task;return null;}
    private static NotificationManager manager(){return (NotificationManager)ApplicationLoader.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE);}
    static Notification notification(Task task){
        Context c=ApplicationLoader.applicationContext;
        if(Build.VERSION.SDK_INT>=26)manager().createNotificationChannel(new NotificationChannel(CHANNEL,"Фоновые задачи",NotificationManager.IMPORTANCE_LOW));
        Intent launch=new Intent(c,LaunchActivity.class).setAction(ACTION).putExtra("ghost_task_id",task.id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open=PendingIntent.getActivity(c,task.id,launch,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(c,CHANNEL):new Notification.Builder(c);
        b.setSmallIcon(R.drawable.notification).setContentTitle(task.title).setContentText(task.stage).setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(task.running).setAutoCancel(!task.running);
        if(task.running)b.setProgress(100,Math.max(0,task.percent),task.percent<0);
        if(!task.finished){Intent cancel=new Intent(c,GhostTaskService.CancelReceiver.class).putExtra("task",task.id);
            b.addAction(0,"Отмена",PendingIntent.getBroadcast(c,task.id,cancel,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));}
        return b.build();
    }
    static void refresh(){
        try{
            for(Task t:tasks.values())if(!t.running)manager().notify(t.id,notification(t));
            if(running()!=null){Intent service=new Intent(ApplicationLoader.applicationContext,GhostTaskService.class);
                if(Build.VERSION.SDK_INT>=26)ApplicationLoader.applicationContext.startForegroundService(service);else ApplicationLoader.applicationContext.startService(service);
            }else ApplicationLoader.applicationContext.stopService(new Intent(ApplicationLoader.applicationContext,GhostTaskService.class));
        }catch(Exception error){FileLog.e(error);}
    }
    private GhostBackgroundTasks(){}
}
