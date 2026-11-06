package org.telegram.messenger;

import android.os.Bundle;
import android.text.TextUtils;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.AlertsCreator;

/** Sends new uploads/messages: no forward RPC, author header, source reply or source file reference. */
public final class GhostProtectedForward {
    public static boolean restricted(MessageObject message){
        return message!=null&&message.messageOwner!=null&&(message.messageOwner.noforwards
                ||MessagesController.getInstance(message.currentAccount).isPeerNoForwards(message.getDialogId()));
    }
    public static boolean canCopy(MessageObject message){
        if(message==null||message.messageOwner==null||!(message.messageOwner instanceof TLRPC.TL_message)
                ||message.getId()<=0||message.isSecretMedia()||message.isEphemeralAndNotWelcome()
                ||DialogObject.isEncryptedDialog(message.getDialogId())||message.isVoiceOnce()||message.isRoundOnce()
                ||message.needDrawBluredPreview()||message.isSponsored()||message.isLiveLocation()
                ||message.type==MessageObject.TYPE_PAID_MEDIA||message.messageOwner.action!=null)return false;
        TLRPC.MessageMedia media=message.messageOwner.media;
        if(media==null||media instanceof TLRPC.TL_messageMediaEmpty||media instanceof TLRPC.TL_messageMediaWebPage)
            return !TextUtils.isEmpty(message.messageOwner.message);
        return media.photo instanceof TLRPC.TL_photo||media.document instanceof TLRPC.TL_document
                ||media instanceof TLRPC.TL_messageMediaGeo||media instanceof TLRPC.TL_messageMediaVenue
                ||media instanceof TLRPC.TL_messageMediaContact;
    }
    public static void pick(ChatActivity source,ArrayList<MessageObject> messages){
        if(source==null||source.getParentActivity()==null||messages.isEmpty())return;
        for(MessageObject message:messages)if(!canCopy(message)){
            AlertsCreator.showSimpleAlert(source,"Этот тип сообщения нельзя отправить копией.");return;
        }
        if(MessagesController.getInstance(source.getCurrentAccount()).isFrozen())return;
        final ArrayList<MessageObject> snapshot=new ArrayList<>(messages);
        Bundle args=new Bundle();args.putBoolean("onlySelect",true);args.putInt("dialogsType",DialogsActivity.DIALOGS_TYPE_FORWARD);
        args.putInt("messagesCount",snapshot.size());args.putBoolean("canSelectTopics",true);
        DialogsActivity picker=new DialogsActivity(args);picker.setCurrentAccount(source.getCurrentAccount());
        picker.setDelegate((fragment,destinations,comment,param,notify,schedule,repeat,topics)->{
            if(destinations.size()!=1)return false;
            long target=destinations.get(0).dialogId,topic=destinations.get(0).topicId;
            int account=source.getCurrentAccount();
            if(!allowed(account,target,snapshot)){
                AlertsCreator.showSimpleAlert(source,"В выбранный чат нельзя отправить эти сообщения.");return false;
            }
            String note=comment==null?"":comment.toString();
            AlertsCreator.ensurePaidMessageConfirmation(account,target,snapshot.size()+(note.isEmpty()?0:1),stars->{
                fragment.finishFragment();
                source.clearSelectionMode();
                new Job(source,snapshot,target,topic,note,notify,schedule,repeat,stars).start();
            });return true;
        });source.presentFragment(picker);
    }
    private static boolean allowed(int account,long target,ArrayList<MessageObject> messages){
        MessagesController controller=MessagesController.getInstance(account);
        if(controller.isFrozen())return false;
        if(target<0){
            TLRPC.Chat chat=controller.getChat(-target);
            if(chat==null||!ChatObject.canWriteToChat(chat))return false;
            for(MessageObject message:messages)if(SendMessagesHelper.canSendMessageToChat(chat,message)!=0)return false;
        }else if(!DialogObject.isEncryptedDialog(target)){
            if(controller.getUser(target)==null)return false;
            TLRPC.UserFull full=controller.getUserFull(target);
            if(full!=null&&full.voice_messages_forbidden)for(MessageObject message:messages)if(message.isVoice()||message.isRoundVideo())return false;
        }
        return true;
    }
    static SendMessagesHelper.SendMessageParams parameters(MessageObject message,File file,long target)throws Exception{
        if(!canCopy(message))throw new IllegalArgumentException("Unsupported copy");
        SendMessagesHelper.SendMessageParams p=new SendMessagesHelper.SendMessageParams();
        p.peer=target;p.caption=message.messageOwner.message==null?"":message.messageOwner.message;
        p.entities=message.messageOwner.entities==null?new ArrayList<>():new ArrayList<>(message.messageOwner.entities);p.hasMediaSpoilers=message.messageOwner.media!=null&&message.messageOwner.media.spoiler;
        TLRPC.MessageMedia media=message.messageOwner.media;
        if(media!=null&&media.document instanceof TLRPC.TL_document){
            if(file==null||!file.isFile()||file.length()==0)throw new IllegalArgumentException("Missing download");
            TLRPC.Document original=media.document;
            TLRPC.TL_document document=new TLRPC.TL_document();
            document.file_reference=new byte[0];document.mime_type=original.mime_type;document.size=file.length();
            document.attributes=new ArrayList<>(original.attributes);document.date=(int)(System.currentTimeMillis()/1000);
            p.document=document;p.path=file.getAbsolutePath();
        }else if(media!=null&&media.photo instanceof TLRPC.TL_photo){
            if(file==null||!file.isFile()||file.length()==0)throw new IllegalArgumentException("Missing download");
            p.photo=SendMessagesHelper.getInstance(message.currentAccount).generatePhotoSizes(file.getAbsolutePath(),null);
            if(p.photo==null)throw new IllegalArgumentException("Unreadable photo");
            p.path=file.getAbsolutePath();
        }else if(media instanceof TLRPC.TL_messageMediaGeo||media instanceof TLRPC.TL_messageMediaVenue){
            p.location=media;
        }else if(media instanceof TLRPC.TL_messageMediaContact){
            TLRPC.TL_userContact_old2 contact=new TLRPC.TL_userContact_old2();contact.id=media.user_id;
            contact.phone=media.phone_number;contact.first_name=media.first_name;contact.last_name=media.last_name;p.user=contact;
        }else{p.message=p.caption;p.caption=null;}
        return p;
    }
    private static final class Job implements NotificationCenter.NotificationCenterDelegate {
        final ChatActivity source;final int account;final long owner,target,topic,stars;
        final ArrayList<MessageObject> messages;final ArrayList<File> files=new ArrayList<>();
        final String comment;final boolean notify;final int schedule,repeat;final AlertDialog progress;
        int index;volatile boolean stopped;String waiting;Runnable timeout;
        Job(ChatActivity source,ArrayList<MessageObject> messages,long target,long topic,String comment,boolean notify,int schedule,int repeat,long stars){
            this.source=source;this.messages=messages;this.target=target;this.topic=topic;this.comment=comment;
            this.notify=notify;this.schedule=schedule;this.repeat=repeat;this.stars=stars;
            account=source.getCurrentAccount();owner=UserConfig.getInstance(account).getClientUserId();
            progress=new AlertDialog(source.getParentActivity(),3,source.getResourceProvider());
            progress.setOnCancelListener(dialog->stop());
        }
        boolean valid(){return !stopped&&owner==UserConfig.getInstance(account).getClientUserId()&&source.getParentActivity()!=null;}
        void start(){
            NotificationCenter.getInstance(account).addObserver(this,NotificationCenter.fileLoaded);
            NotificationCenter.getInstance(account).addObserver(this,NotificationCenter.fileLoadFailed);
            NotificationCenter.getInstance(account).addObserver(this,NotificationCenter.fileLoadProgressChanged);
            progress.showDelayed(200);next();
        }
        void stop(){stopped=true;waiting=null;if(timeout!=null)AndroidUtilities.cancelRunOnUIThread(timeout);
            NotificationCenter.getInstance(account).removeObserver(this,NotificationCenter.fileLoaded);
            NotificationCenter.getInstance(account).removeObserver(this,NotificationCenter.fileLoadFailed);
            NotificationCenter.getInstance(account).removeObserver(this,NotificationCenter.fileLoadProgressChanged);progress.dismiss();}
        void fail(){stop();if(source.getParentActivity()!=null)AlertsCreator.showSimpleAlert(source,"Не удалось подготовить копию. Проверьте загрузку медиа и повторите попытку.");}
        File local(MessageObject message){
            File file=FileLoader.getInstance(account).getPathToMessage(message.messageOwner);
            if(file.isFile()&&file.length()>0)return file;
            String path=message.messageOwner.attachPath;
            if(!TextUtils.isEmpty(path)){file=new File(path);if(file.isFile()&&file.length()>0)return file;}
            return null;
        }
        void next(){
            if(!valid()){stop();return;}
            if(index==messages.size()){prepare();return;}
            MessageObject message=messages.get(index);TLRPC.MessageMedia media=message.messageOwner.media;
            if(media==null||media.photo==null&&media.document==null){files.add(null);index++;AndroidUtilities.runOnUIThread(this::next);return;}
            File file=local(message);
            if(file!=null){files.add(file);index++;AndroidUtilities.runOnUIThread(this::next);return;}
            if(media.document!=null){waiting=FileLoader.getAttachFileName(media.document);FileLoader.getInstance(account).loadFile(media.document,message,FileLoader.PRIORITY_HIGH,0);}
            else{
                TLRPC.PhotoSize size=FileLoader.getClosestPhotoSizeWithSize(media.photo.sizes,AndroidUtilities.getPhotoSize(true),false,null,true);
                if(size==null){fail();return;}
                waiting=FileLoader.getAttachFileName(size);
                FileLoader.getInstance(account).loadFile(ImageLocation.getForPhoto(size,media.photo),message,null,FileLoader.PRIORITY_HIGH,0);
            }
            timeout=this::fail;AndroidUtilities.runOnUIThread(timeout,120000);
        }
        @Override public void didReceivedNotification(int id,int account,Object...args){
            if(stopped||waiting==null||args.length==0||!waiting.equals(args[0]))return;
            if(id==NotificationCenter.fileLoadProgressChanged){
                if(timeout!=null){AndroidUtilities.cancelRunOnUIThread(timeout);AndroidUtilities.runOnUIThread(timeout,120000);}return;
            }
            waiting=null;if(timeout!=null)AndroidUtilities.cancelRunOnUIThread(timeout);
            if(id==NotificationCenter.fileLoadFailed){fail();return;}
            File file=args.length>1&&args[1] instanceof File?(File)args[1]:local(messages.get(index));
            if(file==null||!file.isFile()||file.length()==0){fail();return;}
            files.add(file);index++;next();
        }
        void prepare(){
            GhostMediaTools.preparationQueue.postRunnable(()->{
                try{
                    ArrayList<SendMessagesHelper.SendMessageParams> prepared=new ArrayList<>();
                    HashMap<Long,Long> albums=new HashMap<>();
                    for(int i=0;i<messages.size();i++){
                        if(stopped)return;
                        MessageObject message=messages.get(i);
                        SendMessagesHelper.SendMessageParams p=parameters(message,files.get(i),target);
                        p.notify=notify;p.scheduleDate=schedule;p.scheduleRepeatPeriod=repeat;p.payStars=stars;
                        if(topic!=0){TLRPC.TL_message top=new TLRPC.TL_message();top.id=(int)topic;top.peer_id=MessagesController.getInstance(account).getPeer(target);top.message="";p.replyToTopMsg=new MessageObject(account,top,false,false);}
                        long group=message.getGroupId();
                        if(group!=0&&(p.photo!=null||p.document!=null)){
                            Long fresh=albums.get(group);if(fresh==null){fresh=SendMessagesHelper.getInstance(account).getNextRandomId();albums.put(group,fresh);}
                            p.params=new HashMap<>();p.params.put("groupId",Long.toString(fresh));
                            if(i==messages.size()-1||messages.get(i+1).getGroupId()!=group)p.params.put("final","1");
                        }
                        prepared.add(p);
                    }
                    AndroidUtilities.runOnUIThread(()->{
                        if(!valid()||!allowed(account,target,messages)){stop();return;}
                        if(!comment.isEmpty()){
                            SendMessagesHelper.SendMessageParams note=SendMessagesHelper.SendMessageParams.of(comment,target);note.notify=notify;note.scheduleDate=schedule;note.scheduleRepeatPeriod=repeat;note.payStars=stars;
                            if(!prepared.isEmpty())note.replyToTopMsg=prepared.get(0).replyToTopMsg;
                            SendMessagesHelper.getInstance(account).sendMessage(note);
                        }
                        for(SendMessagesHelper.SendMessageParams p:prepared)SendMessagesHelper.getInstance(account).sendMessage(p);
                        stop();
                    });
                }catch(Exception error){AndroidUtilities.runOnUIThread(this::fail);}
            });
        }
    }
    private GhostProtectedForward(){}
}
