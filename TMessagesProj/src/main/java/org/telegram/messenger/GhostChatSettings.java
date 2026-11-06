package org.telegram.messenger;

/** Local UI preferences. Changing these never modifies a Telegram message or receipt. */
public final class GhostChatSettings {
    public static final int REACTION=0,REPLY=1,EDIT=2,COPY=3,NONE=4;
    public static final String[] ACTIONS={"Быстрая реакция","Ответить","Редактировать","Копировать текст","Ничего"};
    private GhostChatSettings() { }
    public static boolean legacy(){return MessagesController.getGlobalMainSettings().getBoolean("ghost_legacy_navigation",true);}
    public static boolean previews(){return MessagesController.getGlobalMainSettings().getBoolean("ghost_ephemeral_preview",true);}
    public static int action(boolean outgoing){return Math.max(REACTION,Math.min(NONE,MessagesController.getGlobalMainSettings().getInt(outgoing?"ghost_double_out":"ghost_double_in",REACTION)));}
    public static void action(boolean outgoing,int action){MessagesController.getGlobalMainSettings().edit().putInt(outgoing?"ghost_double_out":"ghost_double_in",Math.max(0,Math.min(NONE,action))).apply();}
    public static boolean ephemeral(MessageObject message){return message!=null&&previews()&&!message.hasExtendedMediaPreview()&&MessageObject.isSecretPhotoOrVideo(message.messageOwner);}
    public static String ephemeralLabel(MessageObject message){
        if(message==null||message.hasExtendedMediaPreview()||!MessageObject.isSecretPhotoOrVideo(message.messageOwner))return null;
        org.telegram.tgnet.TLRPC.MessageMedia media=MessageObject.getMedia(message.messageOwner);
        int ttl=media==null?0:media.ttl_seconds;
        if(message.isSecret())ttl=Math.max(ttl,message.messageOwner.ttl);
        if(ttl==Integer.MAX_VALUE)return "⏳ 1×";
        if(ttl<=0)return null;
        if(ttl<60)return "⏳ "+ttl+" с";
        if(ttl%3600==0)return "⏳ "+ttl/3600+" ч";
        if(ttl%60==0)return "⏳ "+ttl/60+" мин";
        return "⏳ "+ttl+" с";
    }
}
