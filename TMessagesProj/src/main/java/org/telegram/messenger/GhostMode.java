package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

/** Per-account privacy controls. Legacy enabled preference is the migration fallback. */
public final class GhostMode {
    public static final int READ = 0, STORIES = 1, ONLINE = 2, TYPING = 3, AUTO_OFFLINE = 4;
    private static final Map<TLObject, Boolean> actionReceipts = Collections.synchronizedMap(new WeakHashMap<>());
    private GhostMode() { }
    private static SharedPreferences preferences(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences("ghostmode_" + account, Context.MODE_PRIVATE);
    }
    public static boolean option(int account, int option) {
        SharedPreferences p = preferences(account);
        return p.getBoolean("option_" + option, p.getBoolean("enabled", false));
    }
    public static boolean locked(int account, int option) { return preferences(account).getBoolean("lock_" + option, false); }
    public static int count(int account) { int n=0; for(int i=0;i<5;i++) if(option(account,i)) n++; return n; }
    public static boolean isEnabled(int account) { return count(account) > 0; }
    public static boolean masterOn(int account) {
        boolean editable = false;
        for (int i=0;i<5;i++) if (!locked(account,i)) {
            editable = true;
            if (!option(account,i)) return false;
        }
        return editable || isEnabled(account);
    }
    public static boolean hideOnline(int account) { return option(account, ONLINE); }
    public static boolean autoOffline(int account) { return hideOnline(account) && option(account, AUTO_OFFLINE); }
    public static boolean useSchedule(int account) { return count(account) == 5 && preferences(account).getBoolean("schedule", true); }
    public static boolean readOnActions(int account) { return isEnabled(account) && preferences(account).getBoolean("read_actions", false) && !useSchedule(account); }
    public static boolean scheduleSelected(int account) { return preferences(account).getBoolean("schedule", true); }
    public static boolean readActionsSelected(int account) { return preferences(account).getBoolean("read_actions", false); }
    public static void setEnabled(int account, boolean enabled) {
        SharedPreferences p = preferences(account);
        SharedPreferences.Editor e=p.edit();
        for(int i=0;i<5;i++) e.putBoolean("option_"+i, locked(account,i) ? option(account,i) : enabled);
        e.putBoolean("enabled", enabled).apply(); changed(account);
    }
    public static void setOption(int account,int option,boolean value) { preferences(account).edit().putBoolean("option_"+option,value).apply(); changed(account); }
    public static void toggleLock(int account,int option) {
        preferences(account).edit().putBoolean("option_"+option,option(account,option)).putBoolean("lock_"+option,!locked(account,option)).apply();
    }
    public static void setSchedule(int account,boolean value) {
        SharedPreferences.Editor e=preferences(account).edit().putBoolean("schedule",value);
        if(value)e.putBoolean("read_actions",false); e.apply(); changed(account);
    }
    public static void setReadActions(int account,boolean value) {
        SharedPreferences.Editor e=preferences(account).edit().putBoolean("read_actions",value);
        if(value)e.putBoolean("schedule",false); e.apply(); changed(account);
    }
    private static void changed(int account) { MessagesController.getInstance(account).onGhostModeChanged(hideOnline(account)); }
    public static boolean shouldSuppress(int account, TLObject request) {
        if(actionReceipts.containsKey(request)) return false;
        return GhostModePolicy.shouldSuppress(request, option(account,READ),option(account,STORIES),hideOnline(account),option(account,TYPING));
    }
    public static boolean isActionReceipt(TLObject request) { return actionReceipts.containsKey(request); }
    /** Explicit user action only; never grants a general window for unrelated receipts. */
    public static void readOnAction(int account,long peer,int maxId) {
        if(!readOnActions(account)||maxId<=0||peer==0||DialogObject.isEncryptedDialog(peer)||GhostSimulation.active(account))return;
        MessagesController mc=MessagesController.getInstance(account);
        TLObject request;
        TLRPC.Chat chat=peer<0?mc.getChat(-peer):null;
        if(ChatObject.isChannel(chat)) {
            TLRPC.TL_channels_readHistory r=new TLRPC.TL_channels_readHistory();
            r.channel=mc.getInputChannel(-peer); r.max_id=maxId; request=r;
        } else {
            TLRPC.TL_messages_readHistory r=new TLRPC.TL_messages_readHistory();
            r.peer=mc.getInputPeer(peer); r.max_id=maxId; request=r;
        }
        actionReceipts.put(request, true);
        mc.getConnectionsManager().sendRequest(request,(response,error)->{});
    }
}
