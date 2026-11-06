package org.ghostgram.messenger;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.PushListenerController;
import org.telegram.messenger.PluginBridge;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;

public class GhostApplication extends ApplicationLoader {
    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }

    @Override
    public void onCreate() {
        if (org.telegram.messenger.GhostYandexMusicNetwork.isAuthProcess(this)) {
            super.onCreate();
            return;
        }
        // Values are supplied from the ignored private build configuration and
        // deliberately become part of the APK, not of the public source tree.
        BuildVars.APP_ID = BuildConfig.ASTRA_API_ID;
        BuildVars.APP_HASH = BuildConfig.ASTRA_API_HASH;
        BuildVars.CHECK_UPDATES = false;
        BuildVars.SUPPORTS_PASSKEYS = false;
        BuildVars.IS_BILLING_UNAVAILABLE = true;
        BuildVars.DEBUG_PRIVATE_VERSION = false;
        super.onCreate();
        final Thread.UncaughtExceptionHandler previousHandler=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
            org.ghostgram.messenger.plugins.PluginHost.recordCrash(error);
            if(previousHandler!=null)previousHandler.uncaughtException(thread,error);
        });
        org.ghostgram.messenger.license.LicenseManager.initialize(this);
        org.telegram.messenger.GhostBadges.refresh();

        // This standalone package cannot use Telegram's FCM registration. Keep the
        // native Telegram push connection alive instead; it automatically shares
        // the SOCKS5/MTProto/Web proxy configured in the app.
        MessagesController.getGlobalNotificationsSettings().edit()
                .putBoolean("pushService", true)
                .putBoolean("pushConnection", true)
                .apply();
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            MessagesController.getMainSettings(account).edit()
                    .putBoolean("keepAliveService", true)
                    .putBoolean("backgroundConnection", true)
                    .apply();
            if (UserConfig.getInstance(account).isClientActivated()) {
                ConnectionsManager.getInstance(account).setPushConnectionEnabled(true);
            }
        }
        ApplicationLoader.startPushService();
    }

    @Override
    protected PushListenerController.IPushListenerServiceProvider onCreatePushProvider() {
        // This local build has no Firebase project registered for its package.
        // Use Telegram's normal no-Google-services fallback instead of another app's keys.
        return new PushListenerController.IPushListenerServiceProvider() {
            public boolean hasServices() { return false; }
            public String getLogTitle() { return "AstraGram local build"; }
            public void onRequestPushToken() { }
            public int getPushType() { return PushListenerController.PUSH_TYPE_FIREBASE; }
        };
    }
}
