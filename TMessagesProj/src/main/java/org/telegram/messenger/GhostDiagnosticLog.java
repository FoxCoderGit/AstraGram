package org.telegram.messenger;

import android.content.Context;
import android.os.Build;
import java.util.*;
import org.json.*;

/** Small opt-in technical journal. Payloads, credentials, identifiers and messages are never recorded. */
public final class GhostDiagnosticLog {
    private static final Set<String> ALLOWED=new HashSet<>(Arrays.asList("auth_network","speech","playback","background_start","background_stop","connection_ok","connection_failed"));
    private static android.content.SharedPreferences prefs(){return ApplicationLoader.applicationContext.getSharedPreferences("ghost_technical_diagnostics",Context.MODE_PRIVATE);}
    public static boolean enabled(){return ApplicationLoader.applicationContext!=null&&prefs().getBoolean("enabled",false);}
    public static void setEnabled(boolean value){prefs().edit().putBoolean("enabled",value).apply();}
    public static synchronized void record(String event){
        if(!ALLOWED.contains(event)||!enabled())return;
        try{JSONArray previous=new JSONArray(prefs().getString("events","[]")),result=new JSONArray();for(int i=Math.max(0,previous.length()-199);i<previous.length();i++)result.put(previous.get(i));result.put(new JSONObject().put("time",System.currentTimeMillis()).put("event",event));prefs().edit().putString("events",result.toString()).apply();}catch(Exception ignored){}
    }
    public static String version(){try{return ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(),0).versionName;}catch(Exception e){return "—";}}
    private static String clean(String s){return s==null?"":s.replace('\n',' ').replace('\r',' ');}
    public static String report(){
        StringBuilder out=new StringBuilder("AstraGram technical diagnostics\n");out.append("Version: ").append(version()).append("\nAndroid API: ").append(Build.VERSION.SDK_INT).append("\nDevice: ").append(clean(Build.MANUFACTURER)).append(' ').append(clean(Build.MODEL)).append("\nNetwork: ").append(ApplicationLoader.isNetworkOnline()).append("\nBackground connection: ").append(GhostKeepAliveService.enabled()).append("\nForeground service: ").append(GhostKeepAliveService.isRunning()).append("\nRecording: ").append(enabled()).append("\n\n");
        try{JSONArray list=new JSONArray(prefs().getString("events","[]"));for(int i=0;i<list.length();i++){JSONObject item=list.getJSONObject(i);String name=item.optString("event");if(ALLOWED.contains(name))out.append(item.optLong("time")).append(' ').append(name).append('\n');}}catch(Exception ignored){}
        return out.toString();
    }
    public static final String CHANGELOG="AstraGram 113\n\n• Исправлено падение при открытии профиля с бейджем.\n• Проверка обновлений в фоне и уведомления.\n• Проверка versionCode, SHA-256 и подписи APK.\n• Возобновление загрузки, пропуск версии и критические обновления.\n• Собственные бейджи AstraGram и выравнивание переключателей плагинов.";
    private GhostDiagnosticLog(){}
}
