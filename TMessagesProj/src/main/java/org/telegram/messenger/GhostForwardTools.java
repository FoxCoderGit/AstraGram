package org.telegram.messenger;

import android.os.Bundle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.AlertsCreator;

/** Built-in selection and history forwarding, inspired by NoForwardLimit (@zwylair). */
public final class GhostForwardTools {
    public static int selectionLimit() { return Integer.MAX_VALUE; }

    public static void forwardChat(ChatActivity source, boolean hideAuthor) {
        if (source == null || source.getParentActivity() == null) return;
        long peer = source.getDialogId();
        if (DialogObject.isEncryptedDialog(peer) || GhostSimulationPolicy.virtual(peer)
                || MessagesController.getInstance(source.getCurrentAccount()).isPeerNoForwards(peer)) {
            AlertsCreator.showSimpleAlert(source, LocaleController.getString(R.string.GhostForwardUnavailable));
            return;
        }
        new AlertDialog.Builder(source.getParentActivity(), source.getResourceProvider())
                .setTitle(LocaleController.getString(R.string.GhostForwardAll))
                .setMessage(LocaleController.getString(R.string.GhostForwardAllConfirm))
                .setPositiveButton(LocaleController.getString(R.string.Continue), (dialog, which) -> new History(source, hideAuthor).load(0))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null).show();
    }

    private static final class History {
        final ChatActivity source;
        final int account;
        final long peer;
        final boolean hideAuthor;
        final ArrayList<MessageObject> messages = new ArrayList<>();
        final HashSet<Integer> seen = new HashSet<>();
        final AlertDialog progress;
        boolean canceled;
        int requestId;
        History(ChatActivity source, boolean hideAuthor) {
            this.source = source; this.hideAuthor = hideAuthor;
            account = source.getCurrentAccount(); peer = source.getDialogId();
            progress = new AlertDialog(source.getParentActivity(), 3, source.getResourceProvider());
            progress.setOnCancelListener(dialog -> {
                canceled = true;
                if (requestId != 0) Connections(account).cancelRequest(requestId, true);
                messages.clear();
            });
            progress.showDelayed(250);
        }
        void load(int offset) {
            if (canceled || source.getParentActivity() == null) { progress.dismiss(); return; }
            TLObject request;
            if (source.getTopicId() != 0) {
                TLRPC.TL_messages_search search = new TLRPC.TL_messages_search();
                search.peer = MessagesController.getInstance(account).getInputPeer(peer);
                search.q = ""; search.filter = new TLRPC.TL_inputMessagesFilterEmpty();
                search.limit = 100; search.offset_id = offset;
                search.flags |= 2; search.top_msg_id = (int) source.getTopicId();
                request = search;
            } else {
                TLRPC.TL_messages_getHistory history = new TLRPC.TL_messages_getHistory();
                history.peer = MessagesController.getInstance(account).getInputPeer(peer);
                history.limit = 100; history.offset_id = offset; request = history;
            }
            requestId = Connections(account).sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (canceled) return;
                if (error != null || !(response instanceof TLRPC.messages_Messages)) {
                    canceled = true; progress.dismiss();
                    if (source.getParentActivity() != null) AlertsCreator.showSimpleAlert(source, error == null ? LocaleController.getString(R.string.ErrorOccurred) : error.text);
                    return;
                }
                TLRPC.messages_Messages page = (TLRPC.messages_Messages) response;
                MessagesController.getInstance(account).putUsers(page.users, false);
                MessagesController.getInstance(account).putChats(page.chats, false);
                int oldest = offset == 0 ? Integer.MAX_VALUE : offset;
                int added = 0;
                for (TLRPC.Message message : page.messages) {
                    if (message.id <= 0) continue;
                    oldest = Math.min(oldest, message.id);
                    if (seen.add(message.id)) {
                        added++;
                        if (message instanceof TLRPC.TL_message && !message.noforwards) messages.add(new MessageObject(account, message, false, true));
                    }
                }
                if (!page.messages.isEmpty() && added > 0 && oldest != offset) {
                    final int next = oldest;
                    AndroidUtilities.runOnUIThread(() -> load(next), 350);
                } else { progress.dismiss(); pick(); }
            }));
        }
        void pick() {
            if (canceled || source.getParentActivity() == null) return;
            if (messages.isEmpty()) { AlertsCreator.showSimpleAlert(source, LocaleController.getString(R.string.GhostForwardEmpty)); return; }
            Collections.reverse(messages);
            Bundle args = new Bundle();
            args.putBoolean("onlySelect", true); args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
            args.putInt("messagesCount", messages.size());
            DialogsActivity picker = new DialogsActivity(args); picker.setCurrentAccount(account);
            picker.setDelegate((fragment, destinations, comment, param, notify, scheduleDate, repeat, topics) -> {
                if (destinations.size() != 1 || destinations.get(0).topicId != 0) return false;
                long target = destinations.get(0).dialogId;
                AlertsCreator.ensurePaidMessageConfirmation(account, target, messages.size(), payStars -> {
                    fragment.finishFragment();
                    new AlertDialog.Builder(source.getParentActivity(), source.getResourceProvider())
                        .setTitle(LocaleController.getString(R.string.GhostForwardAll))
                        .setMessage(LocaleController.formatString(R.string.GhostForwardCount, messages.size()))
                        .setPositiveButton(LocaleController.getString(R.string.Send), (dialog, which) -> send(target, notify, scheduleDate, payStars))
                        .setNegativeButton(LocaleController.getString(R.string.Cancel), null).show();
                });
                return true;
            });
            source.presentFragment(picker);
        }
        void send(long target, boolean notify, int schedule, long payStars) {
            // Native helper already emits protocol batches of at most 100 messages.
            // Keep a whole album together at a batch boundary.
            AlertDialog sending = new AlertDialog(source.getParentActivity(), 3, source.getResourceProvider());
            sending.setOnCancelListener(dialog -> canceled = true);
            sending.show();
            sendBatch(target, notify, schedule, payStars, 0, sending);
        }
        void sendBatch(long target, boolean notify, int schedule, long payStars, int start, AlertDialog sending) {
            if (canceled || start >= messages.size()) { sending.dismiss(); messages.clear(); return; }
            int end = Math.min(start + 100, messages.size());
            while (end < messages.size() && messages.get(end - 1).getGroupId() != 0 && messages.get(end - 1).getGroupId() == messages.get(end).getGroupId()) end++;
            int result = SendMessagesHelper.getInstance(account).sendMessage(new ArrayList<>(messages.subList(start, end)), target, hideAuthor, false, notify, schedule, payStars);
            if (result != 0) {
                sending.dismiss(); canceled = true;
                AlertsCreator.showSimpleAlert(source, LocaleController.getString(R.string.GhostForwardUnavailable)); return;
            }
            final int next = end;
            AndroidUtilities.runOnUIThread(() -> sendBatch(target, notify, schedule, payStars, next, sending), 2500);
        }
    }
    private static org.telegram.tgnet.ConnectionsManager Connections(int account) {
        return org.telegram.tgnet.ConnectionsManager.getInstance(account);
    }
}
