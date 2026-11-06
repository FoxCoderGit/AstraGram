package org.telegram.messenger;

public final class GhostExperimental {
    public static boolean enabled(){return MessagesController.getGlobalMainSettings().getBoolean("ghost_experimental",false);}
    public static void toggle(){MessagesController.getGlobalMainSettings().edit().putBoolean("ghost_experimental",!enabled()).apply();}
    private GhostExperimental(){}
}
