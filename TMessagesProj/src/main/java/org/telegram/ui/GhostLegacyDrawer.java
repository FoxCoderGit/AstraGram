package org.telegram.ui;

import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.text.TextUtils;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.GhostBotRecipients;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

/** Traditional left navigation, using Telegram's existing cells and account actions. */
public final class GhostLegacyDrawer extends FrameLayout {
    private final LaunchActivity activity;
    private final ScrollView panel;
    private boolean closing;
    private float downX;
    private GhostLegacyDrawer(LaunchActivity activity) {
        super(activity); this.activity = activity;
        setBackgroundColor(0x66000000); setClickable(true);
        setOnClickListener(v -> close());
        panel = new ScrollView(activity);
        panel.setFillViewport(true);
        panel.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        panel.setElevation(AndroidUtilities.dp(8));
        int width = Math.max(AndroidUtilities.dp(160),Math.min(AndroidUtilities.dp(320),activity.getResources().getDisplayMetrics().widthPixels-AndroidUtilities.dp(56)));
        addView(panel, new FrameLayout.LayoutParams(width, -1, Gravity.LEFT));
        LinearLayout rows = new LinearLayout(activity); rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, AndroidUtilities.statusBarHeight, 0, AndroidUtilities.navigationBarHeight);
        panel.addView(rows, new ScrollView.LayoutParams(-1, -2));
        int account = UserConfig.selectedAccount;
        org.telegram.tgnet.TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
        // Dimensions and alignment from Telegram 11.4.2's DrawerProfileCell.
        FrameLayout header = new FrameLayout(activity);
        int background = Theme.getColor(Theme.key_chats_menuTopBackground);
        if (background == 0) background = Theme.getColor(Theme.key_chats_menuTopBackgroundCats);
        header.setBackgroundColor(background);
        BackupImageView avatar = new BackupImageView(activity);
        avatar.setRoundRadius(AndroidUtilities.dp(32));
        avatar.setForUserOrChat(self, new AvatarDrawable(self));
        header.addView(avatar, LayoutHelper.createFrame(64, 64, Gravity.LEFT | Gravity.BOTTOM, 16, 0, 0, 67));
        SimpleTextView name = new SimpleTextView(activity);
        name.setText(UserObject.getUserName(self)); name.setTextSize(15); name.setTypeface(AndroidUtilities.bold());
        name.setTextColor(Theme.getColor(Theme.key_chats_menuName));
        header.addView(name, LayoutHelper.createFrame(-1, 24, Gravity.LEFT | Gravity.BOTTOM, 16, 0, 16, 28));
        TextView phone = new TextView(activity);
        phone.setText(self != null && !TextUtils.isEmpty(self.phone) ? org.telegram.PhoneFormat.PhoneFormat.getInstance().format("+" + self.phone) : "");
        phone.setTextSize(13); phone.setSingleLine(true); phone.setTextColor(Theme.getColor(Theme.key_chats_menuPhone));
        header.addView(phone, LayoutHelper.createFrame(-1, 20, Gravity.LEFT | Gravity.BOTTOM, 16, 0, 16, 9));
        rows.addView(header, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(176)));
        header.setOnClickListener(v -> {
            Bundle args = new Bundle(); args.putLong("user_id", UserConfig.getInstance(account).getClientUserId()); args.putBoolean("my_profile", true);
            open(new ProfileActivity(args));
        });
        for (int i : org.telegram.messenger.GhostAccountOrder.accounts()) {
            if (!UserConfig.isValidAccount(i) || !UserConfig.getInstance(i).isClientActivated() || i == account) continue;
            final int target = i;
            UserCell cell = new UserCell(activity, 8, 0, false);
            org.telegram.tgnet.TLRPC.User user = UserConfig.getInstance(i).getCurrentUser();
            cell.setData(user, UserObject.getUserName(user), user != null && user.bot ? "Бот" : (user != null ? "+" + user.phone : ""), 0);
            rows.addView(cell, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(64)));
            cell.setOnClickListener(v -> { close(); activity.switchToAccount(target, true); });
            cell.setOnLongClickListener(v -> { open(new GhostAccountsOrderActivity());return true; });
        }
        if(UserConfig.getActivatedAccountsCount()>1)row(rows,"Порядок аккаунтов",R.drawable.msg_customize,()->open(new GhostAccountsOrderActivity()));
        if (UserConfig.getActivatedPhoneAccountsCount() < UserConfig.MAX_PHONE_ACCOUNTS) {
            row(rows, LocaleController.getString(R.string.AddAccount), R.drawable.msg_add, () -> {
                for (int i = 0; i < UserConfig.MAX_PHONE_ACCOUNTS; i++) {
                    if (!UserConfig.getInstance(i).isClientActivated()) { open(new LoginActivity(i)); break; }
                }
            });
        }
        if (UserConfig.getAvailableBotAccount() >= 0) {
            row(rows, LocaleController.getString(R.string.GhostAddBotAccount), R.drawable.msg_addbot,
                () -> open(new BotLoginActivity(UserConfig.getAvailableBotAccount())));
        }
        row(rows, LocaleController.getString(R.string.NewGroup), R.drawable.msg_groups, () -> open(new GroupCreateActivity(new Bundle())));
        row(rows, LocaleController.getString(R.string.Contacts), R.drawable.msg_contacts, () -> {
            if (GhostBotRecipients.isBot(account)) open(new GhostBotChatsActivity(account, true));
            else { Bundle args = new Bundle(); args.putBoolean("needPhonebook", true); open(new ContactsActivity(args)); }
        });
        row(rows, LocaleController.getString(R.string.Calls), R.drawable.msg_calls, () -> open(new CallLogActivity()));
        row(rows, LocaleController.getString(R.string.SavedMessages), R.drawable.msg_saved, () -> {
            Bundle args = new Bundle(); args.putLong("user_id", UserConfig.getInstance(account).getClientUserId()); open(new ChatActivity(args));
        });
        row(rows, LocaleController.getString(R.string.Settings), R.drawable.msg_settings, () -> open(new SettingsActivity(new Bundle())));
        row(rows, "Загрузки", R.drawable.msg_download, () -> open(new GhostDownloadsActivity(account)));
        row(rows, "Фоновые задачи", R.drawable.ghost_menu_tasks, () -> open(new GhostTasksActivity()));
        if (!GhostBotRecipients.isBot(account)) row(rows, "Облако", R.drawable.ghost_menu_cloud, () -> open(new GhostCloudActivity(account)));
        row(rows, "Плагины", R.drawable.msg_customize, () -> open(new GhostPluginsActivity()));
    }
    private void row(LinearLayout rows, String title, int icon, Runnable action) {
        TextCell cell = new TextCell(getContext()); cell.setTextAndIcon(title, icon, false);
        cell.setBackground(Theme.getSelectorDrawable(false));
        rows.addView(cell, new LinearLayout.LayoutParams(-1, -2)); cell.setOnClickListener(v -> action.run());
    }
    private void open(BaseFragment next) {
        close(); BaseFragment current = activity.getActionBarLayout().getLastFragment();
        if (current != null) current.presentFragment(next);
    }
    public static void show(LaunchActivity activity) {
        if (!activity.isGhostDrawerHome() || activity.ghostLegacyDrawer != null) return;
        GhostLegacyDrawer drawer = new GhostLegacyDrawer(activity); activity.ghostLegacyDrawer = drawer;
        activity.drawerLayoutContainer.addView(drawer, new FrameLayout.LayoutParams(-1, -1));
        drawer.panel.setTranslationX(-AndroidUtilities.dp(320));
        drawer.panel.post(()->drawer.panel.animate().translationX(0).setDuration(220).start());
        org.telegram.messenger.AndroidUtilities.runOnUIThread(()->{if(!drawer.closing&&activity.ghostLegacyDrawer==drawer)drawer.panel.setTranslationX(0);},300);
    }
    public void close() {
        if (closing) return; closing = true;
        panel.animate().translationX(-panel.getWidth()).setDuration(180).withEndAction(() -> {
            activity.drawerLayoutContainer.removeView(this);
            if (activity.ghostLegacyDrawer == this) activity.ghostLegacyDrawer = null;
        }).start();
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) downX = event.getX();
        if (event.getActionMasked() == MotionEvent.ACTION_UP && downX - event.getX() > AndroidUtilities.dp(80)) { close(); return true; }
        return super.dispatchTouchEvent(event);
    }
}
