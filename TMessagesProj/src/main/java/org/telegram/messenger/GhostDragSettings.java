package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

public final class GhostDragSettings {
    public static final String[] KEYS={"enabled","files","text","multiple","haptic","same_chat"};
    public static SharedPreferences prefs(){return ApplicationLoader.applicationContext.getSharedPreferences("ghost_drag",Context.MODE_PRIVATE);}
    public static boolean get(String key){return prefs().getBoolean(key,true);}
    public static boolean enabled(){return get("enabled");}
}
