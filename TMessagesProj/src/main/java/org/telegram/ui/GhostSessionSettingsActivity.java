package org.telegram.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import java.io.*;
import java.util.*;
import org.telegram.messenger.*;
import org.telegram.ui.ActionBar.*;
import org.telegram.ui.Components.*;
import org.telegram.tgnet.TLRPC;

/** Telegram-native cells and SAF only; passwords and decrypted files never enter saved state. */
public final class GhostSessionSettingsActivity extends UniversalFragment {
    private static final int OPEN=7087,SAVE=7088;
    private final HashSet<Integer> selected=new HashSet<>(),restore=new HashSet<>();
    private boolean selecting,autoImport,destroyed;
    private byte[] encryptedExport;
    private List<GhostSessionBundle.Entry> imported;
    public GhostSessionSettingsActivity(){this(false);}
    public GhostSessionSettingsActivity(boolean autoImport){this.autoImport=autoImport;}
    @Override protected String getTitle(){return "Резервные копии сессий";}
    @Override public void onResume(){super.onResume();refresh();if(autoImport){autoImport=false;AndroidUtilities.runOnUIThread(this::pickImport,300);}}
    private void refresh(){if(!destroyed&&listView!=null)listView.adapter.update(true);}
    private List<Integer> accounts(boolean usersOnly){
        ArrayList<Integer> result=new ArrayList<>();
        for(int i=0;i<UserConfig.MAX_ACCOUNT_COUNT;i++)if(UserConfig.isValidAccount(i)){
            TLRPC.User u=UserConfig.getInstance(i).getCurrentUser();if(u!=null&&(!usersOnly||!u.bot)&&!u.deleted)result.add(i);
        }return result;
    }
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter){
        if(imported!=null){
            items.add(UItem.asHeader("Аккаунты из резервной копии"));
            items.add(UItem.asButton(13,"Выбрать все"));
            for(int i=0;i<imported.size();i++)try{items.add(UItem.asCheck(4000+i,UserObject.getUserName(GhostSessions.user(imported.get(i)))).setChecked(restore.contains(i)));}catch(IOException ignored){}
            items.add(UItem.asButton(14,"Восстановить выбранные · "+restore.size()));items.add(UItem.asButton(15,"Отмена"));
            items.add(UItem.asShadow("Восстановленные аккаунты будут отключены от сети. Существующие аккаунты не перезаписываются."));return;
        }
        items.add(UItem.asButton(1,R.drawable.msg_download,"Создать резервную копию"));
        items.add(UItem.asButton(2,R.drawable.msg_openin,"Импортировать из файла"));
        items.add(UItem.asShadow("Только авторизация пользовательских аккаунтов. История, медиа и секретные чаты в файл не входят. Копия шифруется вашим паролем и не передаётся на сервер AstraGram."));
        if(selecting){
            items.add(UItem.asHeader("Аккаунты для экспорта"));items.add(UItem.asButton(3,"Выбрать все"));
            for(int a:accounts(true))items.add(UItem.asCheck(1000+a,UserObject.getUserName(UserConfig.getInstance(a).getCurrentUser())).setChecked(selected.contains(a)));
            items.add(UItem.asButton(4,"Продолжить · "+selected.size()));items.add(UItem.asShadow(null));
        }
        for(int a:accounts(false)){
            items.add(UItem.asHeader(UserObject.getUserName(UserConfig.getInstance(a).getCurrentUser())));
            items.add(UItem.asCheck(2000+a,"Отключить на этом устройстве").setChecked(GhostAccountSessions.disabled(a)));
            if(GhostAccountSessions.pending(a))items.add(UItem.asShadow("Восстановление не завершено. Аккаунт отключён; перезапустите приложение для завершения отката."));
            else if(!GhostAccountSessions.error(a).isEmpty())items.add(UItem.asShadow("Сессия больше не действительна. Нужен новый вход в аккаунт; резервная копия отозванной сессии не поможет."));
            else if(GhostAccountSessions.firstConnection(a))items.add(UItem.asShadow("Импортирован и отключён. Перед подключением отключите исходную копию этой сессии."));
            else items.add(UItem.asShadow(GhostAccountSessions.disabled(a)?"Соединения закрыты. Данные и авторизация сохранены локально.":"Аккаунт может подключаться к Telegram."));
            if(!GhostAccountSessions.disabled(a))items.add(UItem.asButton(3000+a,"Открыть аккаунт"));
        }
    }
    @Override protected void onClick(UItem item,View view,int position,float x,float y){
        int id=item.id;
        if(id==1){selecting=!selecting;refresh();}
        else if(id==2)pickImport();
        else if(id==3){List<Integer> all=accounts(true);if(selected.containsAll(all))selected.clear();else selected.addAll(all);refresh();}
        else if(id==4){
            selected.retainAll(accounts(true));if(selected.isEmpty()){message("Выберите хотя бы один пользовательский аккаунт.");return;}
            new AlertDialog.Builder(getContext()).setTitle("Экспорт сессий").setMessage(GhostSessions.WARNING).setPositiveButton("Продолжить",(d,w)->password(true,null)).setNegativeButton("Отмена",null).show();
        }else if(id>=1000&&id<1000+UserConfig.MAX_ACCOUNT_COUNT){int a=id-1000;if(!selected.add(a))selected.remove(a);refresh();}
        else if(id>=2000&&id<2000+UserConfig.MAX_ACCOUNT_COUNT){
            int a=id-2000;
            if(!GhostAccountSessions.disabled(a)){
                AlertDialog progress=progress("Отключение аккаунта…");GhostAccountSessions.disconnect(a,error->{progress.dismiss();refresh();if(error!=null)message(error);});
            }else{
                new AlertDialog.Builder(getContext()).setTitle("Подключить аккаунт?").setMessage(GhostSessions.WARNING+"\n\nУбедитесь, что исходная копия отключена. Локальный переключатель не может проверить другое устройство.")
                    .setPositiveButton("Исходная копия отключена",(d,w)->{AlertDialog progress=progress("Проверка авторизации…");GhostAccountSessions.connect(a,error->{progress.dismiss();refresh();message(error==null?"Авторизация подтверждена. Облачные данные синхронизируются.":error);});}).setNegativeButton("Отмена",null).show();
            }
        }else if(id>=3000&&id<3000+UserConfig.MAX_ACCOUNT_COUNT)openAccount(id-3000);
        else if(id==13){if(restore.size()==imported.size())restore.clear();else for(int i=0;i<imported.size();i++)restore.add(i);refresh();}
        else if(id==14)applyImport();
        else if(id==15){clearImport();refresh();}
        else if(id>=4000&&imported!=null&&id<4000+imported.size()){int i=id-4000;if(!restore.add(i))restore.remove(i);refresh();}
    }
    @Override protected boolean onLongClick(UItem item,View v,int position,float x,float y){return false;}
    private void message(String text){if(!destroyed&&getParentActivity()!=null)new AlertDialog.Builder(getContext()).setTitle("Сессии AstraGram").setMessage(text).setPositiveButton("ОК",null).show();}
    private AlertDialog progress(String text){AlertDialog d=new AlertDialog(getContext(),3);d.setMessage(text);d.setCancelable(false);d.setCanceledOnTouchOutside(false);d.show();return d;}
    private void pickImport(){
        if(destroyed)return;clearImport();
        try{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,OPEN);}catch(Exception e){message("Не удалось открыть системный выбор файла.");}
    }
    private char[] chars(EditTextBoldCursor edit){char[] result=new char[edit.length()];android.text.TextUtils.getChars(edit.getText(),0,edit.length(),result,0);return result;}
    private EditTextBoldCursor field(String hint){
        EditTextBoldCursor e=new EditTextBoldCursor(getContext());e.setTextSize(16);e.setSingleLine(true);e.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));e.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);e.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING|EditorInfo.IME_ACTION_DONE);e.setSaveEnabled(false);e.setBackgroundDrawable(Theme.createEditTextDrawable(getContext(),true));
        if(Build.VERSION.SDK_INT>=26)e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        return e;
    }
    private void password(boolean export,byte[] file){
        if(destroyed){if(file!=null)Arrays.fill(file,(byte)0);return;}
        LinearLayout box=new LinearLayout(getContext());box.setOrientation(LinearLayout.VERTICAL);box.setPadding(AndroidUtilities.dp(24),0,AndroidUtilities.dp(24),AndroidUtilities.dp(8));
        EditTextBoldCursor first=field(export?"Пароль · не менее 12 символов":"Пароль резервной копии");box.addView(first,LayoutHelper.createLinear(-1,48));
        EditTextBoldCursor second=export?field("Повторите пароль"):null;if(second!=null)box.addView(second,LayoutHelper.createLinear(-1,48));
        AlertDialog dialog=new AlertDialog.Builder(getContext()).setTitle(export?"Шифрование резервной копии":"Импорт сессий").setView(box).setPositiveButton("Продолжить",(d,w)->{}).setNegativeButton("Отмена",null).create();
        boolean[] consumed={false};dialog.setOnDismissListener(d->{first.setText("");if(second!=null)second.setText("");if(!consumed[0]&&file!=null)Arrays.fill(file,(byte)0);});dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            char[] pass=chars(first),repeat=second==null?null:chars(second);
            if(pass.length<(export?12:1)||pass.length>256){Arrays.fill(pass,'\0');if(repeat!=null)Arrays.fill(repeat,'\0');first.setError(export?"От 12 до 256 символов":"Введите пароль");return;}
            if(repeat!=null&&!Arrays.equals(pass,repeat)){Arrays.fill(pass,'\0');Arrays.fill(repeat,'\0');second.setError("Пароли не совпадают");return;}
            if(repeat!=null)Arrays.fill(repeat,'\0');consumed[0]=true;dialog.dismiss();
            AlertDialog wait=progress(export?"Шифрование сессий…":"Проверка резервной копии…");
            if(export)GhostSessions.exportAccounts(new ArrayList<>(selected),pass,(encrypted,error)->{
                wait.dismiss();if(error!=null){message(error);return;}if(destroyed){Arrays.fill(encrypted,(byte)0);return;}encryptedExport=encrypted;
                try{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"AstraGram-sessions.ghostsession");startActivityForResult(i,SAVE);}catch(Exception e){Arrays.fill(encryptedExport,(byte)0);encryptedExport=null;message("Не удалось открыть системное сохранение файла.");}
            });
            else GhostSessions.decrypt(file,pass,(entries,error)->{wait.dismiss();if(error!=null){message(error);return;}if(destroyed){for(GhostSessionBundle.Entry e:entries)e.close();return;}imported=entries;restore.clear();refresh();});
        });
    }
    private void applyImport(){
        if(restore.isEmpty()){message("Выберите аккаунты для восстановления.");return;}
        ArrayList<GhostSessionBundle.Entry> chosen=new ArrayList<>();for(int i:restore)chosen.add(imported.get(i));
        AlertDialog wait=progress("Восстановление отключённых сессий…");GhostSessions.importAccounts(chosen,(slots,error)->{wait.dismiss();clearImport();refresh();message(error==null?"Восстановлено аккаунтов: "+slots.size()+". Все они отключены от сети. Перед подключением отключите исходную копию.":error);});
    }
    private void openAccount(int account){
        if(GhostAccountSessions.disabled(account))return;
        if(LaunchActivity.instance!=null&&UserConfig.selectedAccount!=account){LaunchActivity.instance.switchToAccount(account,true);return;}
        INavigationLayout layout=getParentLayout();BaseFragment root=GhostNavigation.root(null);root.setCurrentAccount(account);layout.removeAllFragments();layout.addFragmentToStack(root,INavigationLayout.FORCE_ATTACH_VIEW_AS_FIRST);layout.rebuildFragments(INavigationLayout.REBUILD_FLAG_REBUILD_LAST);
    }
    private void clearImport(){if(imported!=null){for(GhostSessionBundle.Entry e:imported)e.close();imported=null;}restore.clear();}
    @Override public void onActivityResultFragment(int requestCode,int resultCode,Intent data){
        if(requestCode==SAVE){
            byte[] bytes=encryptedExport;encryptedExport=null;
            if(resultCode!=Activity.RESULT_OK||data==null||data.getData()==null){if(bytes!=null)Arrays.fill(bytes,(byte)0);return;}
            if(bytes==null){message("Повторите создание резервной копии: приложение было перезапущено.");return;}
            Uri target=data.getData();AlertDialog wait=progress("Сохранение резервной копии…");
            android.content.ContentResolver resolver=getParentActivity().getContentResolver();
            GhostSessions.queue.postRunnable(()->{String error=null;try(OutputStream out=resolver.openOutputStream(target,"wt")){if(out==null)throw new IOException();out.write(bytes);out.flush();}catch(Exception e){error="Не удалось полностью сохранить файл. Выберите другое расположение и повторите экспорт.";}finally{Arrays.fill(bytes,(byte)0);}String failure=error;AndroidUtilities.runOnUIThread(()->{wait.dismiss();message(failure==null?"Зашифрованная резервная копия сохранена.":failure);});});
        }else if(requestCode==OPEN&&resultCode==Activity.RESULT_OK&&data!=null&&data.getData()!=null){
            Uri source=data.getData();android.content.ContentResolver resolver=getParentActivity().getContentResolver();AlertDialog wait=progress("Чтение резервной копии…");
            GhostSessions.queue.postRunnable(()->{byte[] file=null;String error=null;try(InputStream in=resolver.openInputStream(source)){if(in==null)throw new IOException();file=GhostSessionCrypto.readBounded(in);}catch(Exception e){error="Не удалось прочитать файл. Выберите корректный файл .ghostsession размером до 8 МБ.";}byte[] bytes=file;String failure=error;AndroidUtilities.runOnUIThread(()->{wait.dismiss();if(failure!=null)message(failure);else password(false,bytes);});});
        }
    }
    @Override public void onFragmentDestroy(){destroyed=true;clearImport();if(encryptedExport!=null)Arrays.fill(encryptedExport,(byte)0);super.onFragmentDestroy();}
}
