package org.telegram.ui;

import android.os.Bundle;
import android.view.View;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PerChatQuickReaction;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import java.util.ArrayList;

/** Native Telegram settings rows, avatars and reaction selector. */
public class PerChatReactionsActivity extends UniversalFragment {
    @Override protected CharSequence getTitle() { return LocaleController.getString(R.string.GhostPerChatQuickReaction); }
    @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asButton(1, R.drawable.msg_add, LocaleController.getString(R.string.GhostAddReactionChat)));
        for (java.util.Map.Entry<String, ?> entry : org.telegram.messenger.MessagesController.getEmojiSettings(currentAccount).getAll().entrySet()) {
            if (!entry.getKey().startsWith("ghost_quick_reaction_")) continue;
            long dialog;
            try { dialog = Long.parseLong(entry.getKey().substring("ghost_quick_reaction_".length())); } catch (Exception ignore) { continue; }
            final long id = dialog;
            Object peer = id > 0 ? getMessagesController().getUser(id) : getMessagesController().getChat(-id);
            if (peer == null) continue;
            String title = peer instanceof TLRPC.User ? UserObject.getUserName((TLRPC.User) peer) : ((TLRPC.Chat) peer).title;
            UserCell cell = new UserCell(getContext(), 0, 0, false, getResourceProvider());
            cell.setData(peer, title, String.valueOf(entry.getValue()), 0, true);
            cell.setOnClickListener(v -> presentFragment(new ReactionsDoubleTapManageActivity(currentAccount, id)));
            items.add(UItem.asCustom(cell));
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.GhostPerChatReactionInfo)));
    }
    @Override protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id != 1) return;
        Bundle args = new Bundle(); args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_DEFAULT);
        args.putString("selectAlertString", LocaleController.getString(R.string.GhostChooseChatQuickReaction));
        DialogsActivity picker = new DialogsActivity(args);
        picker.setDelegate((fragment, dids, message, param, notify, date, repeat, topics) -> {
            if (dids == null || dids.isEmpty()) return false;
            presentFragment(new ReactionsDoubleTapManageActivity(currentAccount, dids.get(0).dialogId)); return true;
        });
        presentFragment(picker);
    }
    @Override protected boolean onLongClick(UItem item, View view, int position, float x, float y) { return false; }
    @Override public void onResume() { super.onResume(); if (listView != null) listView.adapter.update(true); }
}
