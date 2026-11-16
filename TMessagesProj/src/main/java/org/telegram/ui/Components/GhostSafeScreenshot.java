package org.telegram.ui.Components;

import android.app.Dialog;
import android.view.Gravity;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.ActionBar.Theme;

/** Detached render: original messages, names, entities and account data are never edited. */
public final class GhostSafeScreenshot {
    public static String redact(String text, String... identities) {
        String result = text == null ? "" : text;
        // Handles before plain names: @alice must become "я", not @я.
        int[] order={3,7,0,4,1,5,2,6};
        for (int i:order) {
            if(i>=identities.length)continue;
            String identity=identities[i];
            if(identity!=null&&!identity.trim().isEmpty())
                result=result.replaceAll("(?iu)"+java.util.regex.Pattern.quote(identity), i<4?"я":"собеседник");
        }
        return result.replaceAll("@[\\p{L}\\p{N}_]+", "[тег]")
                .replaceAll("(?i)(https?://|www\\.)\\S+", "[ссылка]")
                .replaceAll("[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}.-]+\\.[\\p{L}]{2,}", "[почта]")
                .replaceAll("\\+?\\d[\\d ()-]{6,}\\d", "[номер]");
    }
    public static void show(ChatActivity chat, TLRPC.User peer) {
        if(chat.getParentActivity()==null||peer==null)return;
        Dialog dialog=new Dialog(chat.getParentActivity(),android.R.style.Theme_Material_NoActionBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root=new LinearLayout(chat.getParentActivity());root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        TextView close=new TextView(chat.getParentActivity());
        close.setText("‹  Безопасный скриншот · Закрыть");close.setTextSize(18);
        close.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        close.setPadding(20,20,20,20);close.setOnClickListener(v->dialog.dismiss());root.addView(close);
        ScrollView scroll=new ScrollView(chat.getParentActivity());
        LinearLayout rows=new LinearLayout(chat.getParentActivity());rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(16,8,16,16);scroll.addView(rows);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TLRPC.User self=UserConfig.getInstance(chat.getCurrentAccount()).getCurrentUser();
        String[] names={self==null?null:UserObject.getUserName(self),self==null?null:self.first_name,self==null?null:self.last_name,
                self==null||self.username==null?null:"@"+self.username,
                UserObject.getUserName(peer),peer.first_name,peer.last_name,peer.username==null?null:"@"+peer.username};
        ArrayList<MessageObject> messages=new ArrayList<>(chat.messages);
        Collections.sort(messages,Comparator.comparingInt(m->m.messageOwner==null?0:m.messageOwner.date));
        for(MessageObject message:messages){
            if(message.messageOwner==null||message.messageOwner.action!=null||message.getId()==0)continue;
            String text=redact(message.messageOwner.message,names);
            if(text.trim().isEmpty())text="[медиа скрыто]";
            if(message.messageOwner.fwd_from!=null)text="[пересланное сообщение]\n"+text;
            if(message.messageOwner.reply_to!=null)text="[ответ на сообщение]\n"+text;
            TextView bubble=new TextView(chat.getParentActivity());bubble.setTextSize(16);
            bubble.setText((message.isOutOwner()?"я":"собеседник")+"\n"+text);
            bubble.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            bubble.setPadding(16,12,16,12);
            bubble.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12),Theme.getColor(Theme.key_windowBackgroundGray)));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.gravity=message.isOutOwner()?Gravity.RIGHT:Gravity.LEFT;
            lp.topMargin=8;rows.addView(bubble,lp);
        }
        dialog.setContentView(root);dialog.show();
        Window window=dialog.getWindow();if(window!=null){window.setLayout(-1,-1);window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);}
        scroll.post(()->scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }
    private GhostSafeScreenshot(){}
}
