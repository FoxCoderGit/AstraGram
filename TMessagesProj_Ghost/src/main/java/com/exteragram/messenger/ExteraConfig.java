package com.exteragram.messenger;

import android.content.SharedPreferences;
import org.telegram.messenger.ApplicationLoader;
import java.util.HashSet;
import java.util.Set;

/** Preferences exposed by the reference plugin SDK. */
public final class ExteraConfig {
    public static final SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("exteraConfig", 0);
    public static final SharedPreferences.Editor editor = preferences.edit();
    public static volatile boolean pluginsEngine = preferences.getBoolean("pluginsEngine", false);
    public static volatile boolean pluginsDevMode = preferences.getBoolean("pluginsDevMode", false);
    public static volatile boolean pluginsSafeMode = preferences.getBoolean("pluginsSafeMode", false);
    public static Set<String> pinnedPlugins = new HashSet<>(preferences.getStringSet("pinnedPlugins", new HashSet<>()));

    public static void setPluginsEngine(boolean value) {
        pluginsEngine = value;
        preferences.edit().putBoolean("pluginsEngine", value).apply();
    }
    public static void setPluginsDevMode(boolean value) {
        pluginsDevMode = value;
        preferences.edit().putBoolean("pluginsDevMode", value).apply();
    }
    public static void setPluginsSafeMode(boolean value) {
        pluginsSafeMode = value;
        preferences.edit().putBoolean("pluginsSafeMode", value).apply();
    }
}
