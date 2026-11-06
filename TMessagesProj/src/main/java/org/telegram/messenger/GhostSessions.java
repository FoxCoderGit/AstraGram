package org.telegram.messenger;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;
import org.telegram.tgnet.*;

/** Authorization-only backups. Import writes happen only in disabled, reserved slots. */
public final class GhostSessions {
    public static final DispatchQueue queue=new DispatchQueue("ghostSessionBackups");
    public static final String WARNING="Файл содержит доступ к выбранным аккаунтам. Храните его и пароль отдельно. Одновременное использование копий одной сессии может привести к аннулированию её ключа. Завершённую или отозванную сессию резервная копия не восстановит";
    private static final AtomicBoolean busy=new AtomicBoolean();
    private static final class NoSlots extends IOException{}
    public interface Result<T>{void done(T value,String error);}
    public static byte[] derive(char[] password,byte[] salt,int iterations)throws GeneralSecurityException{
        ByteBuffer encoded=StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));byte[] utf8=new byte[encoded.remaining()];encoded.get(utf8);
        try{return Utilities.deriveSessionBackupKey(utf8,salt,iterations);}finally{Arrays.fill(utf8,(byte)0);if(encoded.hasArray())Arrays.fill(encoded.array(),(byte)0);}
    }
    public static TLRPC.User user(GhostSessionBundle.Entry e)throws IOException{
        try{
            SerializedData data=new SerializedData(e.user);TLRPC.User user=TLRPC.User.TLdeserialize(data,data.readInt32(true),true);
            if(user==null||user.bot||user.deleted||user.id!=e.id||data.remaining()!=0)throw new IOException("Account metadata");
            if(GhostAuthSnapshot.validate(e.auth)!=user.id||GhostAuthSnapshot.apiId(e.auth)!=BuildVars.APP_ID)throw new IOException("API configuration");
            return user;
        }catch(RuntimeException error){throw new IOException("Invalid account metadata");}
    }
    public static void exportAccounts(List<Integer> slots,char[] password,Result<byte[]> callback){
        if(!busy.compareAndSet(false,true)){Arrays.fill(password,'\0');callback.done(null,"Другая операция с сессиями ещё выполняется.");return;}
        queue.postRunnable(()->{
            ArrayList<GhostSessionBundle.Entry> entries=new ArrayList<>();byte[] plain=null,encrypted=null;String failure=null;
            try{
                if(slots.isEmpty())throw new IOException();
                for(int account:slots){
                    UserConfig cfg=UserConfig.getInstance(account);TLRPC.User u=cfg.getCurrentUser();
                    if(u==null||u.bot||u.deleted||!GhostAccountSessions.error(account).isEmpty()||!GhostAccountSessions.prefs().getString("txn_"+account,"").isEmpty())throw new IOException();
                    SerializedData data=new SerializedData();u.serializeToStream(data);byte[] info=data.toByteArray();
                    ConnectionsManager.getInstance(account);byte[] auth=ConnectionsManager.native_exportSessionAuth(account);
                    if(auth==null){Arrays.fill(info,(byte)0);throw new IOException();}
                    GhostSessionBundle.Entry entry=new GhostSessionBundle.Entry(u.id,info,auth);entries.add(entry);user(entry);
                    if(cfg.getClientUserId()!=u.id)throw new IOException();
                }
                plain=GhostSessionBundle.encode(entries);encrypted=GhostSessionCrypto.encrypt(plain,password,GhostSessions::derive);
            }catch(Throwable error){failure="Не удалось создать резервную копию. Проверьте, что выбранные аккаунты авторизованы и их сессии действительны.";}
            finally{Arrays.fill(password,'\0');if(plain!=null)Arrays.fill(plain,(byte)0);for(GhostSessionBundle.Entry e:entries)e.close();busy.set(false);}
            byte[] result=encrypted;String error=failure;AndroidUtilities.runOnUIThread(()->callback.done(result,error));
        });
    }
    public static void decrypt(byte[] file,char[] password,Result<List<GhostSessionBundle.Entry>> callback){
        if(!busy.compareAndSet(false,true)){Arrays.fill(password,'\0');Arrays.fill(file,(byte)0);callback.done(null,"Другая операция с сессиями ещё выполняется.");return;}
        queue.postRunnable(()->{
            byte[] plain=null;List<GhostSessionBundle.Entry> entries=null;String failure=null;
            try{plain=GhostSessionCrypto.decrypt(file,password,GhostSessions::derive);entries=GhostSessionBundle.decode(plain);for(GhostSessionBundle.Entry e:entries)user(e);}
            catch(GhostSessionCrypto.FormatException e){failure="version".equals(e.kind)?"Версия резервной копии не поддерживается.":"Файл повреждён или не является резервной копией AstraGram.";}
            catch(javax.crypto.AEADBadTagException e){failure="Неверный пароль или файл был повреждён.";}
            catch(Throwable e){failure="Не удалось проверить структуру резервной копии. Аккаунты не изменены.";}
            finally{Arrays.fill(password,'\0');Arrays.fill(file,(byte)0);if(plain!=null)Arrays.fill(plain,(byte)0);busy.set(false);}
            if(failure!=null&&entries!=null){for(GhostSessionBundle.Entry e:entries)e.close();entries=null;}
            List<GhostSessionBundle.Entry> result=entries;String error=failure;AndroidUtilities.runOnUIThread(()->callback.done(result,error));
        });
    }
    public static List<Integer> freeSlots(int count){
        ArrayList<Integer> result=new ArrayList<>();
        for(int i=0;i<UserConfig.MAX_PHONE_ACCOUNTS&&result.size()<count;i++){
            UserConfig.getInstance(i).loadConfig();
            if(!UserConfig.isValidAccount(i)&&GhostAccountSessions.prefs().getString("txn_"+i,"").isEmpty())result.add(i);
        }
        return result;
    }
    public static void importAccounts(List<GhostSessionBundle.Entry> source,Result<List<Integer>> callback){
        if(!busy.compareAndSet(false,true)){callback.done(null,"Другая операция с сессиями ещё выполняется.");return;}
        queue.postRunnable(()->{
            List<Integer> slots=new ArrayList<>();String failure=null;boolean committed=false;String transaction=UUID.randomUUID().toString();JSONArray journal=new JSONArray();
            try{
                if(source.isEmpty())throw new IOException();
                recoverIncompleteImports();if(!GhostAccountSessions.prefs().getString("import_journal","").isEmpty())throw new IOException("Pending recovery");
                HashSet<Long> existing=new HashSet<>();for(int a=0;a<UserConfig.MAX_ACCOUNT_COUNT;a++)if(UserConfig.isValidAccount(a))existing.add(UserConfig.getInstance(a).getClientUserId());
                for(GhostSessionBundle.Entry entry:source){user(entry);if(!existing.add(entry.id))throw new IllegalArgumentException("duplicate");}
                slots=freeSlots(source.size());if(slots.size()!=source.size())throw new NoSlots();
                android.content.SharedPreferences.Editor flags=GhostAccountSessions.prefs().edit();
                for(int i=0;i<slots.size();i++){
                    int a=slots.get(i);journal.put(new JSONObject().put("slot",a).put("id",source.get(i).id));
                    flags.putBoolean("disabled_"+a,true).putBoolean("imported_"+a,true).putBoolean("first_"+a,true).remove("error_"+a).putString("txn_"+a,transaction);
                }
                if(!flags.putString("import_journal",journal.toString()).putString("import_transaction",transaction).commit())throw new IOException();
                for(int i=0;i<slots.size();i++){
                    int a=slots.get(i);GhostSessionBundle.Entry entry=source.get(i);ConnectionsManager.getInstance(a);
                    if(!ConnectionsManager.native_setSessionDisabled(a,true)||!ConnectionsManager.native_installSessionAuth(a,entry.auth))throw new IOException();
                    UserConfig.getInstance(a).installSessionUserOffline(user(entry));
                    // This is not a login yet: cleanup(true) starts getDifference while
                    // the imported account is deliberately offline and not verified.
                    MessagesStorage.getInstance(a).cleanup(false);
                    ArrayList<TLRPC.User> users=new ArrayList<>();users.add(user(entry));MessagesStorage.getInstance(a).putUsersAndChats(users,null,true,true);
                    java.util.concurrent.CountDownLatch stored=new java.util.concurrent.CountDownLatch(1);MessagesStorage.getInstance(a).getStorageQueue().postRunnable(stored::countDown);
                    if(!stored.await(15,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("Storage timeout");
                }
                android.content.SharedPreferences.Editor done=GhostAccountSessions.prefs().edit().remove("import_journal").remove("import_transaction");for(int a:slots)done.remove("txn_"+a);
                if(!done.commit()){
                    android.content.SharedPreferences.Editor retry=GhostAccountSessions.prefs().edit().putString("import_journal",journal.toString()).putString("import_transaction",transaction);for(int a:slots)retry.putString("txn_"+a,transaction);retry.commit();
                    throw new IOException();
                }committed=true;
            }catch(IllegalArgumentException e){failure="Один из выбранных аккаунтов уже добавлен. Существующие аккаунты не перезаписываются.";}
            catch(NoSlots e){failure="Недостаточно свободных слотов для выбранных аккаунтов.";}
            catch(Throwable e){failure="Не удалось восстановить сессии. Изменения отменены; исходные аккаунты не затронуты.";}
            finally{if(!committed)recoverIncompleteImports();busy.set(false);}
            if(!committed&&!GhostAccountSessions.prefs().getString("import_journal","").isEmpty())failure="Не удалось завершить откат. Незавершённые аккаунты отключены; очистка повторится после перезапуска приложения.";
            List<Integer> result=committed?slots:null;String error=failure;
            if(committed)for(int a:slots)GhostAccountSessions.changed(a);
            AndroidUtilities.runOnUIThread(()->callback.done(result,error));
        });
    }
    public static void recoverIncompleteImports(){
        String raw=GhostAccountSessions.prefs().getString("import_journal","");if(raw.isEmpty())return;
        try{
            JSONArray journal=new JSONArray(raw);String transaction=GhostAccountSessions.prefs().getString("import_transaction","");boolean complete=true;
            for(int i=0;i<journal.length();i++){
                JSONObject row=journal.getJSONObject(i);int a=row.getInt("slot");long id=row.getLong("id");if(a<0||a>=UserConfig.MAX_ACCOUNT_COUNT||id<=0){complete=false;continue;}
                if(!transaction.equals(GhostAccountSessions.prefs().getString("txn_"+a,"")))continue;
                if(!removeUnverified(a,id))complete=false;
            }
            if(complete)GhostAccountSessions.prefs().edit().remove("import_journal").remove("import_transaction").commit();
        }catch(Throwable ignored){/* Keep disabled slots and journal for a later retry. Never log key material. */}
    }
    static boolean removeUnverified(int account,long expected){
        try{
            UserConfig cfg=UserConfig.getInstance(account);cfg.loadConfig();
            if(cfg.getClientUserId()!=0&&cfg.getClientUserId()!=expected)return false;
            if(!GhostAccountSessions.imported(account)||!GhostAccountSessions.firstConnection(account))return false;
            GhostAccountSessions.prefs().edit().putBoolean("disabled_"+account,true).commit();ConnectionsManager.getInstance(account);
            if(!ConnectionsManager.native_setSessionDisabled(account,true)||!ConnectionsManager.native_eraseImportedSession(account,expected))return false;
            if(!cfg.removeSessionUserOffline(expected))return false;
            MessagesStorage.getInstance(account).cleanup(false);
            boolean cleared=GhostAccountSessions.prefs().edit().remove("imported_"+account).remove("first_"+account).remove("error_"+account).remove("txn_"+account).commit();
            if(cleared)AndroidUtilities.runOnUIThread(()->{if(org.telegram.ui.LaunchActivity.instance!=null)org.telegram.ui.LaunchActivity.instance.onLocalSessionRestoreRemoved(account);});
            return cleared;
        }catch(Throwable ignored){return false;}
    }
    private GhostSessions(){}
}
