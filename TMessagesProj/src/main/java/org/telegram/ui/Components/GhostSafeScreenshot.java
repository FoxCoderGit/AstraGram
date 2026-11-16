package org.telegram.ui.Components;

import java.util.HashMap;
import java.util.WeakHashMap;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

/** Rendering-only copies; never edits account records or stored messages. */
public final class GhostSafeScreenshot {
    private static final HashMap<String,String[]> scopes=new HashMap<>();
    private static final WeakHashMap<MessageObject,MessageObject> copies=new WeakHashMap<>();
    private static String key(int account,long dialog){return account+":"+dialog;}
    public static boolean active(int account,long dialog){return scopes.containsKey(key(account,dialog));}
    public static boolean active(MessageObject m){return m!=null&&active(m.currentAccount,m.getDialogId());}
    public static void disable(int account,long dialog){scopes.remove(key(account,dialog));copies.clear();}
    public static boolean toggle(int account,long dialog,TLRPC.User peer){
        if(active(account,dialog)){disable(account,dialog);return false;}
        if(peer==null)return false;
        TLRPC.User self=UserConfig.getInstance(account).getCurrentUser();
        scopes.put(key(account,dialog),new String[]{self==null?null:UserObject.getUserName(self),self==null?null:self.first_name,self==null?null:self.last_name,
                self==null||self.username==null?null:"@"+self.username,UserObject.getUserName(peer),peer.first_name,peer.last_name,peer.username==null?null:"@"+peer.username});
        return true;
    }
    public static String alias(MessageObject m){return m!=null&&m.isOutOwner()?"я":"собеседник";}
    public static String redact(String text,String... identities){
        String result=text==null?"":text;
        for(int i:new int[]{3,7,0,4,1,5,2,6}){
            if(i>=identities.length||identities[i]==null||identities[i].trim().isEmpty())continue;
            result=result.replaceAll("(?iu)"+java.util.regex.Pattern.quote(identities[i]),i<4?"я":"собеседник");
        }
        return result.replaceAll("@[\\p{L}\\p{N}_]+","[тег]").replaceAll("(?i)(https?://|www\\.)\\S+","[ссылка]")
                .replaceAll("[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}.-]+\\.[\\p{L}]{2,}","[почта]").replaceAll("\\+?\\d[\\d ()-]{6,}\\d","[номер]");
    }
    public static MessageObject display(MessageObject original){
        if(!active(original))return original;
        MessageObject cached=copies.get(original);if(cached!=null){cached.stableId=original.stableId;return cached;}
        if(original.messageOwner.action!=null){
            TLRPC.TL_message service=new TLRPC.TL_message();service.id=original.getId();service.peer_id=original.messageOwner.peer_id;
            service.dialog_id=original.getDialogId();service.message="[служебное сообщение]";
            MessageObject copy=new MessageObject(original.currentAccount,service,false,true);copy.stableId=original.stableId;return copy;
        }
        NativeByteBuffer buffer=null;
        try{
            buffer=new NativeByteBuffer(original.messageOwner.getObjectSize());original.messageOwner.serializeToStream(buffer);buffer.position(0);
            TLRPC.Message message=TLRPC.Message.TLdeserialize(buffer,buffer.readInt32(true),true);
            message.dialog_id=original.getDialogId();message.attachPath=original.messageOwner.attachPath;
            message.message=redact(original.messageOwner.message,scopes.get(key(original.currentAccount,original.getDialogId())));
            message.entities.clear();message.fwd_from=null;message.via_bot_id=0;
            if(message.reply_to!=null){
                message.reply_to.quote_text=redact(message.reply_to.quote_text,scopes.get(key(original.currentAccount,original.getDialogId())));
                if(message.reply_to.quote_entities!=null)message.reply_to.quote_entities.clear();
            }
            if(message.post_author!=null)message.post_author=alias(original);
            MessageObject copy=new MessageObject(original.currentAccount,message,false,true);copy.stableId=original.stableId;copies.put(original,copy);
            if(original.replyMessageObject!=null){
                TLRPC.TL_message reply=new TLRPC.TL_message();reply.id=original.replyMessageObject.getId();
                reply.peer_id=original.replyMessageObject.messageOwner.peer_id;reply.from_id=original.replyMessageObject.messageOwner.from_id;
                reply.out=original.replyMessageObject.isOutOwner();reply.message=redact(original.replyMessageObject.messageOwner.message,scopes.get(key(original.currentAccount,original.getDialogId())));
                copy.replyMessageObject=new MessageObject(original.currentAccount,reply,false,true);
            }
            return copy;
        }catch(Exception error){
            TLRPC.TL_message hidden=new TLRPC.TL_message();hidden.id=original.getId();hidden.peer_id=original.messageOwner.peer_id;
            hidden.dialog_id=original.getDialogId();hidden.out=original.isOutOwner();hidden.message="[скрыто]";
            MessageObject copy=new MessageObject(original.currentAccount,hidden,false,true);copy.stableId=original.stableId;return copy;
        }finally{if(buffer!=null)buffer.reuse();}
    }
    private GhostSafeScreenshot(){}
}
