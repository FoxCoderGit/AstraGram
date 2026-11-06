package org.telegram.ui.Components;

import android.content.Context;
import android.view.*;
import android.widget.*;
import android.text.TextUtils;
import java.io.File;
import java.util.ArrayList;
import java.util.regex.*;
import org.telegram.messenger.*;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.ActionBar.*;

/** Optional inline suggestion; it never intercepts the normal Telegram send button. */
public final class GhostVideoSuggestion {
    private final ChatActivity chat;
    private final ChatActivityEnterView input;
    private final FrameLayout parent;
    private final LinearLayout banner;
    private final TextView title,subtitle,close;
    private String url="",dismissed="";
    private int generation;
    private boolean destroyed,busy;
    private GhostVideoDownloader.Job job;
    private GhostBackgroundTasks.Task backgroundTask;
    private Runnable delayed;
    public GhostVideoSuggestion(ChatActivity chat,ChatActivityEnterView input,FrameLayout parent){
        this.chat=chat;this.input=input;this.parent=parent;
        Context context=parent.getContext();
        banner=new LinearLayout(context);banner.setGravity(Gravity.CENTER_VERTICAL);banner.setPadding(dp(16),dp(10),dp(8),dp(10));
        ImageView icon=new ImageView(context);icon.setImageResource(R.drawable.msg_video);icon.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));banner.addView(icon,new LinearLayout.LayoutParams(dp(30),dp(30)));
        LinearLayout labels=new LinearLayout(context);labels.setOrientation(LinearLayout.VERTICAL);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMarginStart(dp(14));banner.addView(labels,lp);
        title=GhostSettingsCards.text(context,"Видео по ссылке",16,Theme.key_windowBackgroundWhiteBlackText);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);labels.addView(title);
        subtitle=GhostSettingsCards.text(context,"Отправить как видео (до 720p)",13,Theme.key_windowBackgroundWhiteGrayText);subtitle.setMaxLines(2);subtitle.setEllipsize(TextUtils.TruncateAt.END);labels.addView(subtitle);
        close=GhostSettingsCards.text(context,"×",26,Theme.key_windowBackgroundWhiteGrayText);close.setGravity(Gravity.CENTER);close.setContentDescription("Оставить ссылку / отменить загрузку");banner.addView(close,new LinearLayout.LayoutParams(dp(40),dp(44)));
        banner.setBackground(GhostSettingsCards.background(Theme.getColor(Theme.key_windowBackgroundWhite),22));
        banner.setVisibility(View.GONE);parent.addView(banner,LayoutHelper.createFrame(-1,68,Gravity.TOP,7,0,7,0));
        banner.setOnClickListener(v->start());
        close.setOnClickListener(v->{if(job!=null)job.cancel();busy=false;job=null;dismissed=url;show(false);});
    }
    private static int dp(float value){return AndroidUtilities.dp(value);}
    public void textChanged(CharSequence text){
        if(delayed!=null)AndroidUtilities.cancelRunOnUIThread(delayed);
        int check=++generation;
        if(!GhostOptionalFeatures.enabled(GhostOptionalFeatures.VIDEO)){
            if(job!=null)job.cancel();job=null;busy=false;show(false);return;
        }
        if(busy)return;
        if(!GhostOptionalFeatures.enabled(GhostOptionalFeatures.VIDEO)||input.isEditingMessage()||!ChatObject.canSendVideo(chat.getCurrentChat())){show(false);return;}
        Matcher matcher=Pattern.compile("https?://[^\\s<>]+",Pattern.CASE_INSENSITIVE).matcher(text==null?"":text);
        String candidate=matcher.find()?matcher.group().replaceAll("[.,;!?\\)\\]]+$",""):"";
        if(candidate.isEmpty()){dismissed="";show(false);return;}
        if(candidate.equals(dismissed)){show(false);return;}
        if(candidate.equals(url)&&banner.getVisibility()==View.VISIBLE)return;
        show(false);
        delayed=()->GhostVideoDownloader.supports(candidate,supported->{if(destroyed||check!=generation||busy)return;if(supported){url=candidate;title.setText(siteTitle(candidate));subtitle.setText("Отправить как видео (360–720p)");show(true);}});
        AndroidUtilities.runOnUIThread(delayed,400);
    }
    private String siteTitle(String link){String host=android.net.Uri.parse(link).getHost();return host!=null&&(host.contains("youtube.com")||host.equals("youtu.be"))?"Видео с YouTube":"Видео по ссылке";}
    private void show(boolean visible){
        banner.setVisibility(visible?View.VISIBLE:View.GONE);
        FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)input.getLayoutParams();lp.topMargin=visible?dp(76):0;input.setLayoutParams(lp);parent.requestLayout();
        chat.onGhostVideoSuggestionChanged();
    }
    public int visibleHeight(){return banner.getVisibility()==View.VISIBLE?dp(76):0;}
    private void start(){
        if(busy||destroyed||!GhostOptionalFeatures.enabled(GhostOptionalFeatures.VIDEO))return;
        if(chat.getParentActivity()==null||!ChatObject.canSendVideo(chat.getCurrentChat()))return;
        final int account=chat.getCurrentAccount();
        final long dialogId=chat.getDialogId();
        final String fieldText=input.getFieldText()==null?"":input.getFieldText().toString();
        // The link has become an attachment: do not leave it in the composer or
        // send it again as the video's caption. Any text around the link stays
        // available to the user and is used as the caption.
        final String caption=fieldText.replace(url, "").trim();
        dismissed=url;
        input.setFieldText(caption);
        show(false);
        backgroundTask=GhostBackgroundTasks.create("Загрузка видео",()->{if(job!=null)job.cancel();});
        final GhostBackgroundTasks.Task task=backgroundTask;
        busy=true;title.setText("Загрузка видео");subtitle.setText("Получение информации…");
        job=GhostVideoDownloader.download(url,account,new GhostVideoDownloader.Listener(){
            @Override public void progress(String text){task.progress(text,-1);if(!destroyed){int newline=text.indexOf('\n');if(newline>=0){title.setText(text.substring(0,newline));subtitle.setText(text.substring(newline+1));}else subtitle.setText(text);}}
            @Override public void ready(File video){
                busy=false;job=null;if(!destroyed)show(false);
                if(!GhostOptionalFeatures.enabled(GhostOptionalFeatures.VIDEO)){GhostVideoDownloader.discard(video);task.finish("Отменено",null);return;}
                ArrayList<SendMessagesHelper.SendingMediaInfo> media=new ArrayList<>();SendMessagesHelper.SendingMediaInfo item=new SendMessagesHelper.SendingMediaInfo();
                item.path=video.getAbsolutePath();item.caption=caption;item.isVideo=true;item.canDeleteAfter=true;media.add(item);
                SendMessagesHelper.prepareSendingMedia(AccountInstance.getInstance(account),media,dialogId,null,null,null,null,false,false,null,true,0,0,0,false,null,null,0,false,0,0,null);
                task.finish("Видео добавлено в отправку",null);
            }
            @Override public void failed(String message){busy=false;job=null;task.finish("Не удалось загрузить видео",null);if(destroyed)return;title.setText("Не удалось загрузить видео");subtitle.setText("Нажмите, чтобы повторить · × — оставить ссылку");if(chat.getParentActivity()!=null)chat.showDialog(new AlertDialog.Builder(chat.getParentActivity(),chat.getResourceProvider()).setTitle("yt-dlp").setMessage(message).setPositiveButton("OK",null).create());}
        });
    }
    public void destroy(){destroyed=true;generation++;if(delayed!=null)AndroidUtilities.cancelRunOnUIThread(delayed);}
}
