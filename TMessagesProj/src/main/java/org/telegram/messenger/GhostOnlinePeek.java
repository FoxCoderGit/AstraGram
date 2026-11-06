package org.telegram.messenger;

import android.content.SharedPreferences;
import android.util.Base64;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.text.SimpleDateFormat;
import java.util.Locale;
import org.telegram.tgnet.*;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

/** Temporarily shares the current account's status; never bypasses the other user's privacy. */
public final class GhostOnlinePeek {
    private static final String SNAPSHOT="ghost_online_peek_restore",OWNER="ghost_online_peek_owner";
    private static final Task[] tasks=new Task[UserConfig.MAX_ACCOUNT_COUNT];
    public static boolean available(int account,TLRPC.User user){return user!=null&&!user.bot&&!user.deleted&&!UserObject.isUserSelf(user)&&!UserConfig.getInstance(account).isPremium()&&user.status!=null&&user.status.by_me;}
    public static void show(BaseFragment fragment,TLRPC.User user){
        int account=fragment.getCurrentAccount();if(!available(account,user)||fragment.getParentActivity()==null)return;
        if(tasks[account]!=null||prefs(account).contains(SNAPSHOT)){recover(account);notice(fragment,"Предыдущие настройки конфиденциальности ещё восстанавливаются. Повторите позже.");return;}
        Task task=new Task(account,fragment,user.id);tasks[account]=task;task.start();
    }
    private static SharedPreferences prefs(int account){return ApplicationLoader.applicationContext.getSharedPreferences("ghost_online_peek_"+UserConfig.getInstance(account).getClientUserId(),android.content.Context.MODE_PRIVATE);}
    private static void notice(BaseFragment f,String text){if(f!=null&&f.getParentActivity()!=null)f.showDialog(new AlertDialog.Builder(f.getParentActivity(),f.getResourceProvider()).setTitle("Подсмотреть онлайн").setMessage(text).setPositiveButton("Понятно",null).create());}
    public static void recoverAll(){for(int a=0;a<tasks.length;a++)if(UserConfig.getInstance(a).isClientActivated())recover(a);}
    private static void recover(int account){
        if(tasks[account]!=null||!prefs(account).contains(SNAPSHOT)||prefs(account).getLong(OWNER,0)!=UserConfig.getInstance(account).getClientUserId())return;
        Task task=new Task(account,null,0);tasks[account]=task;task.finish(null);
    }
    static ArrayList<TLRPC.InputPrivacyRule> copyRules(int account,ArrayList<TLRPC.PrivacyRule> rules)throws Exception{
        ArrayList<TLRPC.InputPrivacyRule> result=new ArrayList<>();
        for(TLRPC.PrivacyRule rule:rules){
            String name=rule.getClass().getSimpleName().replace("TL_privacyValue","");
            if(!Arrays.asList("AllowAll","DisallowAll","AllowContacts","DisallowContacts","AllowCloseFriends","AllowPremium","AllowBots","DisallowBots","AllowUsers","DisallowUsers","AllowChatParticipants","DisallowChatParticipants").contains(name))throw new IllegalStateException("Неизвестное правило конфиденциальности");
            TLRPC.InputPrivacyRule input=(TLRPC.InputPrivacyRule)Class.forName("org.telegram.tgnet.TLRPC$TL_inputPrivacyValue"+name).getDeclaredConstructor().newInstance();
            if(name.endsWith("Users")){
                @SuppressWarnings("unchecked") ArrayList<Long> ids=(ArrayList<Long>)rule.getClass().getField("users").get(rule);
                @SuppressWarnings("unchecked") ArrayList<TLRPC.InputUser> users=(ArrayList<TLRPC.InputUser>)input.getClass().getField("users").get(input);
                for(long id:ids){TLRPC.InputUser u=MessagesController.getInstance(account).getInputUser(id);if(!(u instanceof TLRPC.TL_inputUserSelf)&&(!(u instanceof TLRPC.TL_inputUser)||((TLRPC.TL_inputUser)u).access_hash==0))throw new IllegalStateException("Не удалось сохранить исключения конфиденциальности");users.add(u);}
            }else if(name.endsWith("ChatParticipants")){
                @SuppressWarnings("unchecked") ArrayList<Long> ids=(ArrayList<Long>)rule.getClass().getField("chats").get(rule);
                @SuppressWarnings("unchecked") ArrayList<Long> chats=(ArrayList<Long>)input.getClass().getField("chats").get(input);chats.addAll(ids);
            }
            result.add(input);
        }
        if(result.isEmpty())throw new IllegalStateException("Пустые настройки конфиденциальности");return result;
    }
    static String encode(ArrayList<TLRPC.InputPrivacyRule> rules){SerializedData data=new SerializedData();try{data.writeInt32(rules.size());for(TLRPC.InputPrivacyRule rule:rules)rule.serializeToStream(data);return Base64.encodeToString(data.toByteArray(),Base64.NO_WRAP);}finally{data.cleanup();}}
    static ArrayList<TLRPC.InputPrivacyRule> decode(String saved){SerializedData data=new SerializedData(Base64.decode(saved,Base64.DEFAULT));try{int count=data.readInt32(true);if(count<1||count>10000)throw new IllegalStateException("Неверная резервная копия правил");ArrayList<TLRPC.InputPrivacyRule> rules=new ArrayList<>();for(int i=0;i<count;i++)rules.add(TLRPC.InputPrivacyRule.TLdeserialize(data,data.readInt32(true),true));return rules;}finally{data.cleanup();}}
    private static final class Task {
        final int account;final long owner,target;final BaseFragment fragment;AlertDialog progress;
        boolean restoring,done,restored;String result;int request;
        Task(int account,BaseFragment fragment,long target){this.account=account;this.fragment=fragment;this.target=target;owner=UserConfig.getInstance(account).getClientUserId();}
        boolean sameAccount(){return owner!=0&&owner==UserConfig.getInstance(account).getClientUserId();}
        void start(){
            progress=new AlertDialog(fragment.getParentActivity(),3);progress.setCanCancel(true);progress.setOnCancelListener(d->finish("Проверка отменена."));fragment.showDialog(progress);
            TL_account.getPrivacy get=new TL_account.getPrivacy();get.key=new TLRPC.TL_inputPrivacyKeyStatusTimestamp();
            request=ConnectionsManager.getInstance(account).sendRequest(get,(response,error)->AndroidUtilities.runOnUIThread(()->{
                if(done||restoring)return;
                if(error!=null||!(response instanceof TL_account.privacyRules)||!sameAccount()){finish("Не удалось получить исходные настройки. Они не изменены.");return;}
                TL_account.privacyRules privacy=(TL_account.privacyRules)response;MessagesController.getInstance(account).putUsers(privacy.users,false);
                try{
                    String saved=encode(copyRules(account,privacy.rules));if(!prefs(account).edit().putString(SNAPSHOT,saved).putLong(OWNER,owner).commit())throw new IllegalStateException("Не удалось записать резервную копию");
                    TL_account.setPrivacy set=new TL_account.setPrivacy();set.key=new TLRPC.TL_inputPrivacyKeyStatusTimestamp();set.rules.add(new TLRPC.TL_inputPrivacyValueAllowAll());
                    request=ConnectionsManager.getInstance(account).sendRequest(set,(opened,failed)->AndroidUtilities.runOnUIThread(()->{if(done||restoring)return;if(failed!=null){finish("Не удалось временно открыть время захода.");return;}fetch();}),ConnectionsManager.RequestFlagInvokeAfter|ConnectionsManager.RequestFlagFailOnServerErrors);
                }catch(Exception failure){finish("Не удалось сохранить исходные настройки. Проверка остановлена.");}
            }));
            AndroidUtilities.runOnUIThread(()->{if(!done&&!restoring)finish("Проверка заняла слишком много времени.");},15000);
        }
        void fetch(){
            if(!sameAccount()){finish("Аккаунт был переключён.");return;}
            TLRPC.TL_users_getUsers get=new TLRPC.TL_users_getUsers();get.id.add(MessagesController.getInstance(account).getInputUser(target));
            request=ConnectionsManager.getInstance(account).sendRequest(get,(response,error)->AndroidUtilities.runOnUIThread(()->{
                if(done||restoring)return;
                if(error!=null||!(response instanceof org.telegram.tgnet.Vector)){finish("Не удалось обновить статус пользователя.");return;}
                org.telegram.tgnet.Vector vector=(org.telegram.tgnet.Vector)response;TLRPC.User user=null;for(Object item:vector.objects)if(item instanceof TLRPC.User&&((TLRPC.User)item).id==target)user=(TLRPC.User)item;
                if(user==null){finish("Статус пользователя недоступен.");return;}
                if(user.status instanceof TLRPC.TL_userStatusOnline)finish("Сейчас в сети.");
                else if(user.status instanceof TLRPC.TL_userStatusOffline&&user.status.expires>0)finish("Последний заход: "+new SimpleDateFormat("dd.MM.yyyy HH:mm:ss",Locale.getDefault()).format(new Date(user.status.expires*1000L)));
                else finish("Точное время скрыто настройками собеседника и недоступно Telegram.");
            }));
        }
        void finish(String message){
            if(done||restoring)return;restoring=true;result=message;
            if(progress!=null){progress.dismiss();progress=null;}
            if(!prefs(account).contains(SNAPSHOT)){complete();return;}restore();
            AndroidUtilities.runOnUIThread(()->{if(!done&&sameAccount()&&prefs(account).contains(SNAPSHOT))notice(fragment,"Восстановление ещё не подтверждено. До появления связи ваше время захода может оставаться открытым. Исходные правила сохранены, приложение продолжит восстановление автоматически.");},10000);
        }
        void restore(){
            if(done||!sameAccount()){tasks[account]=null;return;}
            try{
                if(prefs(account).getLong(OWNER,0)!=owner){tasks[account]=null;return;}
                TL_account.setPrivacy set=new TL_account.setPrivacy();set.key=new TLRPC.TL_inputPrivacyKeyStatusTimestamp();set.rules=decode(prefs(account).getString(SNAPSHOT,""));
                ConnectionsManager.getInstance(account).sendRequest(set,(response,error)->AndroidUtilities.runOnUIThread(()->{
                    if(!sameAccount()){tasks[account]=null;return;}
                    if(error==null&&response instanceof TL_account.privacyRules){
                        TL_account.privacyRules privacy=(TL_account.privacyRules)response;ContactsController.getInstance(account).setPrivacyRules(privacy.rules,ContactsController.PRIVACY_RULES_TYPE_LASTSEEN);
                        if(prefs(account).edit().remove(SNAPSHOT).remove(OWNER).commit()){restored=true;complete();return;}
                    }
                    if(result!=null){notice(fragment,"Не удалось подтвердить восстановление настроек. Исходные правила сохранены; восстановление будет повторено автоматически.");result=null;}
                    long retry=15000;if(error!=null&&error.text!=null&&error.text.startsWith("FLOOD_WAIT_"))try{retry=Math.max(retry,Long.parseLong(error.text.substring(11))*1000L);}catch(Exception ignored){}AndroidUtilities.runOnUIThread(this::restore,retry);
                }),ConnectionsManager.RequestFlagInvokeAfter|ConnectionsManager.RequestFlagFailOnServerErrors);
            }catch(Exception failure){notice(fragment,"Не удалось прочитать резервную копию настроек. Проверьте раздел «Время захода».");tasks[account]=null;}
        }
        void complete(){done=true;tasks[account]=null;if(result!=null)notice(fragment,result+(restored?"\n\nПрежние настройки конфиденциальности восстановлены.":""));}
    }
    private GhostOnlinePeek(){}
}
