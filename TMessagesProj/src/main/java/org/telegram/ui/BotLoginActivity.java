package org.telegram.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.telegram.messenger.*;
import org.telegram.tgnet.*;
import org.telegram.ui.ActionBar.*;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import java.util.ArrayList;

/** The bot has its own reserved MTProto session, separate from five phone slots. */
public class BotLoginActivity extends BaseFragment {
    private int requestId;
    public BotLoginActivity() { this(UserConfig.getAvailableBotAccount()); }
    public BotLoginActivity(int account) { currentAccount = account >= UserConfig.BOT_ACCOUNT && account < UserConfig.MAX_ACCOUNT_COUNT ? account : UserConfig.BOT_ACCOUNT; }
    @Override public View createView(Context context) {
        actionBar.setTitle(LocaleController.getString(R.string.GhostAddBotAccount));
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        LinearLayout layout = new LinearLayout(context); layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(24), AndroidUtilities.dp(24), 0);
        layout.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        TextView help = new TextView(context); help.setText(LocaleController.getString(R.string.GhostBotLoginInfo));
        help.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText)); help.setTextSize(14);
        layout.addView(help, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 20));
        EditTextBoldCursor token = new EditTextBoldCursor(context); token.setSingleLine(true); token.setTextSize(16);
        token.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        token.setCursorColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        token.setHint("123456:ABC…"); token.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText));
        token.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(token, LayoutHelper.createLinear(-1, 48));
        TextView button = new TextView(context); button.setText(LocaleController.getString(R.string.Continue));
        button.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText)); button.setGravity(Gravity.CENTER);
        button.setTextSize(16); button.setTypeface(AndroidUtilities.bold());
        button.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(8), getThemedColor(Theme.key_featuredStickers_addButton)));
        layout.addView(button, LayoutHelper.createLinear(-1, 48, 0, 24, 0, 0));
        button.setOnClickListener(v -> {
            if (requestId != 0) return;
            String value = token.getText().toString().trim();
            if (!value.matches("[0-9]+:[A-Za-z0-9_-]+")) { token.setError(LocaleController.getString(R.string.GhostInvalidBotToken)); return; }
            if (getUserConfig().isClientActivated()) { finishFragment(); return; }
            AndroidUtilities.hideKeyboard(token); button.setEnabled(false);
            GhostBotAuthorization request = new GhostBotAuthorization(); request.token = value;
            requestId = getConnectionsManager().sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                requestId = 0; button.setEnabled(true); token.setText("");
                if (getParentActivity() == null) return;
                if (error != null || !(response instanceof TLRPC.TL_auth_authorization) || !((TLRPC.TL_auth_authorization) response).user.bot) {
                    showDialog(new AlertDialog.Builder(getParentActivity()).setTitle(LocaleController.getString(R.string.ErrorOccurred))
                        .setMessage(error == null ? LocaleController.getString(R.string.GhostInvalidBotToken) : error.text)
                        .setPositiveButton(LocaleController.getString(R.string.OK), null).create()); return;
                }
                TLRPC.User user = ((TLRPC.TL_auth_authorization) response).user;
                getMessagesController().cleanup(); getUserConfig().clearConfig();
                getConnectionsManager().setUserId(user.id);
                getUserConfig().syncContacts = false; getUserConfig().setCurrentUser(user); getUserConfig().saveConfig(true);
                getMessagesStorage().cleanup(true);
                ArrayList<TLRPC.User> users = new ArrayList<>(); users.add(user);
                getMessagesStorage().putUsersAndChats(users, null, true, true); getMessagesController().putUser(user, false);
                if (getParentActivity() instanceof LaunchActivity) ((LaunchActivity) getParentActivity()).switchToAccount(currentAccount, true);
                finishFragment();
            }), ConnectionsManager.RequestFlagWithoutLogin | ConnectionsManager.RequestFlagFailOnServerErrors);
            getConnectionsManager().bindRequestToGuid(requestId, classGuid);
        });
        return fragmentView = layout;
    }
    @Override public void onFragmentDestroy() {
        if (requestId != 0) getConnectionsManager().cancelRequest(requestId, true);
        super.onFragmentDestroy();
    }
}
