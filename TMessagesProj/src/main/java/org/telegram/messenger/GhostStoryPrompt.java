package org.telegram.messenger;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.atomic.AtomicBoolean;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;

/** Called before StoryViewer attaches or records a view. No receipt on cancellation. */
public final class GhostStoryPrompt {
    private GhostStoryPrompt(){}
    public static boolean offer(int account,Context context,long peer,Runnable open){
        if(peer==0||peer==UserConfig.getInstance(account).getClientUserId()||GhostMode.option(account, GhostMode.STORIES))return false;
        dialog(account,context,open,null).show();return true;
    }
    public static AlertDialog dialog(int account,Context context,Runnable open,Runnable cancel){
        LinearLayout card=new LinearLayout(context);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(18),dp(14),dp(18),dp(16));
        FrameLayout header=new FrameLayout(context);header.setMinimumHeight(dp(42));card.addView(header,new LinearLayout.LayoutParams(-1,-2));
        ImageView icon=new ImageView(context);icon.setImageResource(R.drawable.ghost_menu_ghost);icon.setColorFilter(Theme.getColor(Theme.key_dialogTextLink));icon.setScaleType(ImageView.ScaleType.FIT_CENTER);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams iconLayout=new FrameLayout.LayoutParams(dp(26),dp(30),Gravity.LEFT|Gravity.CENTER_VERTICAL);header.addView(icon,iconLayout);
        TextView title=new TextView(context);title.setText("Как открыть историю?");title.setTextSize(18);title.setTypeface(AndroidUtilities.bold());title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));title.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams titleLayout=new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER_VERTICAL);titleLayout.leftMargin=dp(40);titleLayout.rightMargin=dp(38);header.addView(title,titleLayout);
        ImageView close=new ImageView(context);close.setImageResource(R.drawable.msg_close);close.setColorFilter(Theme.getColor(Theme.key_dialogTextGray2));close.setScaleType(ImageView.ScaleType.CENTER);close.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector),1));close.setContentDescription("Закрыть");
        FrameLayout.LayoutParams closeLayout=new FrameLayout.LayoutParams(dp(40),dp(42),Gravity.RIGHT|Gravity.CENTER_VERTICAL);closeLayout.rightMargin=-dp(8);header.addView(close,closeLayout);
        TextView description=new TextView(context);description.setText("Без режима призрака автор может увидеть ваш просмотр.");description.setTextSize(15);description.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));description.setLineSpacing(dp(2),1);
        LinearLayout.LayoutParams descriptionLayout=new LinearLayout.LayoutParams(-1,-2);descriptionLayout.topMargin=dp(6);descriptionLayout.bottomMargin=dp(18);card.addView(description,descriptionLayout);
        LinearLayout actions=new LinearLayout(context);actions.setOrientation(LinearLayout.HORIZONTAL);card.addView(actions,new LinearLayout.LayoutParams(-1,dp(44)));
        TextView normal=button(context,"Без призрака",false),ghost=button(context,"С призраком",true);
        LinearLayout.LayoutParams normalLayout=new LinearLayout.LayoutParams(0,-1,1);normalLayout.rightMargin=dp(6);actions.addView(normal,normalLayout);actions.addView(ghost,new LinearLayout.LayoutParams(0,-1,1));
        AlertDialog result=new AlertDialog.Builder(context).setView(card).setCustomViewOffset(0).setDimAlpha(.45f).create();
        AtomicBoolean choice=new AtomicBoolean();
        normal.setOnClickListener(v->{if(choice.compareAndSet(false,true)){result.dismiss();open.run();}});
        ghost.setOnClickListener(v->{if(choice.compareAndSet(false,true)){GhostMode.setEnabled(account,true);GhostMode.setOption(account,GhostMode.STORIES,true);result.dismiss();open.run();}});
        close.setOnClickListener(v->result.cancel());
        result.setOnCancelListener(d->{if(choice.compareAndSet(false,true)&&cancel!=null)cancel.run();});return result;
    }
    private static int dp(float value){return AndroidUtilities.dp(value);}
    private static TextView button(Context context,String label,boolean primary){
        TextView view=new TextView(context);view.setText(label);view.setTextSize(14);view.setTypeface(AndroidUtilities.bold());view.setGravity(Gravity.CENTER);view.setMaxLines(1);
        view.setTextColor(Theme.getColor(primary?Theme.key_featuredStickers_buttonText:Theme.key_dialogTextBlack));
        GradientDrawable shape=new GradientDrawable();shape.setCornerRadius(dp(10));shape.setColor(Theme.getColor(primary?Theme.key_featuredStickers_addButton:Theme.key_dialogBackground));
        if(!primary)shape.setStroke(dp(1),androidx.core.graphics.ColorUtils.blendARGB(Theme.getColor(Theme.key_dialogTextGray2),Theme.getColor(Theme.key_dialogBackground),.4f));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(Theme.getColor(Theme.key_listSelector)),shape,null));return view;
    }
}
