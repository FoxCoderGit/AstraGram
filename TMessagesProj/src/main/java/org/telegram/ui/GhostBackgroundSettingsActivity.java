package org.telegram.ui;

import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import androidx.core.app.NotificationManagerCompat;
import java.util.ArrayList;
import org.telegram.messenger.*;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.*;

public final class GhostBackgroundSettingsActivity extends UniversalFragment {
    @Override protected String getTitle(){return "Работа в фоне";}
    @Override public void onResume(){super.onResume();GhostKeepAliveService.startIfEnabled();update();AndroidUtilities.runOnUIThread(this::update,600);}
    private void update(){if(listView!=null&&getParentActivity()!=null)listView.adapter.update(true);}
    private boolean battery(){return Build.VERSION.SDK_INT<23||((PowerManager)getContext().getSystemService(Context.POWER_SERVICE)).isIgnoringBatteryOptimizations(getContext().getPackageName());}
    private boolean notifications(){return NotificationManagerCompat.from(getContext()).areNotificationsEnabled();}
    @Override public void onRequestPermissionsResultFragment(int code,String[] permissions,int[] results){super.onRequestPermissionsResultFragment(code,permissions,results);if(code==GhostKeepAliveService.REQUEST_NOTIFICATIONS)GhostKeepAliveService.batteryPermission(getParentActivity());}
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter){
        boolean keep=GhostKeepAliveService.enabled(),service=GhostKeepAliveService.foregroundEnabled(),ready=keep&&service&&GhostKeepAliveService.isRunning()&&battery()&&notifications();
        items.add(UItem.asCustom(GhostSettingsCards.wrap(getContext(),GhostSettingsCards.status(getContext(),ready,ready?"Готово к работе в фоне":keep?"Проверьте фоновые настройки":"Фоновая работа выключена",ready?"Основные разрешения настроены":keep?"Проверьте службу и ограничения Android":"Включите поддержание соединения"))));
        items.add(UItem.asHeader("Подключение"));LinearLayout connection=GhostSettingsCards.group(getContext());
        connection.addView(GhostSettingsCards.row(getContext(),"Поддерживать соединение","Помогает получать сообщения в фоне.",null,keep,0,()->{GhostKeepAliveService.setEnabled(getParentActivity(),!keep);if(!keep&&service)GhostKeepAliveService.permissions(getParentActivity());update();}));
        GhostSettingsCards.line(connection);connection.addView(GhostSettingsCards.row(getContext(),"Служба в фоне","Показывает постоянное уведомление.",null,service,0,()->{GhostKeepAliveService.setForegroundEnabled(getParentActivity(),!service);if(!service&&keep)GhostKeepAliveService.permissions(getParentActivity());update();}));items.add(UItem.asCustom(GhostSettingsCards.wrap(getContext(),connection)));
        items.add(UItem.asHeader("Настройки Android"));LinearLayout android=GhostSettingsCards.group(getContext());
        android.addView(GhostSettingsCards.row(getContext(),"Работа без ограничений",null,battery()?"Разрешена":"Настроить",null,0,()->GhostKeepAliveService.batteryPermission(getParentActivity())));GhostSettingsCards.line(android);
        android.addView(GhostSettingsCards.row(getContext(),"Автозапуск",null,"Проверить",null,0,this::autostart));GhostSettingsCards.line(android);
        android.addView(GhostSettingsCards.row(getContext(),"Уведомления",null,notifications()?"Включены":"Выключены",null,0,this::notificationSettings));items.add(UItem.asCustom(GhostSettingsCards.wrap(getContext(),android)));
        items.add(UItem.asShadow("Системные параметры открываются в настройках Android. Доступ к проверке автозапуска зависит от прошивки."));
        LinearLayout check=GhostSettingsCards.group(getContext());check.addView(GhostSettingsCards.row(getContext(),"Проверить работу в фоне",null,null,null,R.drawable.msg_info,this::check));items.add(UItem.asCustom(GhostSettingsCards.wrap(getContext(),check)));
        items.add(UItem.asShadow("При задержке сообщений проверьте ограничения батареи и доступ к сети. Без службы соединение поддерживается, пока Android сохраняет процесс приложения."));
    }
    private void details(){try{getParentActivity().startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getContext().getPackageName())));}catch(Exception ignored){}}
    private void autostart(){
        String[][] choices={{"com.miui.securitycenter","com.miui.permcenter.autostart.AutoStartManagementActivity"},{"com.coloros.safecenter","com.coloros.safecenter.permission.startup.StartupAppListActivity"},{"com.vivo.permissionmanager","com.vivo.permissionmanager.activity.BgStartUpManagerActivity"}};
        for(String[] item:choices)try{Intent i=new Intent().setComponent(new ComponentName(item[0],item[1]));if(i.resolveActivity(getContext().getPackageManager())!=null){getParentActivity().startActivity(i);return;}}catch(Exception ignored){}details();
    }
    private void notificationSettings(){try{if(Build.VERSION.SDK_INT>=26)getParentActivity().startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getContext().getPackageName()));else details();}catch(Exception ignored){details();}}
    private void check(){boolean connected=UserConfig.isValidAccount(currentAccount)&&!GhostAccountSessions.disabled(currentAccount)&&ConnectionsManager.getInstance(currentAccount).getConnectionState()==ConnectionsManager.ConnectionStateConnected;new AlertDialog.Builder(getContext()).setTitle("Проверка фоновой работы").setMessage("Сеть: "+(ApplicationLoader.isNetworkOnline()?"доступна":"нет подключения")+"\nTelegram: "+(connected?"подключён":"нет активного соединения")+"\nСлужба: "+(GhostKeepAliveService.isRunning()?"работает":"остановлена")+"\nЭкономия батареи: "+(battery()?"без ограничений":"есть ограничения")+"\nУведомления: "+(notifications()?"включены":"выключены")).setPositiveButton("ОК",null).show();}
    @Override protected void onClick(UItem item,View view,int position,float x,float y){}
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y){return false;}
}
