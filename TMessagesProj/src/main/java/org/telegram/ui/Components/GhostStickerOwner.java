package org.telegram.ui.Components;

import android.os.Bundle;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.telegram.messenger.*;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;
import org.telegram.tgnet.tl.TL_keyboard;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ProfileActivity;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.AlertDialog;

/** Original-creator hint decoded like AyuGram; current ownership is not guaranteed. */
public final class GhostStickerOwner {
    private static final java.util.HashMap<Integer, Lookup> botJobs = new java.util.HashMap<>();
    private GhostStickerOwner() { }
    public static void show(BaseFragment supplied,int account,TLRPC.StickerSet set) {
        BaseFragment f=supplied!=null?supplied:LaunchActivity.getLastFragment();
        if(f==null||f.getParentActivity()==null||set==null)return;
        if(set.creator){profile(f,account,UserConfig.getInstance(account).getClientUserId(),false);return;}
        long id=GhostStickerOwnerId.extract(set.id);
        if(set.official||id<=0){
            f.showDialog(new AlertDialog.Builder(f.getParentActivity(),f.getResourceProvider())
                .setTitle("Владелец пака").setMessage("Не удалось определить создателя этого пака.")
                .setPositiveButton("ОК",null).create());return;
        }
        new Lookup(f,account,id,true,true).start();
    }

