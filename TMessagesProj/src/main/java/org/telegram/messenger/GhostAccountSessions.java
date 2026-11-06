package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.telegram.tgnet.*;

/** Local connection switch. Never sends auth.logOut or revokes an authorization key. */
public final class GhostAccountSessions {
    private static final ConcurrentHashMap<Integer,AtomicBoolean> verifying=new ConcurrentHashMap<>();
    public interface Done {void done(String error);}
    static SharedPreferences prefs(){return ApplicationLoader.applicationContext.getSharedPreferences("ghost_account_sessions",Context.MODE_PRIVATE);}
    public static boolean disabled(int account){return prefs().getBoolean("disabled_"+account,false);}
    public static boolean imported(int account){return prefs().getBoolean("imported_"+account,false);}
    public static boolean pending(int account){return !prefs().getString("txn_"+account,"").isEmpty();}
    public static boolean firstConnection(int account){return prefs().getBoolean("first_"+account,false);}
    public static String error(int account){return prefs().getString("error_"+account,"");}
    public static boolean canResume(int account){return !disabled(account)||verifying.containsKey(account);}
    public static boolean canRequest(int account,TLObject request){
        if(!disabled(account))return true;
        if(verifying.containsKey(account)&&request instanceof TLRPC.TL_users_getUsers){
            TLRPC.TL_users_getUsers get=(TLRPC.TL_users_getUsers)request;
            return get.id.size()==1&&get.id.get(0) instanceof TLRPC.TL_inputUserSelf;
        }
        return false;
    }
    public static boolean fatal(String text){return "AUTH_KEY_DUPLICATED".equals(text)||"SESSION_REVOKED".equals(text)||"SESSION_EXPIRED".equals(text)||"AUTH_KEY_UNREGISTERED".equals(text)||"AUTH_KEY_INVALID".equals(text)||"USER_DEACTIVATED".equals(text)||"USER_DEACTIVATED_BAN".equals(text);}
    static void changed(int account){AndroidUtilities.runOnUIThread(()->{
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces,MessagesController.UPDATE_MASK_STATUS);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.didUpdateConnectionState);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainUserInfoChanged);
    });}
    public static void disconnect(int account,Done callback){
        if(!prefs().edit().putBoolean("disabled_"+account,true).commit()){callback.done("Не удалось сохранить отключённое состояние.");return;}
        AtomicBoolean check=verifying.remove(account);if(check!=null)check.set(true);
        GhostSessions.queue.postRunnable(()->{
            String failure=null;
            try{ConnectionsManager.getInstance(account);if(!ConnectionsManager.native_setSessionDisabled(account,true))failure="Не удалось подтвердить закрытие соединений. Аккаунт оставлен отключённым.";}
            catch(Throwable e){failure="Не удалось закрыть соединения. Перезапустите приложение: отключение сохранено.";}
            String result=failure;changed(account);AndroidUtilities.runOnUIThread(()->callback.done(result));
        });
    }
    public static void invalid(int account,String reason){
        if(!imported(account))return;
        boolean confirmed=fatal(reason);
        String code=fatal(reason)?reason:"SESSION_REVOKED";
        android.content.SharedPreferences.Editor state=prefs().edit().putBoolean("disabled_"+account,true);if(confirmed)state.putString("error_"+account,code);state.commit();
        long user=UserConfig.getInstance(account).getClientUserId();boolean first=firstConnection(account);
        GhostSessions.queue.postRunnable(()->{
            ConnectionsManager.native_setSessionDisabled(account,true);
            if(first&&confirmed)GhostSessions.removeUnverified(account,user);
            changed(account);
        });
    }
    public static void connect(int account,Done callback){
        if(!prefs().getString("txn_"+account,"").isEmpty()){callback.done("Восстановление не завершено. Перезапустите приложение для безопасного отката.");return;}
        if(!error(account).isEmpty()){callback.done("Эта сессия больше не действительна. Нужен новый вход в аккаунт.");return;}
        AtomicBoolean done=new AtomicBoolean();if(verifying.putIfAbsent(account,done)!=null){callback.done("Проверка авторизации уже выполняется.");return;}
        long expected=UserConfig.getInstance(account).getClientUserId();
        GhostSessions.queue.postRunnable(()->{
            try{
                ConnectionsManager manager=ConnectionsManager.getInstance(account);
                if(!ConnectionsManager.native_setSessionDisabled(account,false))throw new IllegalStateException();
                trace(account,done,"start");
                AndroidUtilities.runOnUIThread(()->trace(account,done,"5s"),5000);
                AndroidUtilities.runOnUIThread(()->trace(account,done,"15s"),15000);
                TLRPC.TL_users_getUsers request=new TLRPC.TL_users_getUsers();request.id.add(new TLRPC.TL_inputUserSelf());
                final int token=manager.sendRequest(request,(response,failure)->AndroidUtilities.runOnUIThread(()->{
                    if(!done.compareAndSet(false,true))return;
                    verifying.remove(account,done);
                    TLRPC.User actual=null;
                    if(response instanceof Vector){Vector<?> users=(Vector<?>)response;if(!users.objects.isEmpty()&&users.objects.get(0) instanceof TLRPC.User)actual=(TLRPC.User)users.objects.get(0);}
                    if(failure==null&&actual!=null&&actual.id==expected&&!actual.deleted&&!actual.bot){
                        if(!prefs().edit().putBoolean("disabled_"+account,false).putBoolean("first_"+account,false).remove("error_"+account).commit()){
                            disconnect(account,ignored->callback.done("Не удалось сохранить подключение. Аккаунт отключён."));return;
                        }
                        UserConfig.getInstance(account).setCurrentUser(actual);UserConfig.getInstance(account).saveConfig(true);
                        ConnectionsManager.getInstance(account).setUserPremium(actual.premium);
                        MessagesController.getInstance(account).putUser(actual,false);
                        MessagesController.getInstance(account).loadAppConfig();ConnectionsManager.getInstance(account).updateDcSettings();
                        MessagesController.getInstance(account).getDifference();MessagesController.getInstance(account).loadDialogs(0,0,100,true);
                        if(account!=UserConfig.selectedAccount||ApplicationLoader.mainInterfacePaused)ConnectionsManager.getInstance(account).setAppPaused(true,true);
                        if(SharedConfig.pushString!=null&&!SharedConfig.pushString.isEmpty())PushListenerController.sendRegistrationToServer(ApplicationLoader.getPushProvider().getPushType(),SharedConfig.pushString);
                        changed(account);callback.done(null);
                    }else{
                        String code=failure==null?"IDENTITY_MISMATCH":failure.text;
                        boolean invalid=fatal(code)||"IDENTITY_MISMATCH".equals(code);
                        prefs().edit().putBoolean("disabled_"+account,true).commit();
                        GhostSessions.queue.postRunnable(()->{
                            ConnectionsManager.native_setSessionDisabled(account,true);
                            if(invalid&&firstConnection(account)&&imported(account))GhostSessions.removeUnverified(account,expected);
                            else if(invalid)prefs().edit().putString("error_"+account,fatal(code)?code:"SESSION_REVOKED").commit();
                            changed(account);AndroidUtilities.runOnUIThread(()->callback.done(invalid?"Авторизация недействительна или относится к другому аккаунту. Нужен новый вход.":"Не удалось проверить авторизацию. Аккаунт оставлен отключённым; проверьте сеть и повторите."));
                        });
                    }
                }));
                AndroidUtilities.runOnUIThread(()->{
                    if(!done.compareAndSet(false,true))return;
                    GhostSessions.queue.postRunnable(()->android.util.Log.i("GhostSessionTransport","timeout "+ConnectionsManager.native_sessionConnectionStatus(account)));
                    verifying.remove(account,done);manager.cancelRequest(token,false);
                    disconnect(account,ignored->callback.done("Проверка авторизации не завершилась. Аккаунт оставлен отключённым."));
                },30000);
            }catch(Throwable e){AndroidUtilities.runOnUIThread(()->{
                if(!done.compareAndSet(false,true))return;verifying.remove(account,done);
                disconnect(account,ignored->callback.done("Не удалось подключить аккаунт. Его данные сохранены, соединение отключено."));
            });}
        });
    }
    private static void trace(int account,AtomicBoolean done,String stage){
        if(done.get())return;
        GhostSessions.queue.postRunnable(()->{if(!done.get())android.util.Log.i("GhostSessionTransport",stage+" "+ConnectionsManager.native_sessionConnectionStatus(account));});
    }
    public static void loggedOut(int account){
        prefs().edit().remove("disabled_"+account).remove("imported_"+account).remove("first_"+account).remove("error_"+account).remove("txn_"+account).commit();
        GhostSessions.queue.postRunnable(()->{try{ConnectionsManager.getInstance(account);ConnectionsManager.native_setSessionDisabled(account,false);}catch(Throwable ignored){}});
    }
    public static void prepareLogin(int account){
        if(UserConfig.isValidAccount(account)||!prefs().getString("txn_"+account,"").isEmpty())return;
        if(disabled(account)||imported(account))loggedOut(account);
    }
    private GhostAccountSessions(){}
}
