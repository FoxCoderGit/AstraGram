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
        new Lookup(f,account,id).start();
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
        final java.util.HashSet<String> triedNames=new java.util.HashSet<>();
        final Runnable timeout=()->{failure="Бот не ответил вовремя. Можно повторить поиск позже.";finish(null);};
        final Runnable inlineTimeout=this::onInlineTimeout;
        Lookup(BaseFragment f,int account,long id){this.f=f;this.account=account;this.id=id;owner=UserConfig.getInstance(account).getClientUserId();cm=ConnectionsManager.getInstance(account);mc=MessagesController.getInstance(account);}
        void start(){
            TLRPC.User memory=mc.getUser(id);
            if(usable(memory,id)){profile(f,account,id,true);return;}
            progress=new AlertDialog(f.getParentActivity(),3,f.getResourceProvider());
            progress.setOnCancelListener(d->{if(done.compareAndSet(false,true))cleanup();});
            f.showDialog(progress);AndroidUtilities.runOnUIThread(timeout,15000);
            MessagesStorage.getInstance(account).getStorageQueue().postRunnable(()->{
                TLRPC.User disk=MessagesStorage.getInstance(account).getUser(id);
                AndroidUtilities.runOnUIThread(()->{
                    if(stopped())return;
                    if(usable(disk,id)){mc.putUser(disk,true);finish(disk);return;}
                    fetch(disk!=null?disk:memory);
                });
            });
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
                lookupBot();
            }));
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
                    if(stopped())return;
                    AndroidUtilities.cancelRunOnUIThread(inlineTimeout);
                    if(err!=null||!(result instanceof TLRPC.messages_BotResults)){
                        if(flood(err)){finish(null);return;}
                        failure="Не удалось получить inline-ответ @usinfobot. Повторите поиск позже.";finish(null);return;
                    }
                    TLRPC.messages_BotResults results=(TLRPC.messages_BotResults)result;
                    for(TLRPC.User user:results.users)if(usable(user,id)){save(user);finish(user);return;}
                    StringBuilder text=new StringBuilder();
                    for(TLRPC.BotInlineResult item:results.results){
                        if(text.length()>32768)break;
                        append(text,item.url);append(text,item.description);append(text,item.title);
                        if(item.send_message!=null){
                            append(text,item.send_message.message);
                            for(TLRPC.MessageEntity entity:item.send_message.entities)append(text,entity.url);
                            appendKeyboard(text,item.send_message.reply_markup);
                        }
                    }
                    resolveCandidates(GhostProfileLookupParser.usernames(text.toString()),0);
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
                finish(null);return;
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
            if(!stopped()){failure="Inline-бот не ответил вовремя. Повторите поиск позже.";finish(null);}
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
            if(user!=null){profile(f,account,id,true);return;}
            f.showDialog(new AlertDialog.Builder(f.getParentActivity(),f.getResourceProvider())
                .setTitle("Профиль не найден")
                .setMessage(failure+"\n\nИскомый ID: "+id+". Профиль открывается только после проверки совпадения ID; прежний username мог сменить владельца.")
                .setPositiveButton("Копировать ID",(d,w)->AndroidUtilities.addToClipboard(Long.toString(id)))
                .setNeutralButton("Открыть @usinfobot",(d,w)->Browser.openUrl(f.getParentActivity(),"https://t.me/usinfobot"))
                .setNegativeButton("Закрыть",null).create());
        }
    }
}
