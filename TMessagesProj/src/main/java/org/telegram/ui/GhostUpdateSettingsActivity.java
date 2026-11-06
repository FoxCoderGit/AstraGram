package org.telegram.ui;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.view.View;
import android.widget.LinearLayout;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;
import org.telegram.messenger.*;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.*;

public final class GhostUpdateSettingsActivity extends UniversalFragment {
    private static final int EXPORT_LOG=7089;
    private boolean resumed;
    private String lastInfo="";
    private final Runnable poll=new Runnable(){public void run(){if(!resumed)return;String info=GhostLicenseBridge.updateInfo();if(!info.equals(lastInfo)){lastInfo=info;refresh();}AndroidUtilities.runOnUIThread(this,2000);}};
    private static JSONObject info(){try{return new JSONObject(GhostLicenseBridge.updateInfo());}catch(Exception e){return new JSONObject();}}
    public static String summary(){String status=info().optString("status");return "current".equals(status)?"Установлена последняя версия":"available".equals(status)?"Доступна новая версия":"error".equals(status)?"Не удалось проверить обновления":"Проверка обновлений и журналы";}
    @Override protected String getTitle(){return "Обновления и диагностика";}
    @Override public void onResume(){super.onResume();resumed=true;AndroidUtilities.runOnUIThread(poll);}
    @Override public void onPause(){resumed=false;AndroidUtilities.cancelRunOnUIThread(poll);super.onPause();}
    @Override public void onFragmentDestroy(){resumed=false;AndroidUtilities.cancelRunOnUIThread(poll);super.onFragmentDestroy();}
    private void refresh(){if(listView!=null&&getParentActivity()!=null)listView.adapter.update(true);}
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter){
        JSONObject data=info();String status=data.optString("status","unknown");boolean current="current".equals(status);
        items.add(UItem.asCustom(GhostSettingsCards.brand(getContext(),GhostDiagnosticLog.version())));
        LinearLayout check=(LinearLayout)GhostSettingsCards.status(getContext(),current,current?"Установлена последняя версия":"available".equals(status)?"Доступна новая версия":"error".equals(status)?"Не удалось проверить обновления":"Версия пока не проверена",data.optString("error","").isEmpty()?null:data.optString("error"));
        check.addView(GhostSettingsCards.button(getContext(),"Проверить обновления",()->GhostLicenseBridge.checkUpdates(getParentActivity())));items.add(UItem.asCustom(GhostSettingsCards.wrap(getContext(),check)));
        items.add(UItem.asHeader("Обновления"));LinearLayout updates=GhostSettingsCards.group(getContext());
        updates.addView(GhostSettingsCards.row(getContext(),"Проверять автоматически",null,null,data.optBoolean("automatic",true),0,()->{GhostLicenseBridge.updateOption("automatic_updates",!data.optBoolean("automatic",true));refresh();}));GhostSettingsCards.line(updates);
        JSONObject release=data.optJSONObject("release");
        String notes=release!=null&&"available".equals(status)?release.optString("notes",""):GhostDiagnosticLog.CHANGELOG;
        updates.addView(GhostSettingsCards.row(getContext(),"Список изменений",null,null,null,0,()->message("Список изменений",notes.isEmpty()?"Описание изменений не указано.":notes)));
        if(release!=null&&"available".equals(status)&&!release.optBoolean("mandatory",false)){
            GhostSettingsCards.line(updates);
            boolean skipped=data.optLong("skipped_code")==release.optLong("version_code");
            updates.addView(GhostSettingsCards.row(getContext(),"Пропустить эту версию","Проверка вручную остаётся доступна",null,skipped,0,()->{GhostLicenseBridge.updateOption("skip_update",!skipped);refresh();}));
        }
        items.add(UItem.asCustom(GhostSettingsCards.wrap(getContext(),updates)));
        items.add(UItem.asHeader("Диагностика"));LinearLayout diagnostics=GhostSettingsCards.group(getContext());
        diagnostics.addView(GhostSettingsCards.row(getContext(),"Запись журналов",null,null,GhostDiagnosticLog.enabled(),0,()->{GhostDiagnosticLog.setEnabled(!GhostDiagnosticLog.enabled());refresh();}));GhostSettingsCards.line(diagnostics);
        diagnostics.addView(GhostSettingsCards.row(getContext(),"Экспорт журналов",null,null,null,R.drawable.msg_log,this::export));GhostSettingsCards.line(diagnostics);
        diagnostics.addView(GhostSettingsCards.row(getContext(),"Проверить подключение",null,null,null,R.drawable.msg_info,this::checkConnection));items.add(UItem.asCustom(GhostSettingsCards.wrap(getContext(),diagnostics)));
        items.add(UItem.asShadow("Журналы содержат технические события и сведения об устройстве. Переписка, пароли и ключи не записываются. Перед отправкой проверьте содержимое файла."));
        LinearLayout statistics=GhostSettingsCards.group(getContext());statistics.addView(GhostSettingsCards.row(getContext(),"Статистика установок","Версия, модель устройства, активность и счётчики ошибок. Без переписки, номеров и файлов.",null,data.optBoolean("statistics",true),0,()->{GhostLicenseBridge.updateOption("statistics",!data.optBoolean("statistics",true));refresh();}));items.add(UItem.asCustom(GhostSettingsCards.wrap(getContext(),statistics)));
    }
    private void message(String title,String text){if(getParentActivity()!=null)new AlertDialog.Builder(getContext()).setTitle(title).setMessage(text).setPositiveButton("ОК",null).show();}
    private void export(){try{startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"AstraGram-diagnostics.txt"),EXPORT_LOG);}catch(Exception e){message("Диагностика","Не удалось открыть системное сохранение файла.");}}
    @Override public void onActivityResultFragment(int request,int result,Intent data){
        if(request!=EXPORT_LOG||result!=Activity.RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();ContentResolver resolver=getParentActivity().getContentResolver();String report=GhostDiagnosticLog.report();
        Utilities.globalQueue.postRunnable(()->{boolean saved=false;try(OutputStream out=resolver.openOutputStream(uri,"wt")){if(out!=null){out.write(report.getBytes(StandardCharsets.UTF_8));out.flush();saved=true;}}catch(Exception ignored){}boolean ok=saved;AndroidUtilities.runOnUIThread(()->message("Диагностика",ok?"Технический журнал сохранён.":"Не удалось сохранить журнал."));});
    }
    private void checkConnection(){
        AlertDialog wait=new AlertDialog(getContext(),3);wait.setCancelable(false);wait.show();
        Utilities.globalQueue.postRunnable(()->{
            boolean reachable=false;HttpsURLConnection c=null;
            try{c=(HttpsURLConnection)new URL(GhostServiceEndpoints.UPDATES+"/health").openConnection();c.setConnectTimeout(5000);c.setReadTimeout(5000);c.setInstanceFollowRedirects(false);reachable=c.getResponseCode()==200;}catch(Exception ignored){}finally{if(c!=null)c.disconnect();}
            GhostDiagnosticLog.record(reachable?"connection_ok":"connection_failed");boolean server=reachable;
            AndroidUtilities.runOnUIThread(()->{wait.dismiss();boolean telegram=UserConfig.isValidAccount(currentAccount)&&!GhostAccountSessions.disabled(currentAccount)&&ConnectionsManager.getInstance(currentAccount).getConnectionState()==ConnectionsManager.ConnectionStateConnected;
                message("Проверка подключения","Сеть: "+(ApplicationLoader.isNetworkOnline()?"доступна":"нет подключения")+"\nСервер AstraGram: "+(server?"доступен":"недоступен")+"\nTelegram: "+(telegram?"подключён":"нет активного соединения"));});
        });
    }
    @Override protected void onClick(UItem item,View view,int position,float x,float y){}
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y){return false;}
}
