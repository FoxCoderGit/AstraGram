package org.telegram.ui;

import android.os.Bundle;
import android.content.Context;
import android.view.View;
import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.Components.*;
import java.util.ArrayList;
import java.util.Locale;

/** Native peer rows and search over the bot's locally known conversations. */
public class GhostBotChatsActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {
    private String query = "";
    private final boolean embedded;
    public GhostBotChatsActivity(int account) { this(account, false); }
    public GhostBotChatsActivity(int account, boolean embedded) { currentAccount = account; this.embedded = embedded; }
    @Override public boolean onFragmentCreate() {
        if (!super.onFragmentCreate() || !GhostBotRecipients.isBot(currentAccount)) return false;
        GhostBotRecipients.seedCachedDialogs(currentAccount);
        getNotificationCenter().addObserver(this, NotificationCenter.dialogsNeedReload);
        getNotificationCenter().addObserver(this, NotificationCenter.messagesDidLoad);
        return true;
    }
    @Override protected CharSequence getTitle() { return LocaleController.getString(R.string.GhostBotChats); }
    @Override public View createView(Context context) {
        View view = super.createView(context);
        if (embedded) actionBar.setBackButtonDrawable(null);
        actionBar.createMenu().addItem(0, R.drawable.outline_header_search).setIsSearchField(true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override public void onTextChanged(android.widget.EditText text) { query = text.getText().toString().trim().toLowerCase(Locale.ROOT); refresh(); }
            @Override public void onSearchCollapse() { query = ""; refresh(); }
        }).setSearchFieldHint(LocaleController.getString(R.string.Search));
        GhostBotRecipients.loadPeers(currentAccount, this::refresh);
        return view;
    }
    private void refresh() { if (listView != null && !isFinished) listView.adapter.update(true); }
    @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        int found = 0;
        for (long dialog : GhostBotRecipients.known(currentAccount)) {
            Object peer = dialog > 0 ? getMessagesController().getUser(dialog) : getMessagesController().getChat(-dialog);
            if (peer instanceof TLRPC.User && (((TLRPC.User) peer).deleted || ((TLRPC.User) peer).bot)) continue;
            if (peer instanceof TLRPC.Chat && !ChatObject.canWriteToChat((TLRPC.Chat) peer)) continue;
            String title = peer instanceof TLRPC.User ? UserObject.getUserName((TLRPC.User) peer) : peer instanceof TLRPC.Chat ? ((TLRPC.Chat) peer).title : Long.toString(dialog);
            if (!query.isEmpty() && !title.toLowerCase(Locale.ROOT).contains(query) && !Long.toString(dialog).contains(query)) continue;
            UserCell cell = new UserCell(getContext(), 0, 0, false, getResourceProvider());
            cell.setData(peer, title, peer instanceof TLRPC.Chat ? LocaleController.formatPluralString("Groups", 1) : LocaleController.getString(R.string.GhostBotPrivateChat), 0, true);
            cell.setOnClickListener(v -> {
                Bundle args = new Bundle(); args.putLong(dialog > 0 ? "user_id" : "chat_id", Math.abs(dialog));
                ChatActivity chat = new ChatActivity(args); chat.setCurrentAccount(currentAccount);
                presentFragment(chat);
            });
            items.add(UItem.asCustom(cell)); found++;
        }
        if (found == 0) items.add(UItem.asShadow(LocaleController.getString(R.string.GhostBotChatsEmpty)));
        items.add(UItem.asShadow(LocaleController.getString(R.string.GhostBotChatsInfo)));
    }
    @Override protected void onClick(UItem item, View view, int position, float x, float y) {}
    @Override protected boolean onLongClick(UItem item, View view, int position, float x, float y) { return false; }
    @Override public void didReceivedNotification(int id, int account, Object... args) { GhostBotRecipients.seedCachedDialogs(currentAccount); refresh(); }
    @Override public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.dialogsNeedReload);
        getNotificationCenter().removeObserver(this, NotificationCenter.messagesDidLoad);
        super.onFragmentDestroy();
    }
}
