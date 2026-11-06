package org.ghostgram.messenger.license;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.net.Proxy;
import java.security.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.ghostgram.messenger.BuildConfig;
import org.telegram.messenger.*;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.BulletinFactory;

/** Signed owner policy, private installation statistics and explicit APK updates. */
public final class OwnerControl {
    private static final long DAY=86400000L;
    private static final DispatchQueue queue=new DispatchQueue("ghostOwnerControl");
    private static SharedPreferences prefs;
    private static volatile long policyDeadline;
    private static volatile boolean freeAccess;
    private static volatile JSONObject update;
    private static long nextSync;
    private static boolean syncing;
    private static final AtomicBoolean downloading=new AtomicBoolean();
    private static volatile String updateError="";
    private static final int UPDATE_NOTIFICATION=47041;
    private static AlertDialog updateDialog;
    private OwnerControl(){}
    static void initialize(){
        prefs=LicenseManager.context.getSharedPreferences("ghost_owner_control",Context.MODE_PRIVATE);
        try{
            String saved=prefs.getString("policy","");
            if(!saved.isEmpty()){
                long elapsed=SystemClock.elapsedRealtime(),wall=System.currentTimeMillis();
                long age=Settings.Global.getInt(LicenseManager.context.getContentResolver(),Settings.Global.BOOT_COUNT,-1)==prefs.getInt("boot",-2)?elapsed-prefs.getLong("elapsed",-1):wall-prefs.getLong("wall",-1);
                if(age>=0&&wall>=prefs.getLong("wall",0)-60000)accept(new JSONObject(saved),age,false);
            }
        }catch(Exception ignored){}
        sync(false);
    }
    static boolean bypass(){return freeAccess&&SystemClock.elapsedRealtime()<policyDeadline;}
    static long remaining(){return bypass()?policyDeadline-SystemClock.elapsedRealtime():0;}
    static void sync(boolean force){
        sync(force,null);
    }
    static void sync(boolean force,Runnable finished){
        synchronized(OwnerControl.class){if(prefs==null||syncing||!force&&SystemClock.elapsedRealtime()<nextSync){if(finished!=null)AndroidUtilities.runOnUIThread(finished);return;}syncing=true;nextSync=SystemClock.elapsedRealtime()+60000;}
        queue.postRunnable(()->{
            try{
                JSONObject policy=get("/v1/policy");accept(policy,0,true);
                if(bypass()&&prefs.getLong("free_until",0)<System.currentTimeMillis()/1000+3600){JSONObject lease=deviceRequest("free","");JSONObject claims=LicenseManager.verify(lease,null);prefs.edit().putString("free_lease",lease.toString()).putLong("free_until",claims.getLong("until")).apply();}
                if(prefs.getBoolean("statistics",true)&&(System.currentTimeMillis()-prefs.getLong("heartbeat",0)>30*60000L||!BuildConfig.VERSION_NAME.equals(prefs.getString("heartbeat_version","")))){heartbeat();prefs.edit().putLong("heartbeat",System.currentTimeMillis()).putString("heartbeat_version",BuildConfig.VERSION_NAME).apply();}
                synchronized(OwnerControl.class){nextSync=SystemClock.elapsedRealtime()+15*60000;}
            }catch(Exception ignored){updateError="Не удалось проверить обновления. Проверьте соединение.";}
            finally{synchronized(OwnerControl.class){syncing=false;}AndroidUtilities.runOnUIThread(()->{LicenseManager.policyChanged();if(finished!=null)finished.run();});}
        });
    }
    private static void accept(JSONObject response,long age,boolean save)throws Exception{
        byte[] raw=android.util.Base64.decode(response.getString("payload"),android.util.Base64.URL_SAFE|android.util.Base64.NO_WRAP|android.util.Base64.NO_PADDING);
        Signature verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(LicenseManager.serverKey);verifier.update(raw);
        if(!verifier.verify(android.util.Base64.decode(response.getString("signature"),android.util.Base64.URL_SAFE|android.util.Base64.NO_WRAP|android.util.Base64.NO_PADDING)))throw new SecurityException();
        JSONObject p=new JSONObject(new String(raw,java.nio.charset.StandardCharsets.UTF_8));
        long lifetime=(p.getLong("until")-p.getLong("issued_at"))*1000;
        if(p.getInt("v")!=1||!"policy".equals(p.getString("kind"))||!"org.ghostgram.messenger".equals(p.getString("app"))||lifetime<=0||lifetime>DAY||age>=lifetime||p.getLong("revision")<prefs.getLong("revision",0))throw new SecurityException();
        freeAccess=!p.getBoolean("require_key");policyDeadline=SystemClock.elapsedRealtime()+lifetime-age;update=p.optJSONObject("update");
        if(save)prefs.edit().putString("policy",response.toString()).putLong("revision",p.getLong("revision")).putLong("wall",System.currentTimeMillis()).putLong("elapsed",SystemClock.elapsedRealtime()).putInt("boot",Settings.Global.getInt(LicenseManager.context.getContentResolver(),Settings.Global.BOOT_COUNT,-1)).apply();
        updateError="";
        notifyAvailable();
    }
    private static HttpsURLConnection connection(String path)throws Exception{
        HttpsURLConnection c=(HttpsURLConnection)new URL(GhostServiceEndpoints.UPDATES+path).openConnection(Proxy.NO_PROXY);
        c.setConnectTimeout(12000);c.setReadTimeout(20000);c.setInstanceFollowRedirects(false);return c;
    }
    private static JSONObject get(String path)throws Exception{
        HttpsURLConnection c=connection(path);
        try{if(c.getResponseCode()!=200)throw new IOException();try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[2048];int n;while((n=in.read(b))!=-1){if(out.size()+n>32768)throw new IOException();out.write(b,0,n);}return new JSONObject(out.toString("UTF-8"));}}finally{c.disconnect();}
    }
    private static void heartbeat()throws Exception{
        JSONObject errors=new JSONObject();for(String name:new String[]{"auth_network","speech","playback"})errors.put(name,prefs.getInt("error_"+name,0));
        JSONObject data=new JSONObject().put("version",BuildConfig.VERSION_NAME).put("build",buildNumber()).put("sdk",Build.VERSION.SDK_INT).put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL).put("errors",errors);
        deviceRequest("telemetry",data.toString());
    }
    static JSONObject deviceRequest(String purpose,String value)throws Exception{
        String publicKey=android.util.Base64.encodeToString(LicenseManager.device.getPublic().getEncoded(),android.util.Base64.URL_SAFE|android.util.Base64.NO_WRAP|android.util.Base64.NO_PADDING);
        String nonce=LicenseManager.post("/v1/challenge",new JSONObject().put("public_key",publicKey).put("purpose",purpose)).getString("nonce");
        Signature signature=Signature.getInstance("SHA256withECDSA");signature.initSign(LicenseManager.device.getPrivate());signature.update(("ghostgram-v1\n"+purpose+"\n"+nonce+"\n"+value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return LicenseManager.post("/v1/"+purpose,new JSONObject().put("public_key",publicKey).put("nonce",nonce).put("value",value).put("signature",android.util.Base64.encodeToString(signature.sign(),android.util.Base64.URL_SAFE|android.util.Base64.NO_WRAP|android.util.Base64.NO_PADDING)));
    }
    static JSONObject gameLease()throws Exception{JSONObject lease=new JSONObject(prefs.getString("free_lease",""));JSONObject claims=LicenseManager.verify(lease,null);if(!bypass()||claims.getLong("until")<=System.currentTimeMillis()/1000)throw new SecurityException();return lease;}
    public static void recordError(String name){
        if(prefs==null||!prefs.getBoolean("statistics",true)||!(name.equals("auth_network")||name.equals("speech")||name.equals("playback")))return;
        synchronized(OwnerControl.class){prefs.edit().putInt("error_"+name,Math.min(100000000,prefs.getInt("error_"+name,0)+1)).apply();}
    }
    private static int buildNumber(){try{return Integer.parseInt(BuildConfig.VERSION_NAME.split("(?:ghost|veyl|astra)\\.")[1].split("[^0-9]")[0]);}catch(Exception e){return 0;}}
    public static void show(Activity activity){
        if(prefs==null){BulletinFactory.global().createErrorBulletin("Сервис ещё запускается.").show();return;}
        boolean statistics=prefs.getBoolean("statistics",true);
        new AlertDialog.Builder(activity).setTitle("AstraGram")
            .setItems(new String[]{"Проверить обновления","Статистика установок · "+(statistics?"включена":"выключена")},(d,which)->{
                if(which==0)checkUpdate(activity);
                else new AlertDialog.Builder(activity).setTitle("Статистика AstraGram")
                    .setMessage("Владельцу мода передаются версия приложения и Android, модель телефона, время активности и счётчики ошибок. Переписка, номера, контакты и файлы не передаются.")
                    .setPositiveButton(statistics?"Отключить":"Включить",(a,b)->prefs.edit().putBoolean("statistics",!statistics).apply())
                    .setNegativeButton("Закрыть",null).show();
            }).setNegativeButton("Закрыть",null).show();
    }
    public static void checkUpdate(Activity activity){
        AlertDialog progress=new AlertDialog(activity,3);progress.show();
        queue.postRunnable(()->{
            String error=null;try{accept(get("/v1/policy"),0,true);}catch(Exception e){error="Не удалось проверить обновления. Проверьте соединение.";}
            final String failure=error;
            AndroidUtilities.runOnUIThread(()->{
                progress.dismiss();if(activity.isFinishing()||activity.isDestroyed())return;
                if(failure!=null){new AlertDialog.Builder(activity).setTitle("Обновления").setMessage(failure).setPositiveButton("ОК",null).show();return;}
                JSONObject release=update;
                if(release==null){new AlertDialog.Builder(activity).setTitle("Обновления").setMessage("Опубликованных обновлений пока нет.").setPositiveButton("ОК",null).show();return;}
                try {
                    UpdateSpec spec=new UpdateSpec(release);
                    if(!spec.newerThan(UpdateApk.installedCode())){new AlertDialog.Builder(activity).setTitle("Обновления").setMessage("У вас установлена актуальная версия.").setPositiveButton("ОК",null).show();return;}
                    showRelease(activity,release,spec);
                } catch(Exception e){new AlertDialog.Builder(activity).setTitle("Обновления").setMessage("Сервер вернул неполные сведения об обновлении. Загрузка заблокирована.").setPositiveButton("Повторить",(d,w)->checkUpdate(activity)).setNegativeButton("Закрыть",null).show();}
            });
        });
    }
    public static String info(){
        try{
            JSONObject data=new JSONObject().put("version",BuildConfig.VERSION_NAME).put("build",buildNumber()).put("version_code",UpdateApk.installedCode());
            boolean known=SystemClock.elapsedRealtime()<policyDeadline;
            String status=!known?"unknown":"current";
            if(known&&update!=null){try{if(new UpdateSpec(update).newerThan(UpdateApk.installedCode()))status="available";}catch(Exception e){status="error";}}
            if(!updateError.isEmpty())status="error";
            data.put("status",status).put("error",updateError);
            if(update!=null)data.put("release",update);
            data.put("skipped_code",prefs==null?0:prefs.getLong("update_skipped_code",0));
            data.put("downloading",downloading.get());
            data.put("automatic",prefs==null||prefs.getBoolean("automatic_updates",true)).put("statistics",prefs==null||prefs.getBoolean("statistics",true));
            return data.toString();
        }catch(Exception ignored){return "{}";}
    }
    public static void option(String name,boolean value){
        if(prefs!=null&&(name.equals("automatic_updates")||name.equals("statistics")))prefs.edit().putBoolean(name,value).apply();
        if(prefs!=null&&name.equals("skip_update")&&update!=null)try{
            UpdateSpec spec=new UpdateSpec(update);if(spec.canSkip()){prefs.edit().putLong("update_skipped_code",value?spec.versionCode:0).apply();if(value)cancelUpdateNotification();}
        }catch(Exception ignored){}
    }
    static void maybeNotify(Activity activity){
        if(prefs==null||activity==null||activity.isFinishing()||activity.isDestroyed()||activity instanceof LicenseActivity||update==null||downloading.get()||SystemClock.elapsedRealtime()>=policyDeadline)return;
        try {
            UpdateSpec spec=new UpdateSpec(update);
            if(spec.canSkip()&&!prefs.getBoolean("automatic_updates",true))return;
            if(!spec.newerThan(UpdateApk.installedCode())||spec.canSkip()&&(prefs.getLong("update_skipped_code",0)==spec.versionCode||prefs.getLong("update_prompt_code",0)>=spec.versionCode))return;
            prefs.edit().putLong("update_prompt_code",spec.versionCode).apply();
            showRelease(activity,update,spec);
        } catch(Exception ignored){}
    }
    private static void showRelease(Activity activity,JSONObject release,UpdateSpec spec){
        if(updateDialog!=null&&updateDialog.isShowing())return;
        AlertDialog.Builder dialog=new AlertDialog.Builder(activity).setTitle((spec.mandatory?"Критическое обновление · ":"AstraGram ")+spec.version)
            .setMessage(spec.notes+"\n\nРазмер: "+AndroidUtilities.formatFileSize(spec.size))
            .setPositiveButton("Скачать",(d,w)->download(activity,release));
        if(spec.canSkip())dialog.setNegativeButton("Позже",null).setNeutralButton("Пропустить версию",(d,w)->{prefs.edit().putLong("update_skipped_code",spec.versionCode).apply();cancelUpdateNotification();});
        else dialog.setNegativeButton("Закрыть AstraGram",(d,w)->activity.finishAffinity());
        AlertDialog shown=dialog.create();updateDialog=shown;
        shown.setOnDismissListener(d->{if(updateDialog==shown)updateDialog=null;});
        shown.setCancelable(spec.canSkip());shown.setCanceledOnTouchOutside(spec.canSkip());shown.show();
    }
    private static void cancelUpdateNotification(){
        try{LicenseManager.context.getSystemService(android.app.NotificationManager.class).cancel(UPDATE_NOTIFICATION);}catch(Exception ignored){}
    }
    private static void notifyAvailable(){
        try {
            if(update==null){cancelUpdateNotification();return;}
            UpdateSpec spec=new UpdateSpec(update);
            if(!spec.newerThan(UpdateApk.installedCode())){cancelUpdateNotification();return;}
            if(spec.canSkip()&&(!prefs.getBoolean("automatic_updates",true)||prefs.getLong("update_skipped_code",0)==spec.versionCode))return;
            if(prefs.getLong("update_notification_code",0)==spec.versionCode)return;
            Context context=LicenseManager.context;
            if(!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled())return;
            android.app.NotificationManager manager=context.getSystemService(android.app.NotificationManager.class);
            String channel="ghost_updates";
            if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(new android.app.NotificationChannel(channel,"Обновления AstraGram",android.app.NotificationManager.IMPORTANCE_DEFAULT));
            Intent open=context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            if(open==null)return;
            open.putExtra("ghost_open_updates",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            android.app.PendingIntent pending=android.app.PendingIntent.getActivity(context,UPDATE_NOTIFICATION,open,android.app.PendingIntent.FLAG_UPDATE_CURRENT|android.app.PendingIntent.FLAG_IMMUTABLE);
            androidx.core.app.NotificationCompat.Builder notification=new androidx.core.app.NotificationCompat.Builder(context,channel)
                .setSmallIcon(R.drawable.notification).setContentTitle(spec.mandatory?"Критическое обновление AstraGram":"Доступно обновление AstraGram")
                .setContentText(spec.version).setStyle(new androidx.core.app.NotificationCompat.BigTextStyle().bigText(spec.version+"\n"+spec.notes))
                .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true);
            manager.notify(UPDATE_NOTIFICATION,notification.build());
            prefs.edit().putLong("update_notification_code",spec.versionCode).apply();
        }catch(Exception ignored){}
    }
    private static void download(Activity activity,JSONObject r){
        final UpdateSpec spec;
        try{spec=new UpdateSpec(r);if(!spec.newerThan(UpdateApk.installedCode()))throw new SecurityException();}
        catch(Exception e){BulletinFactory.global().createErrorBulletin("Некорректные сведения об обновлении.").show();return;}
        if(!downloading.compareAndSet(false,true))return;
        AtomicBoolean canceled=new AtomicBoolean();AlertDialog progress=new AlertDialog(activity,2);progress.setTitle("Обновление AstraGram");progress.setMessage("Загрузка…");progress.setProgress(0);
        progress.setButton(AlertDialog.BUTTON_NEGATIVE,spec.canSkip()?"Отмена":"Закрыть AstraGram",(d,w)->{canceled.set(true);if(spec.mandatory)activity.finishAffinity();});
        progress.setOnCancelListener(d->canceled.set(true));progress.setCancelable(spec.canSkip());progress.setCanceledOnTouchOutside(spec.canSkip());progress.show();
        queue.postRunnable(()->{
            File folder=new File(LicenseManager.context.getFilesDir(),"updates");
            File file=new File(folder,spec.sha256+".apk"),stage=new File(file.getPath()+".download");String error=null;
            try{
                if(!file.getParentFile().isDirectory()&&!file.getParentFile().mkdirs())throw new IOException();
                boolean cached=file.isFile()&&file.length()==spec.size&&spec.sha256.equals(fileHash(file,canceled));
                if(!cached){
                    if(file.exists()&&!file.delete())throw new IOException();
                    IOException last=null;
                    for(int attempt=0;attempt<3;attempt++){
                        if(canceled.get())throw new InterruptedIOException();
                        try{downloadPart(spec,stage,canceled,progress);last=null;break;}
                        catch(InterruptedIOException e){if(canceled.get())throw e;last=e;}
                        catch(IOException e){last=e;}
                    }
                    if(last!=null)throw last;
                    if(!spec.sha256.equals(fileHash(stage,canceled))){stage.delete();throw new SecurityException("APK SHA-256 mismatch");}
                    UpdateApk.verify(stage,spec);
                    if(!stage.renameTo(file))throw new IOException();
                }else UpdateApk.verify(file,spec);
            }catch(SecurityException e){file.delete();stage.delete();error="Проверка APK не пройдена: подпись, версия или SHA-256 не совпадают. Установка заблокирована.";}
            catch(Exception e){error="Не удалось скачать обновление. Повторная попытка продолжит загрузку с сохранённого места.";}
            final String failure=error;AndroidUtilities.runOnUIThread(()->{
                downloading.set(false);progress.dismiss();if(canceled.get()||activity.isFinishing()||activity.isDestroyed())return;
                if(failure!=null){
                    AlertDialog retry=new AlertDialog.Builder(activity).setTitle("Обновление").setMessage(failure).setPositiveButton("Повторить",(d,w)->download(activity,r)).setNegativeButton(spec.canSkip()?"Позже":"Закрыть AstraGram",(d,w)->{if(spec.mandatory)activity.finishAffinity();}).create();
                    retry.setCancelable(spec.canSkip());retry.setCanceledOnTouchOutside(spec.canSkip());retry.show();return;
                }
                try{
                    if(Build.VERSION.SDK_INT>=26&&!activity.getPackageManager().canRequestPackageInstalls()){
                        AlertDialog permission=new AlertDialog.Builder(activity).setTitle("Установка обновления").setMessage("Разрешите установку приложений для AstraGram, затем снова откройте проверку обновлений.").setPositiveButton("Разрешить",(d,w)->activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())))).setNegativeButton(spec.canSkip()?"Позже":"Закрыть AstraGram",(d,w)->{if(spec.mandatory)activity.finishAffinity();}).create();
                        permission.setCancelable(spec.canSkip());permission.setCanceledOnTouchOutside(spec.canSkip());permission.show();return;
                    }
                    Uri uri=FileProvider.getUriForFile(activity,activity.getPackageName()+".provider",file);
                    activity.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                }catch(Exception e){BulletinFactory.global().createErrorBulletin("APK сохранён. Не удалось открыть установщик Android.").show();if(spec.mandatory)showRelease(activity,r,spec);}
            });
        });
    }
    private static String fileHash(File file,AtomicBoolean canceled)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[131072];int n;while((n=in.read(buffer))!=-1){if(canceled.get())throw new InterruptedIOException();digest.update(buffer,0,n);}}
        return hex(digest.digest());
    }
    private static void downloadPart(UpdateSpec spec,File stage,AtomicBoolean canceled,AlertDialog progress)throws IOException{
        long offset=stage.isFile()?stage.length():0;
        if(offset>spec.size){if(!stage.delete())throw new IOException();offset=0;}
        if(offset==spec.size)return;
        HttpsURLConnection c;
        try{c=connection(spec.path);}catch(Exception e){throw new IOException(e);}
        c.setRequestProperty("Accept-Encoding","identity");
        if(offset>0)c.setRequestProperty("Range","bytes="+offset+"-");
        try{
            int status=c.getResponseCode();
            if(status==206){if(!UpdateSpec.validRange(c.getHeaderField("Content-Range"),offset,spec.size)){stage.delete();throw new IOException("Invalid Content-Range");}}
            else if(status==200)offset=0; // Server does not support Range: restart safely.
            else throw new IOException("HTTP "+status);
            long contentLength=c.getContentLength();
            if(contentLength>=0&&contentLength!=spec.size-offset)throw new IOException("Invalid download length");
            try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(stage,offset>0)){
                byte[] buffer=new byte[131072];long received=offset,last=0;int n;
                while((n=in.read(buffer))!=-1){
                    if(canceled.get())throw new InterruptedIOException();
                    received+=n;if(received>spec.size)throw new IOException("APK too large");out.write(buffer,0,n);
                    if(SystemClock.elapsedRealtime()-last>200){last=SystemClock.elapsedRealtime();final int percent=(int)(received*100/spec.size);AndroidUtilities.runOnUIThread(()->progress.setProgress(percent));}
                }
                out.getFD().sync();if(received!=spec.size)throw new IOException("Incomplete download");
            }
        }finally{c.disconnect();}
    }
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(java.util.Locale.US,"%02x",b&255));return s.toString();}
}
