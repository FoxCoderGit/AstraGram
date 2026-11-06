package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ImageView;
import org.telegram.messenger.*;
import org.telegram.ui.ActionBar.*;
import org.telegram.ui.Components.LayoutHelper;

/** Normal Telegram chat UI backed by a virtual, permanently non-networkable peer. */
public final class GhostSimulationActivity extends ChatActivity {
    private final GhostSimulation.Session simulation;
    private boolean approved;
    private static Bundle args(long peer) { Bundle args = new Bundle(); args.putLong("user_id", peer); return args; }
    public GhostSimulationActivity(GhostSimulation.Session simulation) {
        super(args(simulation.peer)); setCurrentAccount(simulation.account); this.simulation = simulation; simulation.owner = this;
    }
    public static void open(ChatActivity source) {
        GhostSimulation.Session session = GhostSimulation.begin(source);
        if (session != null && !source.presentFragment(new GhostSimulationActivity(session))) GhostSimulation.end(session);
    }
    private String text(int id) { return LocaleController.getString(id); }
    @Override public View createView(Context context) {
        View view = super.createView(context);
        LinearLayout banner = new LinearLayout(context); banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(8), 0);
        banner.setBackgroundColor(Theme.getColor(Theme.key_chat_topPanelBackground, getResourceProvider()));
        TextView label = new TextView(context); label.setText(text(R.string.GhostSimBanner)); label.setTextSize(12);
        label.setTextColor(Theme.getColor(Theme.key_chat_topPanelTitle, getResourceProvider()));
        banner.addView(label, new LinearLayout.LayoutParams(0, -1, 1)); label.setGravity(Gravity.CENTER_VERTICAL);
        ImageView settings = new ImageView(context); settings.setImageResource(R.drawable.msg_settings); settings.setScaleType(ImageView.ScaleType.CENTER);
        settings.setContentDescription(text(R.string.GhostSimSettings)); settings.setColorFilter(Theme.getColor(Theme.key_chat_topPanelTitle, getResourceProvider()));
        banner.addView(settings, new LinearLayout.LayoutParams(AndroidUtilities.dp(40), -1)); settings.setOnClickListener(v -> settings());
        TextView end = new TextView(context); end.setText(text(R.string.GhostSimEnd)); end.setTextSize(13); end.setGravity(Gravity.CENTER);
        end.setTextColor(Theme.getColor(Theme.key_chat_topPanelTitle, getResourceProvider()));
        banner.addView(end, new LinearLayout.LayoutParams(-2, -1)); end.setOnClickListener(v -> confirmEnd());
        ((ViewGroup) view).addView(banner, LayoutHelper.createFrame(-1, 48, Gravity.TOP));
        refreshSimulationHeader(); return view;
    }
    private void settings() {
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider()).setTitle(text(R.string.GhostSimSettings))
            .setItems(new CharSequence[]{(GhostSimulation.save(currentAccount) ? "✓ " : "") + text(R.string.GhostSimSave),
                (GhostSimulation.rough(currentAccount) ? "✓ " : "") + text(R.string.GhostSimRough), text(R.string.GhostSimAbout)}, (dialog, which) -> {
                if (which < 2) MessagesController.getMainSettings(currentAccount).edit().putBoolean(which == 0 ? "ghost_sim_save" : "ghost_sim_rough",
                    which == 0 ? !GhostSimulation.save(currentAccount) : !GhostSimulation.rough(currentAccount)).apply();
                else showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider()).setTitle(text(R.string.GhostSimulation))
                    .setMessage(text(R.string.GhostSimExplanation)).setPositiveButton(LocaleController.getString(R.string.OK), null).create());
            }).create());
    }
    private void confirmEnd() {
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider()).setTitle(text(R.string.GhostSimEndQuestion))
            .setMessage(text(R.string.GhostSimEndInfo)).setPositiveButton(text(R.string.GhostSimEnd), (dialog, which) -> {
                approved = true; super.finishFragment();
            }).setNegativeButton(LocaleController.getString(R.string.Cancel), null).create());
    }
    @Override public boolean finishFragment(boolean animated) { if (!approved) { confirmEnd(); return false; } return super.finishFragment(animated); }
    @Override public boolean closeLastFragment() { if (!approved) { confirmEnd(); return true; } return false; }
    @Override public boolean onBackPressed(boolean invoked) {
        if (!super.onBackPressed(invoked)) return false;
        if (approved) return true;
        if (invoked) confirmEnd();
        return false;
    }
    @Override public boolean canBeginSlide() { return false; }
    @Override public void onResume() { super.onResume(); simulation.paused = false; simulation.idle(); refreshSimulationHeader(); }
    @Override public void onPause() { simulation.paused = true; simulation.cancel(); super.onPause(); }
    @Override public void onFragmentDestroy() { GhostSimulation.end(simulation); super.onFragmentDestroy(); }
    @Override public void didReceivedNotification(int id, int account, Object... args) { super.didReceivedNotification(id, account, args); refreshSimulationHeader(); }
    public void refreshSimulationHeader() {
        if (avatarContainer == null) return;
        avatarContainer.setTitle(UserObject.getUserName(simulation.user), simulation.user.scam, simulation.user.fake,
            simulation.user.verified, simulation.user.premium, simulation.user.emoji_status, false);
        avatarContainer.getAvatarImageView().setVisibility(simulation.blocked ? View.INVISIBLE : View.VISIBLE);
        if (simulation.blocked) avatarContainer.setSubtitle(LocaleController.getString(R.string.ALongTimeAgo));
        else if (simulation.typing) avatarContainer.setSubtitle(LocaleController.getString(R.string.GhostSimTyping));
        else avatarContainer.updateSubtitle();
        avatarContainer.setSimulationTyping(simulation.typing);
    }
    @Override public void updateTitle(boolean animated) {
        super.updateTitle(animated);
        if (simulation != null) refreshSimulationHeader();
    }
}
