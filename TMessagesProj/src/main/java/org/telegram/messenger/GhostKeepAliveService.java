package org.telegram.messenger;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.LaunchActivity;

/** User-started persistent messaging connection, independent of Telegram presence. */
public final class GhostKeepAliveService extends Service {
    public static final int REQUEST_NOTIFICATIONS=7086;
    private static final int NOTIFICATION=60886;
    private static volatile boolean running;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wake;
    public static boolean enabled(){return ApplicationLoader.applicationContext!=null&&ApplicationLoader.applicationContext.getSharedPreferences("ghost_background",MODE_PRIVATE).getBoolean("enabled",false);}
    public static boolean active(){return enabled()&&GhostLicenseBridge.allowed();}
    public static boolean foregroundEnabled(){return ApplicationLoader.applicationContext.getSharedPreferences("ghost_background",MODE_PRIVATE).getBoolean("foreground",true);}
    public static boolean isRunning(){return running;}
    public static void setForegroundEnabled(Activity activity,boolean enabled){activity.getSharedPreferences("ghost_background",MODE_PRIVATE).edit().putBoolean("foreground",enabled).apply();if(enabled)startIfEnabled();else activity.stopService(new Intent(activity,GhostKeepAliveService.class));}
    public static void setEnabled(Activity activity,boolean enabled){
        activity.getSharedPreferences("ghost_background",MODE_PRIVATE).edit().putBoolean("enabled",enabled).apply();
        if(enabled)startIfEnabled();else activity.stopService(new Intent(activity,GhostKeepAliveService.class));
    }
    public static void startIfEnabled(){
        if(!enabled())return;
        Context c=ApplicationLoader.applicationContext;
        if(!foregroundEnabled()){
            Utilities.stageQueue.postRunnable(()->{if(!active())return;for(int a=0;a<UserConfig.MAX_ACCOUNT_COUNT;a++)if(UserConfig.isValidAccount(a)&&!GhostAccountSessions.disabled(a))ConnectionsManager.native_resumeNetwork(a,false);});
            return;
        }
        try{Intent intent=new Intent(c,GhostKeepAliveService.class);if(Build.VERSION.SDK_INT>=26)c.startForegroundService(intent);else c.startService(intent);}
        catch(Exception ignored){/* Retry from the next foreground entry. */}
    }
    public static void permissions(Activity activity){
        if(activity==null)return;
        if(Build.VERSION.SDK_INT>=33&&activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},REQUEST_NOTIFICATIONS);return;}
        batteryPermission(activity);
    }
    public static void batteryPermission(Activity activity){
        if(activity==null)return;
        PowerManager power=(PowerManager)activity.getSystemService(POWER_SERVICE);
        try{
            if(Build.VERSION.SDK_INT>=23&&!power.isIgnoringBatteryOptimizations(activity.getPackageName()))activity.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+activity.getPackageName())));
            else activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+activity.getPackageName())));
        }catch(Exception ignored){}
    }
    @Override public void onCreate(){super.onCreate();ApplicationLoader.postInitApplication();}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&"stop".equals(intent.getAction())){getSharedPreferences("ghost_background",MODE_PRIVATE).edit().putBoolean("enabled",false).apply();stopSelf();return START_NOT_STICKY;}
        if(!enabled()||!foregroundEnabled()){stopSelf();return START_NOT_STICKY;}
        try{
            NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            String channel="ghost_background_connection";
            if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(new NotificationChannel(channel,"Подключение AstraGram",NotificationManager.IMPORTANCE_LOW));
            PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,LaunchActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,GhostKeepAliveService.class).setAction("stop"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,channel):new Notification.Builder(this);
            b.setSmallIcon(R.drawable.notification).setContentTitle("AstraGram · подключение активно").setContentText("Получение сообщений в фоне").setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).addAction(0,"Остановить",stop);
            if(Build.VERSION.SDK_INT>=34)startForeground(NOTIFICATION,b.build(),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(NOTIFICATION,b.build());
            running=true;
            GhostDiagnosticLog.record("background_start");
            if(wake==null){wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"AstraGram:Messages");wake.setReferenceCounted(false);}
            handler.removeCallbacks(keep);handler.post(keep);
            return START_STICKY;
        }catch(Exception ignored){running=false;stopSelf();return START_NOT_STICKY;}
    }
    private final Runnable keep=new Runnable(){public void run(){
        if(!enabled()||!foregroundEnabled()){stopSelf();return;}
        if(GhostLicenseBridge.allowed()){
            boolean any=false;
            for(int i=0;i<UserConfig.MAX_ACCOUNT_COUNT;i++)if(UserConfig.isValidAccount(i)&&!GhostAccountSessions.disabled(i)){
                any=true;
                ConnectionsManager.getInstance(i).setPushConnectionEnabled(true);
                // Do not call setAppPaused(false) or send account.updateStatus.
                ConnectionsManager.native_resumeNetwork(i,false);
            }
            if(any)wake.acquire(90000);else if(wake.isHeld())wake.release();
        }else if(wake.isHeld())wake.release();
        handler.postDelayed(this,60000);
    }};
    @Override public void onDestroy(){
        GhostDiagnosticLog.record("background_stop");
        running=false;handler.removeCallbacksAndMessages(null);
        if(wake!=null&&wake.isHeld())wake.release();
        if(!enabled())for(int i=0;i<UserConfig.MAX_ACCOUNT_COUNT;i++)if(UserConfig.isValidAccount(i)&&(ApplicationLoader.mainInterfacePaused||i!=UserConfig.selectedAccount))ConnectionsManager.native_pauseNetwork(i);
        stopForeground(true);super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
