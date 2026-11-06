package org.telegram.messenger;

import android.content.Context;
import java.io.File;
import java.security.KeyStore;

/** Local cleanup of the removed experimental external text service. No network. */
final class GhostRetiredFeatures {
    static void cleanup() {
        Utilities.globalQueue.postRunnable(() -> {
            Context context=ApplicationLoader.applicationContext;
            context.getSharedPreferences("ghost_beta_presence",Context.MODE_PRIVATE).edit().clear().commit();
            android.content.SharedPreferences state=context.getSharedPreferences("ghost_retired_features",Context.MODE_PRIVATE);
            if(state.getBoolean("luna_removed",false))return;
            try {
                if(!context.getSharedPreferences("ghost_luna",Context.MODE_PRIVATE).edit().clear().commit())return;
                KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
                if(store.containsAlias("ghost_luna_personal_key"))store.deleteEntry("ghost_luna_personal_key");
                File[] cached=context.getCacheDir().listFiles();
                if(cached!=null)for(File file:cached)if(file.isFile()&&file.getName().matches("luna_[a-f0-9]{64}"))if(!file.delete())return;
                File results=new File(context.getApplicationInfo().dataDir,"app_luna_results");
                File[] files=results.listFiles();
                if(files!=null)for(File file:files)if(file.isFile()&&(file.getName().matches("luna_[a-f0-9]{64}")||file.getName().matches("result_.*\\.tmp")))if(!file.delete())return;
                state.edit().putBoolean("luna_removed",true).apply();
            }catch(Exception ignored){/* Retry at next process start, without logging private data. */}
        });
    }
}
