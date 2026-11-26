package org.telegram.ui;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;
import org.telegram.messenger.*;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.*;

public final class GhostUpdateSettingsActivity extends UniversalFragment {
    private static final int EXPORT_LOG=7089;
    private static final int SAVE_SETTINGS=7090, OPEN_SETTINGS=7091;
    private byte[] pendingSettings;
    private boolean resumed;
    private String lastInfo="";
    private final Runnable poll=new Runnable(){public void run(){if(!resumed)return;String info=GhostLicenseBridge.updateInfo();if(!info.equals(lastInfo)){lastInfo=info;refresh();}AndroidUtilities.runOnUIThread(this,2000);}};
    private static JSONObject info(){try{return new JSONObject(GhostLicenseBridge.updateInfo());}catch(Exception e){return new JSONObject();}}
    public static String summary(){String status=info().optString("status");return "current".equals(status)?"Установлена последняя версия":"available".equals(status)?"Доступна новая версия":"error".equals(status)?"Не удалось проверить обновления":"Проверка обновлений и журналы";}
    @Override protected String getTitle(){return "Обновления и диагностика";}
    @Override public void onResume(){super.onResume();resumed=true;AndroidUtilities.runOnUIThread(poll);}
    @Override public void onPause(){resumed=false;AndroidUtilities.cancelRunOnUIThread(poll);super.onPause();}
    @Override public void onFragmentDestroy(){resumed=false;AndroidUtilities.cancelRunOnUIThread(poll);clearSettingsExport();super.onFragmentDestroy();}
    private void refresh(){if(listView!=null&&getParentActivity()!=null)listView.adapter.update(true);}
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter){
        JSONObject data=info();String status=data.optString("status","unknown");boolean current="current".equals(status);
        items.add(UItem.asCustom(GhostSettingsCards.brand(getContext(),GhostDiagnosticLog.version())));
        LinearLayout check=(LinearLayout)GhostSettingsCards.status(getContext(),current,current?"Установлена последняя версия":"available".equals(status)?"Доступна новая версия":"error".equals(status)?"Не удалось проверить обновления":"Версия пока не проверена",data.optString("error","").isEmpty()?null:data.optString("error"));
        check.addView(GhostSettingsCards.button(getContext(),"Проверить обновления",()->GhostLicenseBridge.checkUpdates(getParentActivity())));items.add(UItem.asCustom(GhostSettingsCards.wrap(getContext(),check)));
        items.add(UItem.asHeader("Обновления"));LinearLayout updates=GhostSettingsCards.group(getContext());
        updates.addView(GhostSettingsCards.row(getContext(),"Проверять автоматически",null,null,data.optBoolean("automatic",true),0,()->{GhostLicenseBridge.updateOption("automatic_updates",!data.optBoolean("automatic",true));refresh();}));GhostSettingsCards.line(updates);
        JSONObject release=data.optJSONObject("release");
        updates.addView(GhostSettingsCards.row(getContext(),"Список изменений",null,null,null,0,()->GhostLicenseBridge.showChangelog(getParentActivity())));
        if(release!=null&&"available".equals(status)&&!release.optBoolean("mandatory",false)){
            GhostSettingsCards.line(updates);
            boolean skipped=data.optLong("skipped_code")==release.optLong("version_code");
            updates.addView(GhostSettingsCards.row(getContext(),"Пропустить эту версию","Проверка вручную остаётся доступна",null,skipped,0,()->{GhostLicenseBridge.updateOption("skip_update",!skipped);refresh();}));
        }
        items.add(UItem.asCustom(GhostSettingsCards.wrap(getContext(),updates)));
        items.add(UItem.asHeader("Резервная копия настроек"));
        LinearLayout backup = GhostSettingsCards.group(getContext());
        backup.addView(GhostSettingsCards.row(getContext(), "Сохранить настройки", "Зашифрованный файл", null, null, 0, () -> settingsPassword(null)));
        GhostSettingsCards.line(backup);
        backup.addView(GhostSettingsCards.row(getContext(), "Восстановить настройки", null, null, null, 0,
                () -> chooseSettingsFile(Intent.ACTION_OPEN_DOCUMENT, OPEN_SETTINGS)));
        items.add(UItem.asCustom(GhostSettingsCards.wrap(getContext(), backup)));
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
        if (request == SAVE_SETTINGS || request == OPEN_SETTINGS) {
            byte[] export = request == SAVE_SETTINGS ? pendingSettings : null;
            if (request == SAVE_SETTINGS) pendingSettings = null;
            Activity activity = getParentActivity();
            if (activity == null || result != Activity.RESULT_OK || data == null || data.getData() == null) {
                if (export != null) Arrays.fill(export, (byte) 0);
                return;
            }
            transferSettings(activity.getContentResolver(), data.getData(), request, export);
            return;
        }
        if(request!=EXPORT_LOG||result!=Activity.RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();ContentResolver resolver=getParentActivity().getContentResolver();String report=GhostDiagnosticLog.report();
        Utilities.globalQueue.postRunnable(()->{boolean saved=false;try(OutputStream out=resolver.openOutputStream(uri,"wt")){if(out!=null){out.write(report.getBytes(StandardCharsets.UTF_8));out.flush();saved=true;}}catch(Exception ignored){}boolean ok=saved;AndroidUtilities.runOnUIThread(()->message("Диагностика",ok?"Технический журнал сохранён.":"Не удалось сохранить журнал."));});
    }

    private void clearSettingsExport() {
        if (pendingSettings != null) Arrays.fill(pendingSettings, (byte) 0);
        pendingSettings = null;
    }

    private void chooseSettingsFile(String action, int request) {
        try {
            Intent intent = new Intent(action).addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(request == SAVE_SETTINGS ? "application/octet-stream" : "*/*");
            if (request == SAVE_SETTINGS) intent.putExtra(Intent.EXTRA_TITLE, "AstraGram-settings.astrasettings");
            startActivityForResult(intent, request);
        } catch (Exception error) {
            if (request == SAVE_SETTINGS) clearSettingsExport();
            message("Резервная копия настроек", "Не удалось открыть выбор файла.");
        }
    }

    private void transferSettings(ContentResolver resolver, Uri uri, int request, byte[] export) {
        Utilities.globalQueue.postRunnable(() -> {
            byte[] imported = null;
            boolean ok = false;
            try {
                if (request == SAVE_SETTINGS && export != null) {
                    try (OutputStream stream = resolver.openOutputStream(uri, "wt")) {
                        if (stream == null) throw new IOException();
                        stream.write(export);
                    }
                    ok = true;
                } else if (request == OPEN_SETTINGS) {
                    try (InputStream stream = resolver.openInputStream(uri)) {
                        if (stream == null) throw new IOException();
                        imported = GhostSessionCrypto.readBounded(stream);
                    }
                    ok = true;
                }
            } catch (Exception ignored) {
            } finally {
                if (export != null) Arrays.fill(export, (byte) 0);
            }
            boolean success = ok;
            byte[] bytes = imported;
            AndroidUtilities.runOnUIThread(() -> {
                if (success && request == OPEN_SETTINGS) settingsPassword(bytes);
                else message("Резервная копия настроек", success ? "Настройки сохранены." : "Не удалось прочитать или записать файл настроек.");
            });
        });
    }

    private void settingsPassword(byte[] imported) {
        if (getParentActivity() == null) {
            if (imported != null) Arrays.fill(imported, (byte) 0);
            return;
        }
        boolean saving = imported == null;
        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), AndroidUtilities.dp(8));
        EditTextBoldCursor field = backupPasswordField(saving ? "Пароль, от 12 символов" : "Пароль копии");
        EditTextBoldCursor confirmation = saving ? backupPasswordField("Повтори пароль") : null;
        content.addView(field, LayoutHelper.createLinear(-1, 48));
        if (confirmation != null) content.addView(confirmation, LayoutHelper.createLinear(-1, 48));
        AlertDialog dialog = new AlertDialog.Builder(getContext()).setTitle(saving ? "Сохранить настройки" : "Восстановить настройки")
                .setView(content).setPositiveButton(saving ? "Продолжить" : "Восстановить", (d, w) -> {})
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null).create();
        boolean[] submitted = {false};
        dialog.setOnDismissListener(d -> {
            field.setText("");
            if (confirmation != null) confirmation.setText("");
            if (!submitted[0] && imported != null) Arrays.fill(imported, (byte) 0);
        });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (field.length() < (saving ? 12 : 1) || field.length() > 256) {
                field.setError(saving ? "От 12 до 256 символов" : "Введите пароль");
                return;
            }
            if (confirmation != null && !TextUtils.equals(field.getText(), confirmation.getText())) {
                confirmation.setError("Пароли не совпадают");
                return;
            }
            char[] password = new char[field.length()];
            TextUtils.getChars(field.getText(), 0, field.length(), password, 0);
            submitted[0] = true;
            dialog.dismiss();
            if (saving) AstraSettingsBackup.encrypt(password, (bytes, error) -> {
                if (error != null) { message("Резервная копия настроек", "Не удалось создать копию."); return; }
                if (getParentActivity() == null) { Arrays.fill(bytes, (byte) 0); return; }
                clearSettingsExport();
                pendingSettings = bytes;
                chooseSettingsFile(Intent.ACTION_CREATE_DOCUMENT, SAVE_SETTINGS);
            });
            else AstraSettingsBackup.restore(imported, password, (count, error) -> {
                refresh();
                message("Резервная копия настроек", error == null
                        ? "Настройки восстановлены. Перезапусти AstraGram, чтобы применить все изменения."
                        : error instanceof javax.crypto.AEADBadTagException ? "Неверный пароль или файл повреждён." : "Не удалось восстановить настройки из этого файла.");
            });
        });
    }

    private EditTextBoldCursor backupPasswordField(String hint) {
        EditTextBoldCursor field = new EditTextBoldCursor(getContext());
        field.setHint(hint);
        field.setSingleLine(true);
        field.setTextColor(getThemedColor(org.telegram.ui.ActionBar.Theme.key_dialogTextBlack));
        field.setHintTextColor(getThemedColor(org.telegram.ui.ActionBar.Theme.key_dialogTextHint));
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        field.setSaveEnabled(false);
        return field;
    }
    private void checkConnection(){
        AlertDialog wait=new AlertDialog(getContext(),3);wait.setCancelable(false);wait.show();
        Utilities.globalQueue.postRunnable(()->{
            boolean reachable=false;HttpsURLConnection c=null;
            try{c=(HttpsURLConnection)new URL(GhostServiceEndpoints.UPDATES+"/health").openConnection();c.setConnectTimeout(5000);c.setReadTimeout(5000);c.setInstanceFollowRedirects(false);reachable=c.getResponseCode()==200;}catch(Exception ignored){}finally{if(c!=null)c.disconnect();}
            GhostDiagnosticLog.record(reachable?"connection_ok":"connection_failed");boolean server=reachable;
            String relay = AstraRelay.checkReachability();
            AndroidUtilities.runOnUIThread(()->{wait.dismiss();boolean telegram=UserConfig.isValidAccount(currentAccount)&&!GhostAccountSessions.disabled(currentAccount)&&ConnectionsManager.getInstance(currentAccount).getConnectionState()==ConnectionsManager.ConnectionStateConnected;
                message("Проверка подключения","Сеть: "+(ApplicationLoader.isNetworkOnline()?"доступна":"нет подключения")+"\nСервер AstraGram: "+(server?"доступен":"недоступен")+"\nTelegram: "+(telegram?"подключён":"нет активного соединения")+"\nAstraRelay: "+relay);});
        });
    }
    @Override protected void onClick(UItem item,View view,int position,float x,float y){}
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y){return false;}
}
