package org.telegram.ui;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.*;
import java.util.ArrayList;
import java.util.Map;

public class GhostFloatingChatsActivity extends UniversalFragment {
    public GhostFloatingChatsActivity(int account) { currentAccount = account; }
    private String text(int id) { return LocaleController.getString(id); }
    @Override protected CharSequence getTitle() { return text(R.string.GhostFloatingChats); }
    @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asRadio(10, text(R.string.GhostBubblesOff)).setChecked(GhostFloatingChats.mode(currentAccount) == 0));
        items.add(UItem.asRadio(11, text(R.string.GhostBubblesAll)).setChecked(GhostFloatingChats.mode(currentAccount) == 1).setEnabled(Build.VERSION.SDK_INT >= 30));
        items.add(UItem.asRadio(12, text(R.string.GhostBubblesSelected)).setChecked(GhostFloatingChats.mode(currentAccount) == 2).setEnabled(Build.VERSION.SDK_INT >= 30));
        items.add(UItem.asShadow(text(R.string.GhostBubblesInfo)));
        items.add(UItem.asHeader(text(R.string.GhostBubblesNotifications)));
        items.add(UItem.asRadio(20, text(R.string.GhostBubblesBoth)).setChecked(!GhostFloatingChats.onlyBubble(currentAccount)));
        items.add(UItem.asRadio(21, text(R.string.GhostBubblesOnly)).setChecked(GhostFloatingChats.onlyBubble(currentAccount)));
        items.add(UItem.asButton(30, R.drawable.msg_settings, text(R.string.GhostBubblesSystemSettings)).setEnabled(Build.VERSION.SDK_INT >= 30));
        items.add(UItem.asButton(31, R.drawable.msg_add, text(R.string.GhostBubblesAddUser)).setEnabled(Build.VERSION.SDK_INT >= 30));
        items.add(UItem.asButton(32, R.drawable.msg_delete, text(R.string.GhostBubblesClose)));
        items.add(UItem.asHeader(text(R.string.GhostBubblesSelected)));
        for (Map.Entry<String, ?> setting : GhostFloatingChats.settings(currentAccount).getAll().entrySet()) {
            if (!setting.getKey().startsWith("ghost_bubble_user_") || !Boolean.TRUE.equals(setting.getValue())) continue;
            long id; try { id = Long.parseLong(setting.getKey().substring("ghost_bubble_user_".length())); } catch (Exception ignored) { continue; }
            TLRPC.User user = getMessagesController().getUser(id);
            UItem row = UItem.asButtonCheck(40, user == null ? Long.toString(id) : UserObject.getUserName(user), text(R.string.GhostBubblesProfile)).setChecked(true);
            row.object = id; items.add(row);
        }
    }
    @Override protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id >= 10 && item.id <= 12) GhostFloatingChats.setMode(currentAccount, item.id - 10);
        else if (item.id == 20 || item.id == 21) GhostFloatingChats.setOnlyBubble(currentAccount, item.id == 21);
        else if (item.id == 30) {
            Intent settings = new Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getContext().getPackageName());
            try { getParentActivity().startActivity(settings); } catch (Exception error) { FileLog.e(error); }
        } else if (item.id == 31) {
            Bundle args = new Bundle(); args.putBoolean("onlySelect", true); args.putBoolean("allowBots", true);
            args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_USERS_ONLY);
            args.putBoolean("allowGroups", false); args.putBoolean("allowChannels", false);
            DialogsActivity picker = new DialogsActivity(args);
            picker.setCurrentAccount(currentAccount);
            picker.setDelegate((fragment, dids, message, param, notify, date, repeat, topics) -> {
                if (dids != null && !dids.isEmpty() && dids.get(0).dialogId > 0) {
                    long id = dids.get(0).dialogId;
                    if (!GhostFloatingChats.selected(currentAccount, id)) GhostFloatingChats.toggle(currentAccount, id);
                    fragment.finishFragment();
                }
                return true;
            }); presentFragment(picker);
        } else if (item.id == 32) GhostFloatingChats.close(currentAccount);
        else if (item.id == 40 && item.object instanceof Long) GhostFloatingChats.toggle(currentAccount, (Long) item.object);
        if (listView != null) listView.adapter.update(true);
    }
    @Override public void onResume() { super.onResume(); if (listView != null) listView.adapter.update(true); }
    @Override protected boolean onLongClick(UItem item, View view, int position, float x, float y) { return false; }
}
