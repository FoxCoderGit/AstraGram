package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

/** Themed rounded cards shared by GhostGram settings screens. */
public final class GhostSettingsCards {
    private static int dp(float n){return AndroidUtilities.dp(n);}
    public static TextView text(Context c,String value,int size,int key){TextView t=new TextView(c);t.setText(value);t.setTextSize(size);t.setTextColor(Theme.getColor(key));return t;}
    public static GradientDrawable background(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    public static LinearLayout group(Context c){LinearLayout v=new LinearLayout(c);v.setOrientation(LinearLayout.VERTICAL);v.setBackground(background(Theme.getColor(Theme.key_windowBackgroundWhite),16));v.setClipToOutline(true);return v;}
    public static View wrap(Context c,View card){FrameLayout outer=new FrameLayout(c);outer.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));outer.setPadding(dp(16),dp(4),dp(16),dp(8));outer.addView(card,new FrameLayout.LayoutParams(-1,-2));return outer;}
    public static void line(LinearLayout card){View line=new View(card.getContext());line.setBackgroundColor(Theme.getColor(Theme.key_divider));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,1);p.setMargins(dp(16),0,dp(16),0);card.addView(line,p);}
    public static View row(Context c,String title,String subtitle,String value,Boolean checked,int icon,Runnable click){
        LinearLayout row=new LinearLayout(c);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(16),dp(16),dp(16),dp(16));row.setMinimumHeight(dp(64));
        if(click!=null){row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector),2));row.setOnClickListener(v->click.run());row.setFocusable(true);}
        if(icon!=0){ImageView image=new ImageView(c);image.setImageResource(icon);image.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(24),dp(24));p.setMarginEnd(dp(14));row.addView(image,p);}
        LinearLayout labels=new LinearLayout(c);labels.setOrientation(LinearLayout.VERTICAL);row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));labels.addView(text(c,title,16,Theme.key_windowBackgroundWhiteBlackText));
        if(subtitle!=null&&!subtitle.isEmpty()){TextView sub=text(c,subtitle,13,Theme.key_windowBackgroundWhiteGrayText);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(4);labels.addView(sub,p);}
        if(value!=null){TextView right=text(c,value,14,Theme.key_windowBackgroundWhiteGrayText);right.setSingleLine(true);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.setMarginStart(dp(12));row.addView(right,p);}
        if(checked!=null){Switch control=new Switch(c);control.setColors(Theme.key_switchTrack,Theme.key_switchTrackChecked,Theme.key_windowBackgroundWhite,Theme.key_windowBackgroundWhite);control.setChecked(checked,false);control.setClickable(false);control.setFocusable(false);control.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(40),dp(32));p.setMarginStart(dp(16));row.addView(control,p);row.setContentDescription(title+" · "+(checked?"включено":"выключено"));}
        else if(click!=null){TextView arrow=text(c,"›",25,Theme.key_windowBackgroundWhiteGrayText);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(16),-2);p.setMarginStart(dp(10));row.addView(arrow,p);}
        return row;
    }
    public static View status(Context c,boolean ready,String title,String subtitle){
        LinearLayout card=group(c),row=new LinearLayout(c);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(16),dp(18),dp(16),dp(18));
        TextView check=text(c,ready?"✓":"!",25,Theme.key_featuredStickers_buttonText);check.setTextColor(Color.WHITE);check.setGravity(Gravity.CENTER);check.setTypeface(AndroidUtilities.bold());check.setBackground(background(Theme.getColor(ready?Theme.key_windowBackgroundWhiteGreenText:Theme.key_windowBackgroundWhiteBlueText),24));row.addView(check,new LinearLayout.LayoutParams(dp(44),dp(44)));
        LinearLayout labels=new LinearLayout(c);labels.setOrientation(LinearLayout.VERTICAL);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMarginStart(dp(16));row.addView(labels,p);labels.addView(text(c,title,16,Theme.key_windowBackgroundWhiteBlackText));if(subtitle!=null){TextView sub=text(c,subtitle,13,Theme.key_windowBackgroundWhiteGrayText);LinearLayout.LayoutParams s=new LinearLayout.LayoutParams(-1,-2);s.topMargin=dp(4);labels.addView(sub,s);}card.addView(row);return card;
    }
    public static View button(Context c,String title,Runnable click){TextView b=text(c,title,16,Theme.key_featuredStickers_buttonText);b.setGravity(Gravity.CENTER);b.setTypeface(AndroidUtilities.bold());b.setMinHeight(dp(52));b.setBackground(background(Theme.getColor(Theme.key_featuredStickers_addButton),12));b.setOnClickListener(v->click.run());LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(dp(10),0,dp(10),dp(12));b.setLayoutParams(p);return b;}
    public static View brand(Context c,String version){
        LinearLayout block=new LinearLayout(c);block.setOrientation(LinearLayout.VERTICAL);block.setGravity(Gravity.CENTER);block.setPadding(dp(16),dp(18),dp(16),dp(22));
        ImageView icon=new ImageView(c);icon.setPadding(dp(17),dp(17),dp(17),dp(17));icon.setImageResource(R.drawable.ghost_menu_ghost);icon.setColorFilter(Color.WHITE);icon.setBackground(background(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText),40));block.addView(icon,new LinearLayout.LayoutParams(dp(80),dp(80)));
        TextView name=text(c,"AstraGram",22,Theme.key_windowBackgroundWhiteBlackText);name.setTypeface(AndroidUtilities.bold());LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-2,-2);np.topMargin=dp(14);block.addView(name,np);TextView ver=text(c,"Версия "+version,14,Theme.key_windowBackgroundWhiteGrayText);LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(-2,-2);vp.topMargin=dp(6);block.addView(ver,vp);return block;
    }
    private GhostSettingsCards(){}
}
