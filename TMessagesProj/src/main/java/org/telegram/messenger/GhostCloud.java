package org.telegram.messenger;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.util.Base64;
import org.json.JSONObject;
import org.telegram.tgnet.*;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Account-owned photo backup. All protocol operations use this account's session. */
public final class GhostCloud implements NotificationCenter.NotificationCenterDelegate {
    public static final String PREFIX="GGCLOUD1 ", DELETE="GGCLOUD1_DELETE ", TITLE="Облако · AstraGram";
    private static final GhostCloud[] instances=new GhostCloud[UserConfig.MAX_ACCOUNT_COUNT];
    public final int account;
    public final GhostCloudStore store;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private volatile boolean running;
    private volatile boolean verified,readOnly,cleanup;
    private boolean scanBusy,confirmationPending;
    private volatile int generation;
    private int requestId;
    private long started;
    private Runnable finished;
    private TLRPC.Chat channel;
    private MessageObject uploading;
    private static final java.util.concurrent.ConcurrentHashMap<String,GhostCloud> uploadPaths=new java.util.concurrent.ConcurrentHashMap<>();
    private GhostCloudStore.Photo active;
    public volatile String status="Выключено";
    public volatile float progress;
    private final Runnable monitor=new Runnable(){@Override public void run(){
        if(!running)return;
        String waiting=readOnly?readWaitReason():waitReason();
        if(waiting!=null){status=waiting;stop();return;}
        if(System.currentTimeMillis()-started>7*60*1000){status="Продолжим в следующем фоновом запуске";stop();return;}
        ConnectionsManager.getInstance(account).setAppPaused(false,false);
        if(active!=null&&active.stage!=null){long[] sizes=ImageLoader.getInstance().getFileProgressSizes(active.stage);if(sizes!=null&&sizes[1]>0)progress=Math.min(1f,(float)sizes[0]/sizes[1]);}
        AndroidUtilities.runOnUIThread(this,1000);
    }};
    public static synchronized GhostCloud get(int account) {
        UserConfig config=UserConfig.getInstance(account);
        if(!config.isClientActivated()||config.getClientUserId()==0||config.getCurrentUser()!=null&&config.getCurrentUser().bot)return null;
        if(instances[account]==null||instances[account].store.owner!=config.getClientUserId()) {
            if(instances[account]!=null) {
                GhostCloud old=instances[account];old.stopWithoutCompletion();
                NotificationCenter.getInstance(account).removeObserver(old,NotificationCenter.messageReceivedByServer2);
                NotificationCenter.getInstance(account).removeObserver(old,NotificationCenter.messageSendError);
            }
            instances[account]=new GhostCloud(account,config.getClientUserId());
        }
        return instances[account];
    }
    private GhostCloud(int account,long owner) {
        this.account=account;store=new GhostCloudStore(owner);
        NotificationCenter.getInstance(account).addObserver(this,NotificationCenter.messageReceivedByServer2);
        NotificationCenter.getInstance(account).addObserver(this,NotificationCenter.messageSendError);
        String value=store.preferences.getString("channel_blob",null);
        try{if(value!=null){SerializedData s=new SerializedData(Base64.decode(value,Base64.NO_WRAP));channel=TLRPC.Chat.TLdeserialize(s,s.readInt32(true),true);s.cleanup();MessagesController.getInstance(account).putChat(channel,false);}}catch(Exception error){FileLog.e(error);}
    }
    private boolean identity(){return UserConfig.getInstance(account).getClientUserId()==store.owner;}
    public long channelId(){return channel==null?0:channel.id;}
    public static boolean hidden(int account,long dialog){
        if(dialog>=0||!UserConfig.getInstance(account).isClientActivated())return false;
        long owner=UserConfig.getInstance(account).getClientUserId();
        android.content.SharedPreferences prefs=ApplicationLoader.applicationContext.getSharedPreferences("ghost_cloud_"+owner,0);
        long id=prefs.getLong("channel_id",0);
        if(id==0){String blob=prefs.getString("channel_blob",null);if(blob!=null)try{SerializedData data=new SerializedData(Base64.decode(blob,Base64.NO_WRAP));TLRPC.Chat chat=TLRPC.Chat.TLdeserialize(data,data.readInt32(true),true);data.cleanup();id=chat.id;prefs.edit().putLong("channel_id",id).apply();}catch(Exception ignored){}}
        return id!=0&&dialog==-id;
    }
    public static GhostCloudStore.Photo photoFor(MessageObject message){
        if(message==null||!hidden(message.currentAccount,message.getDialogId())||message.messageOwner.message==null||!message.messageOwner.message.startsWith(PREFIX))return null;
        try{GhostCloud c=get(message.currentAccount);if(c==null)return null;JSONObject meta=new JSONObject(message.messageOwner.message.substring(PREFIX.length()));if(meta.optLong("owner")!=c.store.owner)return null;return c.store.get(meta.getString("hash"));}catch(Exception ignored){return null;}
    }
    public String waitReason() {
        GhostCloudStore.Config c=store.config();
        if(!identity())return "Аккаунт вышел из системы";
        if(!c.enabled)return "Выключено";
        if(c.paused)return "Приостановлено пользователем";
        if(!GhostLicenseBridge.allowed())return "Требуется действующая лицензия";
        if(GhostSimulation.active(account))return "Ожидаем завершения симуляции";
        if(!GhostCloudMedia.permission(c.video))return c.video?"Нет доступа к фото и видео":"Нет полного доступа к фотографиям";
        if(!GhostCloudMedia.originalPermission())return "Нужен доступ к исходным метаданным фотографий";
        long flood=store.preferences.getLong("flood",0);
        if(flood>System.currentTimeMillis())return "Ограничение Telegram · ожидание "+((flood-System.currentTimeMillis()+999)/1000)+" с";
        ConnectivityManager manager=(ConnectivityManager)ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities network=manager.getNetworkCapabilities(manager.getActiveNetwork());
        if(network==null||!network.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)||!network.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))return "Нет подключения к интернету";
        if(!c.mobile&&(network.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)||!network.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)))return "Ожидаем Wi-Fi без тарификации";
        if(c.charging) {
            Intent battery=ApplicationLoader.applicationContext.registerReceiver(null,new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int state=battery==null?0:battery.getIntExtra(BatteryManager.EXTRA_PLUGGED,0);
            if(state==0)return "Ожидаем подключение зарядки";
        }
        Calendar time=Calendar.getInstance();int day=(time.get(Calendar.DAY_OF_WEEK)+5)%7;
        if(!GhostCloudPolicy.inWindow(day,time.get(Calendar.HOUR_OF_DAY)*60+time.get(Calendar.MINUTE),c.scheduled,c.days,c.start,c.end))return "Ожидаем выбранный день и время";
        return null;
    }
    public void start(Runnable completion) {
        if(running){completion.run();return;}
        if(!store.config().enabled||store.config().paused){restore(completion);return;}
        String reason=waitReason();if(reason!=null){status=reason;completion.run();return;}
        finished=completion;readOnly=false;cleanup=false;running=true;verified=false;generation++;started=System.currentTimeMillis();
        monitor.run();
        if(channel==null)discover(0,0,0,new TLRPC.TL_inputPeerEmpty());else verify(this::sync);
    }
    public boolean isRunning(){return running;}
    public boolean isRestoring(){return running&&readOnly;}
    public void restore(Runnable completion) {
        if(running){completion.run();return;}
        String reason=readWaitReason();if(reason!=null){status=reason;completion.run();return;}
        readOnly=true;cleanup=false;finished=completion;running=true;verified=false;generation++;started=System.currentTimeMillis();
        monitor.run();if(channel==null)discover(0,0,0,new TLRPC.TL_inputPeerEmpty());else verify(this::sync);
    }
    private String readWaitReason() {
        if(!identity())return "Аккаунт вышел из системы";
        if(!GhostLicenseBridge.allowed())return "Требуется действующая лицензия";
        if(store.preferences.getLong("flood",0)>System.currentTimeMillis())return "Ожидаем снятия ограничения Telegram";
        ConnectivityManager manager=(ConnectivityManager)ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities network=manager.getNetworkCapabilities(manager.getActiveNetwork());
        if(network==null||!network.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))return "Нет подключения к интернету";
        if(!store.config().mobile&&(network.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)||!network.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)))return "Для восстановления списка ожидаем Wi-Fi";
        return null;
    }
    public void stop() {
        if(!running)return;running=false;verified=false;generation++;
        AndroidUtilities.cancelRunOnUIThread(monitor);
        if(requestId!=0)ConnectionsManager.getInstance(account).cancelRequest(requestId,true);
        requestId=0;
        if(uploading!=null&&uploading.getId()<0) {
            TLRPC.Document doc=uploading.getDocument();
            if(uploading.messageOwner.reqId!=0)ConnectionsManager.getInstance(account).cancelRequest(uploading.messageOwner.reqId,true);
            if(doc!=null)FileLoader.getInstance(account).cancelFileUpload(uploading.messageOwner.attachPath,false);
            // Keep the same local message/random_id; do not delete a potentially acknowledged server copy.
        }
        uploading=null;active=null;confirmationPending=false;progress=0;
        if(ApplicationLoader.mainInterfacePaused||UserConfig.selectedAccount!=account)ConnectionsManager.getInstance(account).setAppPaused(true,false);
        Runnable callback=finished;finished=null;if(callback!=null)callback.run();
    }
    public void stopWithoutCompletion(){finished=null;stop();}
    private void fail(String text){status=text;store.preferences.edit().putString("last_error",text).apply();stop();}
    private void rpc(TLObject query,BiConsumer<TLObject,TLRPC.TL_error> callback) {
        if(!running)return;int epoch=generation;
        requestId=ConnectionsManager.getInstance(account).sendRequest(query,(response,error)->AndroidUtilities.runOnUIThread(()->{
            if(!running||generation!=epoch||!identity())return;requestId=0;
            if(error!=null){long delay=GhostCloudPolicy.retryDelay(0,error.text);store.preferences.edit().putLong("flood",System.currentTimeMillis()+delay).commit();fail("Telegram: "+error.text);return;}
            callback.accept(response,null);
        }));
    }
    private TLRPC.TL_inputChannel inputChannel(){TLRPC.TL_inputChannel input=new TLRPC.TL_inputChannel();input.channel_id=channel.id;input.access_hash=channel.access_hash;return input;}
    private TLRPC.TL_inputPeerChannel peer(){TLRPC.TL_inputPeerChannel p=new TLRPC.TL_inputPeerChannel();p.channel_id=channel.id;p.access_hash=channel.access_hash;return p;}
    private boolean privateChat(TLRPC.Chat c) {
        // A cached channel object may temporarily omit `creator`, even for its
        // owner. Ownership is confirmed authoritatively by getParticipants.
        if(c==null||!c.broadcast||c.megagroup||c.left||c.kicked||c.username!=null&&!c.username.isEmpty())return false;
        if(c.usernames!=null)for(TLRPC.TL_username name:c.usernames)if(name.active)return false;
        return true;
    }
    private String marker(){return "AstraGram private photo cloud v1; owner="+store.owner;}
    private String legacyMarker(){return "GhostGram private photo cloud v1; owner="+store.owner;}
    private boolean cloudMarker(String about){return marker().equals(about)||legacyMarker().equals(about);}
    private void migrateBranding(TLRPC.ChatFull info){
        if(channel==null)return;
        if(!TITLE.equals(channel.title))MessagesController.getInstance(account).changeChatTitle(channel.id,TITLE);
        if(!marker().equals(info.about))MessagesController.getInstance(account).updateChatAbout(channel.id,marker(),info);
    }
    private void setChannel(TLRPC.Chat chat) {
        channel=chat;MessagesController.getInstance(account).putChat(chat,false);
        boolean changed=store.preferences.getLong("channel_id",0)!=chat.id;
        SerializedData data=new SerializedData();chat.serializeToStream(data);
        store.preferences.edit().putLong("channel_id",chat.id).putString("channel_blob",Base64.encodeToString(data.toByteArray(),Base64.NO_WRAP)).commit();data.cleanup();
        ArrayList<TLRPC.Chat> chats=new ArrayList<>();chats.add(chat);MessagesStorage.getInstance(account).putUsersAndChats(null,chats,true,true);
        if(changed){MessagesController.getInstance(account).sortDialogs(null);NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);}
    }
    private void discover(int folder,int offset,int date,TLRPC.InputPeer offsetPeer) {
        status="Ищем приватное облако этого аккаунта";
        TLRPC.TL_messages_getDialogs request=new TLRPC.TL_messages_getDialogs();request.flags|=2;request.folder_id=folder;
        request.offset_id=offset;request.offset_date=date;request.offset_peer=offsetPeer;request.limit=100;
        rpc(request,(response,error)->{
            if(!(response instanceof TLRPC.messages_Dialogs)){fail("Не удалось получить список чатов");return;}
            TLRPC.messages_Dialogs dialogs=(TLRPC.messages_Dialogs)response;
            MessagesController.getInstance(account).putChats(dialogs.chats,false);MessagesController.getInstance(account).putUsers(dialogs.users,false);
            ArrayList<TLRPC.Chat> candidates=new ArrayList<>();for(TLRPC.Chat c:dialogs.chats)if(privateChat(c))candidates.add(c);
            findCandidate(candidates,0,()->{
                if(!running)return;
                if(dialogs.dialogs.size()<100){if(folder==0)discover(1,0,0,new TLRPC.TL_inputPeerEmpty());else create();return;}
                TLRPC.Dialog last=dialogs.dialogs.get(dialogs.dialogs.size()-1);long dialog=DialogObject.getPeerDialogId(last.peer);int stamp=0;
                for(TLRPC.Message m:dialogs.messages)if(m.id==last.top_message&&DialogObject.getPeerDialogId(m.peer_id)==dialog){stamp=m.date;break;}
                if(stamp==0){fail("Не удалось продолжить поиск облака");return;}
                discover(folder,last.top_message,stamp,MessagesController.getInstance(account).getInputPeer(dialog));
            });
        });
    }
    private void findCandidate(ArrayList<TLRPC.Chat> list,int index,Runnable none) {
        if(index>=list.size()){none.run();return;}
        TLRPC.Chat candidate=list.get(index);
        TLRPC.TL_channels_getFullChannel request=new TLRPC.TL_channels_getFullChannel();
        TLRPC.TL_inputChannel input=new TLRPC.TL_inputChannel();input.channel_id=candidate.id;input.access_hash=candidate.access_hash;request.channel=input;
        rpc(request,(response,error)->{
            if(response instanceof TLRPC.TL_messages_chatFull && cloudMarker(((TLRPC.TL_messages_chatFull)response).full_chat.about)) {
                setChannel(candidate);verify(this::sync);
            }else findCandidate(list,index+1,none);
        });
    }
    private void create() {
        if(readOnly){fail("Облако этого аккаунта не найдено. Включите копирование, чтобы создать его.");return;}
        status="Создаём приватный канал без приглашений";
        TLRPC.TL_channels_createChannel request=new TLRPC.TL_channels_createChannel();request.broadcast=true;request.title=TITLE;request.about=marker();
        rpc(request,(response,error)->{
            if(!(response instanceof TLRPC.Updates)||((TLRPC.Updates)response).chats.isEmpty()){fail("Канал не создан");return;}
            TLRPC.Updates updates=(TLRPC.Updates)response;
            Utilities.stageQueue.postRunnable(()->MessagesController.getInstance(account).processUpdates(updates,false));
            setChannel(updates.chats.get(0));verify(this::sync);
        });
    }
    private void verify(Runnable ready) {
        status="Проверяем приватность канала";
        TLRPC.TL_channels_getFullChannel full=new TLRPC.TL_channels_getFullChannel();full.channel=inputChannel();
        rpc(full,(response,error)->{
            if(!(response instanceof TLRPC.TL_messages_chatFull)){fail("Канал недоступен");return;}
            TLRPC.TL_messages_chatFull info=(TLRPC.TL_messages_chatFull)response;
            for(TLRPC.Chat c:info.chats)if(c.id==channel.id)channel=c;
            if(!privateChat(channel)||!cloudMarker(info.full_chat.about)){fail("Облако требует приватный канал владельца");return;}
            TLRPC.TL_channels_getParticipants people=new TLRPC.TL_channels_getParticipants();people.channel=inputChannel();people.filter=new TLRPC.TL_channelParticipantsRecent();people.limit=2;
            rpc(people,(r,e)->{
                if(!(r instanceof TLRPC.channels_ChannelParticipants)){fail("Не удалось проверить участников канала. Повторите попытку.");return;}
                if(!GhostCloudParticipants.onlyOwner((TLRPC.channels_ChannelParticipants)r,store.owner)){
                    TLRPC.channels_ChannelParticipants participants=(TLRPC.channels_ChannelParticipants)r;
                    fail(participants.count>1||participants.participants.size()>1?"В канале появились другие участники. Загрузка остановлена.":"Не удалось подтвердить, что в канале только владелец. Повторите попытку.");return;
                }
                migrateBranding(info.full_chat);verified=true;setChannel(channel);ready.run();
            });
        });
    }
    private void sync() {
        status="Восстанавливаем список сохранённых фотографий";
        TLRPC.TL_messages_getHistory history=new TLRPC.TL_messages_getHistory();history.peer=peer();history.limit=100;
        history.offset_id=store.preferences.getInt("sync_offset",0);history.min_id=store.preferences.getInt("sync_floor",0);
        int epoch=generation;
        rpc(history,(response,error)->{
            if(!(response instanceof TLRPC.messages_Messages)){fail("История облака недоступна");return;}
            ArrayList<TLRPC.Message> messages=((TLRPC.messages_Messages)response).messages;
            io.execute(()->{
                try {
                    int top=store.preferences.getInt("sync_top",0),offset=0;
                    for(TLRPC.Message m:messages){top=Math.max(top,m.id);offset=m.id;index(m);}
                    android.content.SharedPreferences.Editor edit=store.preferences.edit();
                    if(messages.size()<100)edit.putInt("sync_floor",Math.max(top,store.preferences.getInt("sync_floor",0))).putInt("sync_offset",0).putInt("sync_top",0).putBoolean("indexed",true);
                    else edit.putInt("sync_offset",offset).putInt("sync_top",top);
                    edit.commit();
                    AndroidUtilities.runOnUIThread(()->{if(running&&generation==epoch){if(messages.size()==100)sync();else scanThenSend();}});
                }catch(Exception failure){AndroidUtilities.runOnUIThread(()->{if(running&&generation==epoch)fail("Не удалось восстановить индекс: "+failure.getMessage());});}
            });
        });
    }
    private void index(TLRPC.Message message)throws Exception {
        if(message.message==null||message.id<=0)return;
        boolean deleted=message.message.startsWith(DELETE);
        if(!deleted&&!message.message.startsWith(PREFIX))return;
        JSONObject meta=new JSONObject(message.message.substring(deleted?DELETE.length():PREFIX.length()));
        if(meta.optLong("owner")!=store.owner||!meta.optString("hash").matches("[0-9a-f]{64}"))return;
        if(!deleted && (message.media==null||message.media.document==null||message.media.document.id==0||message.media.document.size<=0
                ||message.media.document.size!=meta.optLong("size")||!meta.optString("mime").equals(message.media.document.mime_type)
                ||!(meta.optString("mime").startsWith("image/")||meta.optString("mime").startsWith("video/"))))return;
        store.remote(message,meta,deleted);
    }
    public void preview(GhostCloudStore.Config config,Consumer<long[]> callback) {
        io.execute(()->{
            long count=0,size=0;
            try{for(GhostCloudMedia.Source source:GhostCloudMedia.query())if(GhostCloudMedia.selected(source,config)){count++;size+=source.size;}}
            catch(Exception error){AndroidUtilities.runOnUIThread(()->callback.accept(new long[]{-1,0}));return;}
            final long[] result={count,size};AndroidUtilities.runOnUIThread(()->callback.accept(result));
        });
    }
    private void scanThenSend() {
        if(readOnly){cleanup=true;next();return;}
        if(scanBusy){AndroidUtilities.runOnUIThread(()->{if(running&&!readOnly)scanThenSend();},1000);return;}scanBusy=true;status="Проверяем фотографии и дубли";int epoch=generation;
        GhostCloudStore.Config config=store.config();
        io.execute(()->{
            try{
                for(GhostCloudMedia.Source source:GhostCloudMedia.query()) {
                    if(!running||epoch!=generation)break;
                    if(!GhostCloudMedia.selected(source,config))continue;
                    try {
                        String hash=source.revision==null?null:store.seen(source.uri,source.revision);
                        if(hash==null){hash=GhostCloudMedia.hash(source.uri);store.seen(source.uri,source.revision,hash);}
                        GhostCloudStore.Photo existing=store.get(hash);
                        if(existing!=null){if(!GhostCloudStore.DONE.equals(existing.state)&&!GhostCloudStore.DELETED.equals(existing.state)&&existing.id==0){ContentValues v=new ContentValues();v.put("uri",source.uri);store.update(hash,v);}store.clearSourceError(source.uri);continue;}
                        GhostCloudStore.Photo p=new GhostCloudStore.Photo();p.hash=hash;p.uri=source.uri;p.name=source.name;p.mime=source.mime;p.size=source.size;p.taken=source.taken;store.enqueue(p);
                        store.clearSourceError(source.uri);
                    } catch(Exception error) {store.sourceError(source.uri,source.name,error.getMessage());}
                }
                AndroidUtilities.runOnUIThread(()->{scanBusy=false;if(running&&generation==epoch)next();});
            }catch(Exception e){AndroidUtilities.runOnUIThread(()->{scanBusy=false;if(running&&generation==epoch)fail("Сканирование: "+e.getMessage());});}
        });
    }
    private void next() {
        if(!running)return;String reason=readOnly?readWaitReason():waitReason();if(reason!=null){status=reason;stop();return;}
        active=readOnly?store.nextDeletion():store.next();
        confirmationPending=false;
        if(active==null&&readOnly){status="Список облака восстановлен";stop();return;}
        if(active==null){long[] stats=store.statistics();status=stats[1]+stats[2]>0?"Ожидаем повторения загрузок · ошибки доступны в списке":"Все выбранные фотографии сохранены · ожидаем новые";stop();return;}
        if(GhostCloudStore.DELETING.equals(active.state)||active.id>0&&GhostCloudStore.ERROR.equals(active.state)){verify(()->deleteRemote(active));return;}
        verify(()->prepare(active));
    }
    private JSONObject meta(GhostCloudStore.Photo p)throws Exception {
        return new JSONObject().put("v",1).put("owner",store.owner).put("hash",p.hash).put("name",p.name).put("mime",p.mime).put("size",p.size).put("taken",p.taken);
    }
    private HashMap<String,String> parameters(String hash,String kind){HashMap<String,String> p=new HashMap<>();p.put("ghost_cloud_hash",hash);p.put("ghost_cloud_owner",Long.toString(store.owner));p.put("ghost_cloud_kind",kind);return p;}
    private void prepare(GhostCloudStore.Photo photo) {
        status="Подготавливаем оригинал · "+photo.name;int epoch=generation;
        io.execute(()->{
            try{
                File file=GhostCloudMedia.stage(store,photo);photo.stage=file.getAbsolutePath();
                if(!FileLoader.checkUploadFileSize(account,photo.size))throw new java.io.IOException("Файл превышает лимит Telegram");
                uploadPaths.put(photo.stage,this);
                ContentValues v=new ContentValues();v.put("stage",photo.stage);store.update(photo.hash,v);
                TLRPC.TL_document doc=new TLRPC.TL_document();doc.file_reference=new byte[0];doc.date=ConnectionsManager.getInstance(account).getCurrentTime();doc.size=file.length();doc.mime_type=photo.mime;
                TLRPC.TL_documentAttributeFilename name=new TLRPC.TL_documentAttributeFilename();name.file_name=photo.name;doc.attributes.add(name);
                Bitmap bitmap=photo.mime.startsWith("video/")?SendMessagesHelper.createVideoThumbnail(file.getAbsolutePath(),android.provider.MediaStore.Video.Thumbnails.MINI_KIND):ImageLoader.loadBitmap(file.getAbsolutePath(),null,320,320,true);
                if(bitmap!=null){TLRPC.PhotoSize thumb=ImageLoader.scaleAndSaveImage(bitmap,320,320,80,false);bitmap.recycle();if(thumb!=null){doc.thumbs.add(thumb);doc.flags|=1;uploadPaths.put(FileLoader.getInstance(account).getPathToAttach(thumb,true).getAbsolutePath(),this);}}
                String caption=PREFIX+meta(photo).toString();
                AndroidUtilities.runOnUIThread(()->{
                    if(!running||generation!=epoch||waitReason()!=null){if(running)stop();return;}
                    status="Загружаем · "+photo.name;progress=0;
                    TLRPC.Message previous=store.decode(photo.pending);
                    if(previous!=null&&previous.id<0){previous.attachPath=photo.stage;if(previous.media!=null&&previous.media.document!=null){previous.media.document.thumbs=doc.thumbs;previous.media.document.flags=doc.flags;}uploading=new MessageObject(account,previous,false,false);SendMessagesHelper.getInstance(account).retrySendMessage(uploading,false,0);}
                    else{
                        SendMessagesHelper.SendMessageParams params=SendMessagesHelper.SendMessageParams.of(doc,null,photo.stage,-channel.id,null,null,caption,null,null,parameters(photo.hash,"photo"),false,0,0,0,null,null,false);
                        SendMessagesHelper.getInstance(account).sendMessage(params);
                    }
                });
            }catch(Exception e){store.error(photo.hash,e.getMessage()==null?"Фотография недоступна":e.getMessage());AndroidUtilities.runOnUIThread(()->{if(running&&generation==epoch)AndroidUtilities.runOnUIThread(this::next,3000);});}
        });
    }
    public void pause(boolean paused){GhostCloudStore.Config c=store.config();c.paused=paused;store.save(c);if(paused){status="Приостановлено пользователем";stop();}GhostCloudJobs.schedule(account,0);}
    public void delete(String hash) {
        GhostCloudStore.Photo p=store.get(hash);if(p==null||p.id<=0)return;
        ContentValues v=new ContentValues();v.put("state",GhostCloudStore.DELETING);v.putNull("pending");v.put("retry",0);store.update(hash,v);
        GhostCloudJobs.schedule(account,0);
    }
    private void deleteRemote(GhostCloudStore.Photo photo) {
        // A durable tombstone is posted first; reinstall will not re-upload this hash.
        try {
            if(photo.pending==null) {
                SendMessagesHelper.SendMessageParams params=SendMessagesHelper.SendMessageParams.of(DELETE+meta(photo).put("target",photo.id).toString(),-channel.id,null,null,null,false,null,null,parameters(photo.hash,"delete"),false,0,0,null,false);
                SendMessagesHelper.getInstance(account).sendMessage(params);
            } else {
                TLRPC.Message message=store.decode(photo.pending);
                if(message!=null&&message.id<0){uploading=new MessageObject(account,message,false,false);SendMessagesHelper.getInstance(account).retrySendMessage(uploading,false,0);}
                else deleteFileMessage(photo);
            }
        }catch(Exception e){store.error(photo.hash,e.getMessage());AndroidUtilities.runOnUIThread(this::next,3000);}
    }
    private void deleteFileMessage(GhostCloudStore.Photo p) {
        TLRPC.TL_channels_deleteMessages request=new TLRPC.TL_channels_deleteMessages();request.channel=inputChannel();request.id.add(p.id);
        rpc(request,(response,error)->{
            ContentValues v=new ContentValues();v.put("state",GhostCloudStore.DELETED);store.update(p.hash,v);
            if(response instanceof TLRPC.TL_messages_affectedMessages){TLRPC.TL_messages_affectedMessages r=(TLRPC.TL_messages_affectedMessages)response;MessagesController.getInstance(account).processNewChannelDifferenceParams(r.pts,r.pts_count,channel.id);}
            active=null;uploading=null;AndroidUtilities.runOnUIThread(this::next,3000);
        });
    }
    public static boolean cloud(HashMap<String,String> params){return params!=null&&params.containsKey("ghost_cloud_hash");}
    public static boolean uploadAllowed(String path) {
        if(path==null)return true;
        GhostCloud c=uploadPaths.get(path);
        if(c!=null)return c.running&&!c.readOnly&&c.verified&&c.waitReason()==null;
        // A native unsent-message retry after process death must wait for restoration.
        String prefix=new File(ApplicationLoader.applicationContext.getCacheDir(),"cloud_").getAbsolutePath();
        return !path.startsWith(prefix);
    }
    public static boolean keepUploadProgress(String path){
        if(path==null)return false;
        String root=new File(ApplicationLoader.applicationContext.getCacheDir(),"cloud_").getAbsolutePath();
        return (uploadPaths.containsKey(path)||path.startsWith(root))&&!uploadAllowed(path);
    }
    public static boolean cloudRequest(TLObject request) {
        String message=request instanceof TLRPC.TL_messages_sendMedia?((TLRPC.TL_messages_sendMedia)request).message:request instanceof TLRPC.TL_messages_sendMessage?((TLRPC.TL_messages_sendMessage)request).message:null;
        return message!=null&&(message.startsWith(PREFIX)||message.startsWith(DELETE));
    }
    public static boolean requestAllowed(int account,TLObject request) {
        if(!cloudRequest(request))return true;
        GhostCloud c=instances[account];
        TLRPC.InputPeer peer=request instanceof TLRPC.TL_messages_sendMedia?((TLRPC.TL_messages_sendMedia)request).peer:((TLRPC.TL_messages_sendMessage)request).peer;
        boolean deleting=request instanceof TLRPC.TL_messages_sendMessage&&((TLRPC.TL_messages_sendMessage)request).message.startsWith(DELETE);
        return c!=null&&c.running&&(!c.readOnly||c.cleanup&&deleting)&&c.verified&&c.identity()&&(c.readOnly?c.readWaitReason():c.waitReason())==null&&peer instanceof TLRPC.TL_inputPeerChannel&&peer.channel_id==c.channelId();
    }
    public static boolean canSend(int account,HashMap<String,String> params) {
        if(!cloud(params))return true;
        GhostCloud c=get(account);
        boolean deleting="delete".equals(params.get("ghost_cloud_kind"));
        if(c==null||!c.running||c.readOnly&&(!c.cleanup||!deleting)||!c.verified||(c.readOnly?c.readWaitReason():c.waitReason())!=null||!Long.toString(c.store.owner).equals(params.get("ghost_cloud_owner")))return false;
        GhostCloudStore.Photo photo=c.store.get(params.get("ghost_cloud_hash"));
        return photo!=null&&!GhostCloudStore.DELETED.equals(photo.state)&&("delete".equals(params.get("ghost_cloud_kind"))||!GhostCloudStore.DONE.equals(photo.state));
    }
    public static long randomId(int account,HashMap<String,String> params) {
        try{byte[] digest=MessageDigest.getInstance("SHA-256").digest((params.get("ghost_cloud_owner")+":"+params.get("ghost_cloud_kind")+":"+params.get("ghost_cloud_hash")).getBytes(StandardCharsets.UTF_8));long id=ByteBuffer.wrap(digest).getLong();return id==0?1:id;}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    public static void created(int account,MessageObject message) {
        if(!cloud(message.messageOwner.params))return;GhostCloud c=get(account);if(c==null)return;
        if(!Long.toString(c.store.owner).equals(message.messageOwner.params.get("ghost_cloud_owner")))return;
        String hash=message.messageOwner.params.get("ghost_cloud_hash");c.store.pending(hash,message.messageOwner);c.uploading=message;
    }
    public static void sendError(int account,TLRPC.Message message,String error) {
        if(message==null||!cloud(message.params))return;GhostCloud c=get(account);if(c==null)return;
        if(!Long.toString(c.store.owner).equals(message.params.get("ghost_cloud_owner")))return;
        c.store.error(message.params.get("ghost_cloud_hash"),error==null?"Ошибка отправки":error);
    }
    @Override public void didReceivedNotification(int id,int currentAccount,Object... args) {
        if(!identity()||active==null)return;
        int old=(Integer)args[0];GhostCloudStore.Photo p=store.get(active.hash);
        if(p==null||old!=p.localId)return;
        if(id==NotificationCenter.messageSendError) {
            if(!GhostCloudStore.ERROR.equals(p.state))store.error(p.hash,"Ошибка отправки");
            uploading=null;active=null;if(running)AndroidUtilities.runOnUIThread(this::next,3000);return;
        }
        int server=(Integer)args[1];
        if(server<=0||confirmationPending||!running)return;
        confirmationPending=true;
        TLRPC.TL_channels_getMessages request=new TLRPC.TL_channels_getMessages();request.channel=inputChannel();
        request.id.add(server);
        rpc(request,(response,error)->{
            if(!(response instanceof TLRPC.messages_Messages)||((TLRPC.messages_Messages)response).messages.isEmpty()){fail("Ожидаем подтверждение облачной копии");return;}
            TLRPC.Message m=((TLRPC.messages_Messages)response).messages.get(0);
            try{
                if(m.message.startsWith(DELETE)) {
                    ContentValues v=new ContentValues();v.put("pending",GhostCloudStore.encode(m));store.update(p.hash,v);deleteFileMessage(p);return;
                }
                index(m);
                GhostCloudStore.Photo saved=store.get(p.hash);
                if(saved==null||!GhostCloudStore.DONE.equals(saved.state)){fail("Ответ сервера не подтверждает оригинал");return;}
                io.execute(()->GhostCloudMedia.releaseReplicas(account,store,p,m.media.document));
                uploading=null;active=null;progress=0;status="Сохранено · "+p.name;
                if(running)AndroidUtilities.runOnUIThread(this::next,3000);
            }catch(Exception failure){fail("Подтверждение: "+failure.getMessage());}
        });
    }
}
