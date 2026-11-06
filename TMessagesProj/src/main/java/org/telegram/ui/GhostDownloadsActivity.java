package org.telegram.ui;

import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ProgressBar;
import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.*;
import java.util.ArrayList;
import java.util.HashMap;

/** A simple management screen backed by Telegram's actual download queue and history. */
public class GhostDownloadsActivity extends UniversalFragment {
    private final HashMap<String, long[]> samples = new HashMap<>();
    private final HashMap<String, LinearLayout> rows = new HashMap<>();
    private boolean resumed;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if (listView != null) listView.adapter.update(false);
            AndroidUtilities.runOnUIThread(this, 1000);
        }
    };
    public GhostDownloadsActivity(int account) { currentAccount = account; }
    private String text(int id) { return LocaleController.getString(id); }
    @Override protected CharSequence getTitle() { return text(R.string.GhostDownloads); }
    @Override public boolean onFragmentCreate() {
        getDownloadController().loadDownloadingFiles(); return super.onFragmentCreate();
    }
    @Override public void onResume() { super.onResume(); resumed = true; tick.run(); }
    @Override public void onPause() { resumed = false; AndroidUtilities.cancelRunOnUIThread(tick); super.onPause(); }
    @Override public void onFragmentDestroy() { resumed = false; AndroidUtilities.cancelRunOnUIThread(tick); super.onFragmentDestroy(); }
    @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(text(R.string.GhostDownloading)));
        for (MessageObject message : new ArrayList<>(getDownloadController().downloadingFiles)) add(items, message, false);
        if (getDownloadController().downloadingFiles.isEmpty()) items.add(UItem.asShadow(text(R.string.GhostNoDownloads)));
        items.add(UItem.asHeader(text(R.string.GhostCompleted)));
        for (MessageObject message : new ArrayList<>(getDownloadController().recentDownloadingFiles)) add(items, message, true);
        if (getDownloadController().recentDownloadingFiles.isEmpty()) items.add(UItem.asShadow(text(R.string.GhostNoDownloads)));
    }
    private void add(ArrayList<UItem> items, MessageObject message, boolean completed) {
        TLRPC.Document document = message.getDocument();
        if (document == null) return;
        boolean loading = getFileLoader().isLoadingFile(message.getFileName());
        long[] progress = ImageLoader.getInstance().getFileProgressSizes(message.getFileName());
        long now = SystemClock.elapsedRealtime();
        long[] previous = samples.get(message.getFileName());
        long downloaded = completed ? document.size : progress != null ? progress[0] : previous != null ? previous[0]
            : getMessagesController().getMainSettings(currentAccount).getLong("ghost_download_progress_" + message.getFileName(), 0);
        long speed = 0;
        if (previous != null && now > previous[1] && downloaded >= previous[0] && loading)
            speed = (downloaded - previous[0]) * 1000 / (now - previous[1]);
        samples.put(message.getFileName(), new long[]{downloaded, now});
        int percent = document.size > 0 ? (int) Math.min(100, downloaded * 100 / document.size) : 0;
        String status = completed ? text(R.string.GhostCompleted) : loading ? text(R.string.GhostDownloading) : text(R.string.GhostPaused);
        String subtitle = AndroidUtilities.formatFileSize(downloaded) + " / " + AndroidUtilities.formatFileSize(document.size)
                + " · " + percent + "%\n" + status + (loading ? " · " + AndroidUtilities.formatFileSize(speed) + "/" + text(R.string.GhostSecondShort) : "");
        LinearLayout row = rows.get(message.getFileName());
        if (row == null) {
        row = new LinearLayout(getContext()); row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(12), AndroidUtilities.dp(20), AndroidUtilities.dp(12));
        TextView title = new TextView(getContext()); title.setTextSize(16); title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END); row.addView(title);
        TextView detail = new TextView(getContext()); detail.setTextSize(13); detail.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        row.addView(detail);
            ProgressBar bar = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleHorizontal);
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText)));
            bar.setProgress(percent); row.addView(bar, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(4)));
        row.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        rows.put(message.getFileName(), row);
        }
        ((TextView) row.getChildAt(0)).setText(FileLoader.getDocumentFileName(document).isEmpty() ? message.getFileName() : FileLoader.getDocumentFileName(document));
        ((TextView) row.getChildAt(1)).setText(subtitle);
        ProgressBar progressBar = (ProgressBar) row.getChildAt(2);
        progressBar.setProgress(percent); progressBar.setVisibility(completed ? View.GONE : View.VISIBLE);
        row.setOnClickListener(v -> actions(message, completed));
        items.add(UItem.asCustom(row));
    }
    private void actions(MessageObject message, boolean completed) {
        boolean loading = getFileLoader().isLoadingFile(message.getFileName());
        CharSequence[] options = completed ? new CharSequence[]{text(R.string.GhostOpenFile), text(R.string.GhostGoToMessage), text(R.string.GhostDeleteFile)}
            : new CharSequence[]{text(loading ? R.string.GhostPauseDownload : R.string.GhostResumeDownload), text(R.string.GhostDownloadFirst), text(R.string.GhostCancelDownload), text(R.string.GhostGoToMessage)};
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider()).setTitle(message.getFileName()).setItems(options, (dialog, which) -> {
            if (completed) {
                if (which == 0) AndroidUtilities.openForView(message, getParentActivity(), getResourceProvider(), false);
                else if (which == 1) openMessage(message);
                else confirmRemove(message);
            } else {
                if (which == 0) {
                    if (loading) {
                        MessagesController.getMainSettings(currentAccount).edit().putBoolean("ghost_download_paused_" + message.getFileName(), true).apply();
                        long[] sizes = ImageLoader.getInstance().getFileProgressSizes(message.getFileName());
                        if (sizes != null) MessagesController.getMainSettings(currentAccount).edit().putLong("ghost_download_progress_" + message.getFileName(), sizes[0]).apply();
                        getFileLoader().cancelLoadFile(message.getDocument(), false);
                    }
                    else {
                        MessagesController.getMainSettings(currentAccount).edit().remove("ghost_download_paused_" + message.getFileName()).apply();
                        getFileLoader().loadFile(message.getDocument(), message, FileLoader.PRIORITY_NORMAL_UP, 0);
                    }
                } else if (which == 1) {
                    MessagesController.getMainSettings(currentAccount).edit().remove("ghost_download_paused_" + message.getFileName()).apply();
                    for (MessageObject other : new ArrayList<>(getDownloadController().downloadingFiles))
                        if (other != message && getFileLoader().isLoadingFile(other.getFileName()))
                            getFileLoader().loadFile(other.getDocument(), other, FileLoader.PRIORITY_NORMAL, 0);
                    getFileLoader().loadFile(message.getDocument(), message, FileLoader.PRIORITY_HIGH, 0);
                }
                else if (which == 2) remove(message);
                else openMessage(message);
            }
            if (listView != null) listView.adapter.update(true);
        }).create());
    }
    private void confirmRemove(MessageObject message) {
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider()).setTitle(text(R.string.GhostDeleteFile))
            .setMessage(text(R.string.GhostDeleteFileInfo)).setPositiveButton(text(R.string.GhostDeleteFile), (dialog, which) -> remove(message))
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null).create());
    }
    private void remove(MessageObject message) {
        ArrayList<MessageObject> files = new ArrayList<>(); files.add(message); getDownloadController().deleteRecentFiles(files);
        samples.remove(message.getFileName()); if (listView != null) listView.adapter.update(true);
        rows.remove(message.getFileName());
        MessagesController.getMainSettings(currentAccount).edit().remove("ghost_download_progress_" + message.getFileName()).remove("ghost_download_paused_" + message.getFileName()).apply();
    }
    private void openMessage(MessageObject message) {
        Bundle args = new Bundle(); long dialog = message.getDialogId();
        if (dialog > 0) args.putLong("user_id", dialog); else args.putLong("chat_id", -dialog);
        args.putInt("message_id", message.getId()); ChatActivity chat = new ChatActivity(args); chat.setCurrentAccount(currentAccount); presentFragment(chat);
    }
    @Override protected void onClick(UItem item, View view, int position, float x, float y) {}
    @Override protected boolean onLongClick(UItem item, View view, int position, float x, float y) { return false; }
}
