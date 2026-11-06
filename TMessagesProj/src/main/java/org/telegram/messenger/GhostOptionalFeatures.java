package org.telegram.messenger;

/** Independent built-in switches shown in Plugins; legacy experimental settings do not opt users in. */
public final class GhostOptionalFeatures {
    // Kept only to read legacy preferences; full-chat translation is no longer a mod feature.
    public static final String MUSIC="yandex_music", DRAWING="waveform_drawing", CHAT_TRANSLATION="chat_translation", VIDEO="yt_dlp", EXTERA_SUPPORTERS="extera_supporters";
    public static boolean enabled(String feature){return MessagesController.getGlobalMainSettings().getBoolean("ghost_optional_"+feature,false);}
    public static void setEnabled(String feature,boolean enabled){
        if(!MUSIC.equals(feature)&&!DRAWING.equals(feature)&&!VIDEO.equals(feature)&&!EXTERA_SUPPORTERS.equals(feature))throw new IllegalArgumentException("feature");
        MessagesController.getGlobalMainSettings().edit().putBoolean("ghost_optional_"+feature,enabled).apply();
        if(EXTERA_SUPPORTERS.equals(feature))GhostExteraSupporters.onPreferenceChanged(enabled);
    }
    private GhostOptionalFeatures(){}
}
