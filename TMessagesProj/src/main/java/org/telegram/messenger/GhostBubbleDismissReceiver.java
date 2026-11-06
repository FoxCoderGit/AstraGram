package org.telegram.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class GhostBubbleDismissReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        ApplicationLoader.postInitApplication();
        int account = intent.getIntExtra("currentAccount", -1);
        if (account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT)
            GhostFloatingChats.dismiss(account, intent.getLongExtra("dialogId", 0));
    }
}
