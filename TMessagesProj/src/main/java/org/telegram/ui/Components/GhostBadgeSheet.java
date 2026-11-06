package org.telegram.ui.Components;

import android.content.Context;
import android.view.Gravity;
import android.widget.*;
import org.telegram.messenger.*;
import org.telegram.ui.ActionBar.*;

public final class GhostBadgeSheet extends BottomSheet {
    public GhostBadgeSheet(BaseFragment fragment, GhostBadgeRegistry.Badge badge) {
        super(fragment.getParentActivity(), false, fragment.getResourceProvider()); fixNavigationBar();
        Context context = getContext();
        LinearLayout content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(24), AndroidUtilities.dp(24), AndroidUtilities.dp(16));
        boolean extera=badge.type==GhostBadgeRegistry.Type.EXTERA_SUPPORTER;
        ImageView icon = new ImageView(context);
        if(extera){
            AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable emoji=new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(icon,AndroidUtilities.dp(64));
            emoji.setCurrentAccount(fragment.getCurrentAccount());emoji.set(badge.documentId,false);icon.setImageDrawable(emoji);
            icon.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener(){
                @Override public void onViewAttachedToWindow(android.view.View view){emoji.attach();}
                @Override public void onViewDetachedFromWindow(android.view.View view){emoji.detach();}
            });
        }else icon.setImageDrawable(new GhostBadgeDrawable(badge.documentId));
        icon.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, fragment.getResourceProvider()));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(AndroidUtilities.dp(64), AndroidUtilities.dp(64)); ip.gravity = Gravity.CENTER_HORIZONTAL; content.addView(icon, ip);
        TextView title = GhostSettingsCards.text(context, GhostBadges.description(badge), 20, Theme.key_windowBackgroundWhiteBlackText);
        title.setGravity(Gravity.CENTER); title.setTypeface(AndroidUtilities.bold());
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2); tp.topMargin = AndroidUtilities.dp(16); content.addView(title, tp);
        String explanation = extera ? "Эта отметка означает поддержку проекта exteraGram. Данные получены из публичного API exteraGram."
            : badge.type == GhostBadgeRegistry.Type.DEVELOPER
            ? "Эта галочка принадлежит создателю AstraGram — человеку, который создаёт и развивает приложение."
            : "Эта галочка отмечает человека, который поддержал проект AstraGram. Спасибо за помощь в развитии приложения!";
        TextView body = GhostSettingsCards.text(context, explanation + "\n\nЭто отметка "+(extera?"exteraGram":"AstraGram")+", а не официальная верификация Telegram.", 14, Theme.key_windowBackgroundWhiteGrayText);
        body.setGravity(Gravity.CENTER); LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2); bp.setMargins(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(24)); content.addView(body, bp);
        content.addView(GhostSettingsCards.button(context, "Понятно", this::dismiss));
        ScrollView scroll = new ScrollView(context); scroll.addView(content); setCustomView(scroll);
    }
}
