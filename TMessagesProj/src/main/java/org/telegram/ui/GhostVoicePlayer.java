package org.telegram.ui;

import android.view.Gravity;
import android.widget.*;
import org.telegram.messenger.*;
import org.telegram.messenger.MediaController;
import org.telegram.ui.ActionBar.*;
import org.telegram.ui.Components.GhostPlayerMenu;

/** A native sheet for precise voice seeking, without leaving the conversation. */
public final class GhostVoicePlayer {
    public static void show(BaseFragment f,MessageObject message){
        if(f.getParentActivity()==null||message==null||!message.isVoice()||message.isVoiceOnce())return;
        MediaController media=MediaController.getInstance();
        LinearLayout body=new LinearLayout(f.getParentActivity());body.setOrientation(1);body.setPadding(AndroidUtilities.dp(20),0,AndroidUtilities.dp(20),AndroidUtilities.dp(16));
        TextView time=new TextView(f.getParentActivity());time.setTextSize(16);time.setGravity(Gravity.CENTER);time.setTextColor(Theme.getColor(Theme.key_dialogTextBlack,f.getResourceProvider()));body.addView(time);
        SeekBar seek=new SeekBar(f.getParentActivity());seek.setMax(10000);seek.setContentDescription("Позиция голосового сообщения");body.addView(seek,new LinearLayout.LayoutParams(-1,AndroidUtilities.dp(56)));
        android.content.res.ColorStateList tint=android.content.res.ColorStateList.valueOf(Theme.getColor(Theme.key_dialogTextLink,f.getResourceProvider()));seek.setProgressTintList(tint);seek.setThumbTintList(tint);
        LinearLayout controls=new LinearLayout(f.getParentActivity());body.addView(controls);
        TextView back=button(f,"−5 с"),play=button(f,"Слушать"),forward=button(f,"+5 с");
        for(TextView b:new TextView[]{back,play,forward})controls.addView(b,new LinearLayout.LayoutParams(0,AndroidUtilities.dp(52),1));
        TextView speed=button(f,"Скорость"),tools=button(f,"Усиление · тишина · таймер");body.addView(speed,new LinearLayout.LayoutParams(-1,AndroidUtilities.dp(48)));body.addView(tools,new LinearLayout.LayoutParams(-1,AndroidUtilities.dp(48)));
        TextView hint=new TextView(f.getParentActivity());hint.setText("В чате: ведите по полоске для перемотки. Отведите палец выше или ниже — перемотка станет точнее.");hint.setTextSize(14);hint.setTextColor(Theme.getColor(Theme.key_dialogTextGray,f.getResourceProvider()));body.addView(hint);
        BottomSheet sheet=new BottomSheet.Builder(f.getParentActivity(),false,f.getResourceProvider()).setTitle("Голосовое сообщение").setCustomView(body).create();
        boolean[] dragging={false},closed={false};
        java.util.function.Consumer<Float> jump=p->{
            float target=Math.max(0,Math.min(.9999f,p));message.audioProgress=target;message.audioProgressSec=(int)(target*message.getDuration());
            if(media.isPlayingMessage(message))media.seekToProgress(message,target);
            else message.forceSeekTo=target;
            seek.setProgress(Math.round(target*10000));time.setText(clock(message.audioProgressSec)+" / "+clock((int)message.getDuration()));
        };
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar bar){dragging[0]=true;}
            public void onProgressChanged(SeekBar bar,int p,boolean user){if(user)time.setText(clock((int)(message.getDuration()*p/10000f))+" / "+clock((int)message.getDuration()));}
            public void onStopTrackingTouch(SeekBar bar){jump.accept(bar.getProgress()/10000f);dragging[0]=false;}
        });
        play.setOnClickListener(v->{if(media.isPlayingMessage(message)&&!media.isMessagePaused())media.pauseMessage(message);else media.playMessage(message);});
        back.setOnClickListener(v->jump.accept(message.audioProgress-5f/Math.max(1,(float)message.getDuration())));
        forward.setOnClickListener(v->jump.accept(message.audioProgress+5f/Math.max(1,(float)message.getDuration())));
        speed.setOnClickListener(v->{float current=media.getPlaybackSpeed(false);media.setPlaybackSpeed(false,current<1.25f?1.5f:current<1.75f?2f:1f);});
        tools.setOnClickListener(v->GhostPlayerMenu.show(f.getParentActivity(),f.getResourceProvider()));
        Runnable ticker=new Runnable(){public void run(){if(closed[0])return;if(!dragging[0]){seek.setProgress(Math.round(message.audioProgress*10000));time.setText(clock(message.audioProgressSec)+" / "+clock((int)message.getDuration()));}play.setText(media.isPlayingMessage(message)&&!media.isMessagePaused()?"Пауза":"Слушать");speed.setText("Скорость · "+media.getPlaybackSpeed(false)+"×");AndroidUtilities.runOnUIThread(this,250);}};
        if(f.showDialog(sheet,d->{closed[0]=true;AndroidUtilities.cancelRunOnUIThread(ticker);})!=null)ticker.run();
    }
    private static String clock(int seconds){seconds=Math.max(0,seconds);return seconds>=3600?String.format(java.util.Locale.ROOT,"%d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60):String.format(java.util.Locale.ROOT,"%d:%02d",seconds/60,seconds%60);}
    private static TextView button(BaseFragment f,String text){TextView v=new TextView(f.getParentActivity());v.setText(text);v.setTextSize(16);v.setGravity(Gravity.CENTER);v.setTextColor(Theme.getColor(Theme.key_dialogTextLink,f.getResourceProvider()));v.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(10),0,Theme.getColor(Theme.key_dialogButtonSelector,f.getResourceProvider())));return v;}
}
