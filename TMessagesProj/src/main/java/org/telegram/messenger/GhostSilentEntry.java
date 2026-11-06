package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.concurrent.atomic.AtomicIntegerArray;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.tl.TL_account;

/** Passive entry is a session guard, independent of the persistent ghost switch. */
public final class GhostSilentEntry {
    private static final AtomicIntegerArray active = new AtomicIntegerArray(UserConfig.MAX_ACCOUNT_COUNT);

    private GhostSilentEntry() {}

    private static SharedPreferences preferences(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences(
                "ghost_silent_entry_" + UserConfig.getInstance(account).getClientUserId(), Context.MODE_PRIVATE);
    }

    public static boolean enabled(int account) {
        return preferences(account).getBoolean("enabled", false);
    }

    public static boolean holding(int account) {
        return enabled(account) && active.get(account) == 0;
    }

    public static boolean privateReading(int account) {
        return GhostMode.option(account, GhostMode.READ) || holding(account);
    }

    public static void setEnabled(int account, boolean enabled) {
        active.set(account, 0);
        preferences(account).edit().putBoolean("enabled", enabled).apply();
        MessagesController.getInstance(account).onSilentEntryChanged();
    }

    public static void enter(int account) {
        active.set(account, 0);
        if (enabled(account)) MessagesController.getInstance(account).onSilentEntryChanged();
    }

    /** Only explicit composer/reaction/call actions call this; uploads and bots do not. */
    public static void userAction(int account) {
        if (!enabled(account) || GhostSimulation.active(account)) return;
        if (active.compareAndSet(account, 0, 1)) {
            MessagesController.getInstance(account).onSilentEntryChanged();
        }
    }

    public static boolean suppress(int account, TLObject request) {
        if (GhostMode.isActionReceipt(request) || !holding(account)) return false;
        if (request instanceof TL_account.updateStatus && GhostMode.hideOnline(account)
                && ((TL_account.updateStatus) request).offline) return false;
        // Do not even send offline updates: opening the app must not change last seen.
        return request instanceof TL_account.updateStatus || GhostModePolicy.shouldSuppress(request, true);
    }
}