    public static boolean openProfileLink(BaseFragment fragment, int account, long id) {
        if (fragment == null || fragment.getParentActivity() == null || fragment.isFinished
                || id <= 0 || !UserConfig.getInstance(account).isClientActivated()) return false;
        if (id == UserConfig.getInstance(account).getClientUserId()) {
            profile(fragment, account, id, false);
        } else {
            new Lookup(fragment, account, id, false, false).start();
        }
        return true;
    }
    private static boolean usable(TLRPC.User user,long id){return GhostBotReplyPolicy.matches(user,id);}
    private static void profile(BaseFragment f,int account,long id,boolean candidate){
        Bundle args=new Bundle();args.putLong("user_id",id);
        ProfileActivity profile=new ProfileActivity(args);profile.setCurrentAccount(account);
        f.presentFragment(profile);
        if(candidate)AndroidUtilities.runOnUIThread(()->{
            if(LaunchActivity.getLastFragment()==profile && profile.getParentActivity()!=null)
                BulletinFactory.of(profile).createErrorBulletin("Возможный создатель по ID пака. Текущий владелец не подтверждён.").show();
        },500);
    }
    private static final class Lookup {
        final BaseFragment f;final int account;final long id;final AtomicBoolean done=new AtomicBoolean();
        final ConnectionsManager cm;final MessagesController mc;
        AlertDialog progress;int token;
        TLRPC.User resolvedBot;
        String failure="Бот не вернул подтверждённый профиль.";
        final long owner;
        final boolean stickerCandidate;
        final boolean allowBot;
        boolean searchedBotHistory;
        final java.util.HashSet<String> triedNames=new java.util.HashSet<>();
        final Runnable timeout=()->{failure="Бот не ответил вовремя. Можно повторить поиск позже.";finish(null);};
        final Runnable inlineTimeout=this::onInlineTimeout;
        Lookup(BaseFragment f,int account,long id,boolean stickerCandidate,boolean allowBot){
            this.f=f;this.account=account;this.id=id;this.stickerCandidate=stickerCandidate;this.allowBot=allowBot;
            owner=UserConfig.getInstance(account).getClientUserId();
            cm=ConnectionsManager.getInstance(account);mc=MessagesController.getInstance(account);
        }
        void start(){
            TLRPC.User memory=mc.getUser(id);
            if(usable(memory,id)){profile(f,account,id,stickerCandidate);return;}
            showProgress();
            AndroidUtilities.runOnUIThread(timeout,15000);
            MessagesStorage.getInstance(account).getStorageQueue().postRunnable(()->{
                TLRPC.User disk=MessagesStorage.getInstance(account).getUser(id);
                AndroidUtilities.runOnUIThread(()->{
                    if(stopped())return;
                    if(usable(disk,id)){mc.putUser(disk,true);finish(disk);return;}
                    fetch(disk!=null?disk:memory);
                });
            });
        }
        void showProgress() {
            progress = new AlertDialog(f.getParentActivity(), 3, f.getResourceProvider());
            progress.setOnCancelListener(dialog -> { if (done.compareAndSet(false, true)) cleanup(); });
            f.showDialog(progress);
        }
        void fetch(TLRPC.User known){
            TLRPC.TL_users_getUsers request=new TLRPC.TL_users_getUsers();
            TLRPC.TL_inputUser input=new TLRPC.TL_inputUser();input.user_id=id;
            if(known!=null&&!known.min)input.access_hash=known.access_hash;
            request.id.add(input);
            token=cm.sendRequest(request,(response,error)->AndroidUtilities.runOnUIThread(()->{
                if(stopped())return;
                if(error==null&&response instanceof Vector){
                    for(Object value:((Vector<?>)response).objects)if(value instanceof TLRPC.User&&usable((TLRPC.User)value,id)){
                        TLRPC.User user=(TLRPC.User)value;mc.putUser(user,false);
                        ArrayList<TLRPC.User> users=new ArrayList<>();users.add(user);MessagesStorage.getInstance(account).putUsersAndChats(users,null,true,true);
                        finish(user);return;
                    }
                }
                if(flood(error)){finish(null);return;}
                if (allowBot) lookupBot();
                else offerBotLookup();
            }));
        }
        void offerBotLookup() {
            if (!done.compareAndSet(false, true)) return;
            cleanup();
            if (f.getParentActivity() == null || f.isFinished) return;
            f.showDialog(new AlertDialog.Builder(f.getParentActivity(), f.getResourceProvider())
                    .setTitle("Поиск профиля")
                    .setMessage("Telegram не вернул профиль по этому ID. Проверить его через inline-поиск @usinfobot и существующие ответы бота?")
                    .setPositiveButton("Найти через бота", (dialog, which) -> {
                        if (!f.isFinished && f.getParentActivity() != null
                                && UserConfig.getInstance(account).getClientUserId() == owner) {
                            Lookup lookup = new Lookup(f, account, id, false, true);
                            AndroidUtilities.runOnUIThread(() -> {
                                if (lookup.stopped() || f.getParentActivity() == null) return;
                                lookup.showProgress();
                                lookup.lookupBot();
                            });
                        }
                    })
                    .setNegativeButton("Отмена", null).create());
        }
        void lookupBot(){
            if(stopped())return;
            Lookup existing=botJobs.get(account);
            if(existing!=null&&existing!=this&&!existing.done.get()){
                failure="Поиск через бота уже выполняется для этого аккаунта.";finish(null);return;
            }
            botJobs.put(account,this);
            AndroidUtilities.cancelRunOnUIThread(timeout);AndroidUtilities.runOnUIThread(timeout,30000);
            TLRPC.TL_contacts_resolveUsername request=new TLRPC.TL_contacts_resolveUsername();request.username="usinfobot";
            token=cm.sendRequest(request,(response,error)->AndroidUtilities.runOnUIThread(()->{
                if(stopped())return;
                if(error!=null||!(response instanceof TLRPC.TL_contacts_resolvedPeer)){finish(null);return;}
                TLRPC.TL_contacts_resolvedPeer resolved=(TLRPC.TL_contacts_resolvedPeer)response;
                TLRPC.User bot=null;
                for(TLRPC.User user:resolved.users)if(resolved.peer!=null&&user.id==resolved.peer.user_id&&user.bot&&!user.min)bot=user;
                if(bot==null){finish(null);return;}
                resolvedBot=bot;mc.putUser(bot,false);
                TLRPC.TL_messages_getInlineBotResults inline=new TLRPC.TL_messages_getInlineBotResults();
                inline.bot=mc.getInputUser(bot);inline.peer=new TLRPC.TL_inputPeerSelf();
                inline.query=Long.toString(id);inline.offset="";
                AndroidUtilities.runOnUIThread(inlineTimeout,10000);
                token=cm.sendRequest(inline,(result,err)->AndroidUtilities.runOnUIThread(()->{
                    if (stopped() || searchedBotHistory) return;
                    AndroidUtilities.cancelRunOnUIThread(inlineTimeout);
                    if(err!=null||!(result instanceof TLRPC.messages_BotResults)){
                        if(flood(err)){finish(null);return;}
                        searchExistingBotReplies();return;
                    }
                    TLRPC.messages_BotResults results=(TLRPC.messages_BotResults)result;
                    StringBuilder text=new StringBuilder();
                    for(TLRPC.User user:results.users) {
                        if (usable(user,id)) { save(user);finish(user);return; }
                        if (user.id == id) {
                            String username = UserObject.getPublicUsername(user, false);
                            if (username != null && !username.isEmpty()) append(text, "@" + username);
                        }
                    }
                    for(TLRPC.BotInlineResult item:results.results){
                        if(text.length()>32768)break;
                        append(text,item.url);append(text,item.description);append(text,item.title);
                        if(item.send_message!=null){
                            append(text,item.send_message.message);
                            append(text,item.send_message.url);
                            for(TLRPC.MessageEntity entity:item.send_message.entities)append(text,entity.url);
                            appendKeyboard(text,item.send_message.reply_markup);
                        }
                    }
                    resolveCandidates(GhostProfileLookupParser.usernames(text.toString()),0);
                }));
            }));
        }
        /** Read existing replies if inline mode returned no usable profile. This never sends a message. */
        void searchExistingBotReplies() {
            if (stopped()) return;
            if (resolvedBot == null || searchedBotHistory) { finish(null); return; }
            searchedBotHistory = true;
            TLRPC.TL_messages_search request = new TLRPC.TL_messages_search();
            request.peer = mc.getInputPeer(resolvedBot.id);
            request.q = Long.toString(id);
            request.filter = new TLRPC.TL_inputMessagesFilterEmpty();
            request.limit = 10;
            token = cm.sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (stopped()) return;
                if (error != null || !(response instanceof TLRPC.messages_Messages)) {
                    flood(error);finish(null);return;
                }
                TLRPC.messages_Messages history = (TLRPC.messages_Messages) response;
                for (TLRPC.User user : history.users) {
                    if (usable(user,id)) { save(user);finish(user);return; }
                }
                StringBuilder hints = new StringBuilder();
                int contextMessageId = 0;
                for (TLRPC.Message reply : history.messages) {
                    if (!GhostBotReplyPolicy.fresh(reply, resolvedBot.id, 0)) continue;
                    append(hints, reply.message);
                    if (reply.entities != null) {
                        for (TLRPC.MessageEntity entity : reply.entities) append(hints, entity.url);
                    }
                    boolean targetButton = appendKeyboard(hints, reply.reply_markup);
                    boolean forwardedTarget = reply.fwd_from != null && reply.fwd_from.from_id != null
                            && reply.fwd_from.from_id.user_id == id;
                    if (contextMessageId == 0 && (forwardedTarget || targetButton || GhostBotReplyPolicy.mentions(reply,id))) {
                        contextMessageId = reply.id;
                    }
                }
                ArrayList<String> names = GhostProfileLookupParser.usernames(hints.toString());
                if (contextMessageId == 0) { resolveCandidates(names,0);return; }
                TLRPC.TL_inputUserFromMessage input = new TLRPC.TL_inputUserFromMessage();
                input.peer = request.peer;
                input.msg_id = contextMessageId;
                input.user_id = id;
                TLRPC.TL_users_getUsers lookup = new TLRPC.TL_users_getUsers();
                lookup.id.add(input);
                token = cm.sendRequest(lookup, (result, lookupError) -> AndroidUtilities.runOnUIThread(() -> {
                    if (stopped()) return;
                    if (result instanceof Vector) {
                        for (Object value : ((Vector<?>) result).objects) {
                            if (value instanceof TLRPC.User && usable((TLRPC.User) value,id)) {
                                TLRPC.User user = (TLRPC.User) value;
                                save(user);finish(user);return;
                            }
                        }
                    }
                    if (flood(lookupError)) finish(null);
                    else resolveCandidates(names,0);
                }));
            }));
        }
        void append(StringBuilder text,String value){if(value!=null){text.append(value,0,Math.min(value.length(),4096));text.append('\n');}}
        boolean appendKeyboard(StringBuilder text,TLRPC.ReplyMarkup markup){
            boolean target=false;
            if(markup instanceof TLRPC.TL_replyInlineMarkup)for(TL_keyboard.KeyboardInlineButtonRow row:((TLRPC.TL_replyInlineMarkup)markup).rows)
                for(TL_keyboard.KeyboardInlineButton button:row.buttons)if(button.type instanceof TL_keyboard.TL_inlineButtonTypeUrl){
                    String url=((TL_keyboard.TL_inlineButtonTypeUrl)button.type).url;append(text,url);
                    target|=GhostBotReplyPolicy.profileLink(url,id);
                }
            return target;
        }
        void resolveCandidates(ArrayList<String> names,int index){
            if(stopped())return;
            if(index>=names.size()){
                searchExistingBotReplies();return;
            }
            if(!triedNames.add(names.get(index))){resolveCandidates(names,index+1);return;}
            TLRPC.TL_contacts_resolveUsername request=new TLRPC.TL_contacts_resolveUsername();request.username=names.get(index);
            token=cm.sendRequest(request,(response,error)->AndroidUtilities.runOnUIThread(()->{
                if(stopped())return;
                if(error==null&&response instanceof TLRPC.TL_contacts_resolvedPeer){
                    TLRPC.TL_contacts_resolvedPeer resolved=(TLRPC.TL_contacts_resolvedPeer)response;
                    if(resolved.peer!=null&&resolved.peer.user_id==id)for(TLRPC.User user:resolved.users)
                        if(usable(user,id)){save(user);finish(user);return;}
                }
                if(flood(error)){finish(null);return;}
                resolveCandidates(names,index+1);
            }));
        }
        boolean stopped(){
            if(done.get())return true;
            if(f.isFinished||UserConfig.getInstance(account).getClientUserId()!=owner){
                if(done.compareAndSet(false,true))cleanup();return true;
            }
            return false;
        }
        void onInlineTimeout(){
            if (!stopped()) {
                if (token != 0) cm.cancelRequest(token,true);
                searchExistingBotReplies();
            }
        }
        boolean flood(TLRPC.TL_error error){
            if(error!=null&&error.text!=null&&error.text.startsWith("FLOOD_")){
                failure="Telegram ограничил частоту запросов. Повторите поиск позже.";return true;
            }
            return false;
        }
        void cleanup(){
            AndroidUtilities.cancelRunOnUIThread(timeout);
            AndroidUtilities.cancelRunOnUIThread(inlineTimeout);
            if(token!=0)cm.cancelRequest(token,true);
            if(botJobs.get(account)==this)botJobs.remove(account);
            if(progress!=null)progress.dismiss();
        }
        void save(TLRPC.User user){
            mc.putUser(user,false);ArrayList<TLRPC.User> users=new ArrayList<>();users.add(user);
            MessagesStorage.getInstance(account).putUsersAndChats(users,null,true,true);
        }
        void finish(TLRPC.User user){
            if(!done.compareAndSet(false,true))return;
            cleanup();if(f.getParentActivity()==null||f.isFinished||UserConfig.getInstance(account).getClientUserId()!=owner)return;
            if(user!=null){profile(f,account,id,stickerCandidate);return;}
            f.showDialog(new AlertDialog.Builder(f.getParentActivity(),f.getResourceProvider())
                .setTitle("Профиль не найден")
                .setMessage(failure+"\n\nИскомый ID: "+id+". Профиль открывается только после проверки совпадения ID; прежний username мог сменить владельца.")
                .setPositiveButton("Копировать ID",(d,w)->AndroidUtilities.addToClipboard(Long.toString(id)))
                .setNeutralButton("Открыть @usinfobot",(d,w)->Browser.openUrl(f.getParentActivity(),"https://t.me/usinfobot"))
                .setNegativeButton("Закрыть",null).create());
        }
    }
}
