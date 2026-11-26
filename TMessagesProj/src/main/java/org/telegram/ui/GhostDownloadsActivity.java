package org.telegram.ui;

import android.app.Activity;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;

import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.*;
import org.telegram.ui.Components.*;
import org.telegram.ui.Components.Forum.ForumUtilities;

import java.io.*;
import java.util.*;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

public final class GhostDownloadsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private static final int PICK_FOLDER = 7091;
    private final ArrayList<Entry> entries = new ArrayList<>();
    private final HashSet<String> selected = new HashSet<>();
    private final HashMap<String, long[]> samples = new HashMap<>();
    private final ArrayList<TextView> chips = new ArrayList<>();
    private ArrayList<AstraDownloadFile> saving;
    private RecyclerView list;
    private DownloadAdapter adapter;
    private ActionBarMenuItem batchMenu;
    private String query = "";
    private int filter;
    private boolean resumed;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed || list == null) return;
            for (int i = 0; i < list.getChildCount(); i++) {
                View child = list.getChildAt(i);
                if (child instanceof FileRow) ((FileRow) child).updateProgress();
            }
            AndroidUtilities.runOnUIThread(this, 750);
        }
    };

    public GhostDownloadsActivity(int account) { currentAccount = account; }

    @Override public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.onDownloadingFilesChanged);
        getNotificationCenter().addObserver(this, NotificationCenter.didUpdateConnectionState);
        getDownloadController().loadDownloadingFiles();
        return super.onFragmentCreate();
    }

    @Override public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setTitle(getString(R.string.GhostDownloads));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) {
                if (id == -1 && onBackPressed(false)) finishFragment();
                if (id == 2) actions(batchMenu, chosen(), true);
                if (id >= 30 && id <= 32) {
                    FileLoadOperation.setAstraDownloadSpeed(id - 30);
                    android.widget.Toast.makeText(context, "Скорость изменится для следующих загрузок", android.widget.Toast.LENGTH_SHORT).show();
                }
            }
        });
        ActionBarMenuItem search = actionBar.createMenu().addItem(1, R.drawable.msg_search).setIsSearchField(true)
                .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                    @Override public void onTextChanged(EditText field) {
                        query = field.getText().toString().trim().toLowerCase(Locale.ROOT);
                        rebuild();
                        if (list != null) list.scrollToPosition(0);
                    }
                });
        search.setSearchFieldHint(getString(R.string.AstraDownloadSearch));
        ActionBarMenuItem speed = actionBar.createMenu().addItem(3, R.drawable.msg_speed);
        speed.setContentDescription("Скорость скачивания");
        speed.addSubItem(30, R.drawable.msg_speed, "Обычная скорость");
        speed.addSubItem(31, R.drawable.msg_speed_medium, "Ускоренная");
        speed.addSubItem(32, R.drawable.msg_speed_fast, "Максимальная");
        batchMenu = actionBar.createMenu().addItem(2, R.drawable.ic_ab_other);
        batchMenu.setVisibility(View.GONE);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(color(Theme.key_windowBackgroundWhite));
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout bar = new LinearLayout(context);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPaddingRelative(dp(14), 0, dp(8), 0);
        int[] names = {R.string.AstraDownloadAll, R.string.AstraDownloadVideo, R.string.AstraDownloadPhoto, R.string.AstraDownloadFiles};
        chips.clear();
        for (int i = 0; i < names.length; i++) {
            int position = i;
            TextView chip = label(context, 14, Theme.key_windowBackgroundWhiteBlackText);
            chip.setGravity(Gravity.CENTER);
            chip.setText(getString(names[i]));
            chip.setPaddingRelative(dp(16), 0, dp(16), 0);
            chip.setOnClickListener(v -> {
                filter = position;
                updateChips();
                rebuild();
                list.scrollToPosition(0);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(36));
            params.setMarginEnd(dp(7));
            bar.addView(chip, params);
            chips.add(chip);
        }
        scroll.addView(bar, new HorizontalScrollView.LayoutParams(-2, -1));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, dp(58)));
        updateChips();

        list = new RecyclerView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setItemAnimator(null);
        adapter = new DownloadAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        fragmentView = root;
        rebuild();
        return root;
    }

    @Override public void onResume() { super.onResume(); resumed = true; tick.run(); }
    @Override public void onPause() { stopTick(); super.onPause(); }
    private void stopTick() { resumed = false; AndroidUtilities.cancelRunOnUIThread(tick); }

    @Override public void onFragmentDestroy() {
        stopTick();
        getNotificationCenter().removeObserver(this, NotificationCenter.onDownloadingFilesChanged);
        getNotificationCenter().removeObserver(this, NotificationCenter.didUpdateConnectionState);
        super.onFragmentDestroy();
    }

    @Override public void didReceivedNotification(int id, int account, Object... args) { rebuild(); }

    @Override public boolean onBackPressed(boolean invoked) {
        if (selected.isEmpty()) return super.onBackPressed(invoked);
        selected.clear();
        rebuild();
        return false;
    }

    private int color(int key) { return Theme.getColor(key, getResourceProvider()); }

    private GradientDrawable surface(int background, int radius, boolean outline) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(background);
        shape.setCornerRadius(dp(radius));
        if (outline) shape.setStroke(dp(1), color(Theme.key_divider));
        return shape;
    }

    private TextView label(Context context, int size, int key) {
        TextView view = new TextView(context);
        view.setTextSize(size);
        view.setTextColor(color(key));
        return view;
    }

    private void updateChips() {
        for (int i = 0; i < chips.size(); i++) {
            TextView chip = chips.get(i);
            boolean active = i == filter;
            chip.setSelected(active);
            chip.setTextColor(color(active ? Theme.key_featuredStickers_buttonText : Theme.key_windowBackgroundWhiteBlackText));
            chip.setBackground(surface(color(active ? Theme.key_featuredStickers_addButton : Theme.key_windowBackgroundGray), 20, !active));
        }
    }

    private void rebuild() {
        if (adapter == null) return;
        entries.clear();
        HashSet<String> queueKeys = new HashSet<>();
        HashSet<String> historyKeys = new HashSet<>();
        entries.add(new Entry(getString(R.string.AstraDownloadQueue), true));
        for (MessageObject message : getDownloadController().downloadingFiles) {
            AstraDownloadFile file = AstraDownloadFile.from(message);
            if (file == null) continue;
            queueKeys.add(file.key);
            if (matches(file)) entries.add(new Entry(file, false));
        }
        if (entries.size() == 1) entries.add(new Entry(getString(R.string.GhostNoDownloads), false));
        entries.add(new Entry(getString(R.string.AstraDownloaded), true));
        int historyStart = entries.size();
        Calendar day = Calendar.getInstance();
        long previousDay = Long.MIN_VALUE;
        for (MessageObject message : getDownloadController().recentDownloadingFiles) {
            AstraDownloadFile file = AstraDownloadFile.from(message);
            if (file == null) continue;
            historyKeys.add(file.key);
            if (!matches(file)) continue;
            long date = getDownloadController().downloadDate(file);
            day.setTimeInMillis(date * 1000);
            long dayKey = day.get(Calendar.YEAR) * 400L + day.get(Calendar.DAY_OF_YEAR);
            if (dayKey != previousDay) {
                entries.add(new Entry(LocaleController.formatDateChat(date), false));
                previousDay = dayKey;
            }
            entries.add(new Entry(file, true));
        }
        if (entries.size() == historyStart) entries.add(new Entry(getString(R.string.GhostNoDownloads), false));
        samples.keySet().retainAll(queueKeys);
        selected.retainAll(historyKeys);
        long total = 0;
        for (AstraDownloadFile file : chosen()) total += file.total;
        actionBar.setTitle(selected.isEmpty() ? getString(R.string.GhostDownloads)
                : selected.size() + " · " + AndroidUtilities.formatFileSize(total));
        batchMenu.setVisibility(selected.isEmpty() ? View.GONE : View.VISIBLE);
        adapter.notifyDataSetChanged();
    }

    private boolean matches(AstraDownloadFile file) {
        if (filter == 1 && !file.isVideo() || filter == 2 && !file.isPhoto()
                || filter == 3 && (file.isVideo() || file.isPhoto())) return false;
        return query.isEmpty() || file.name.toLowerCase(Locale.ROOT).contains(query)
                || chatName(file.message).toLowerCase(Locale.ROOT).contains(query);
    }

    private String chatName(MessageObject message) {
        long dialog = message.getDialogId();
        if (dialog == getUserConfig().getClientUserId()) return getString(R.string.SavedMessages);
        if (DialogObject.isEncryptedDialog(dialog)) {
            TLRPC.EncryptedChat secret = getMessagesController().getEncryptedChat(DialogObject.getEncryptedChatId(dialog));
            if (secret != null) dialog = secret.user_id;
        }
        if (dialog < 0) {
            TLRPC.Chat chat = getMessagesController().getChat(-dialog);
            if (chat != null) return chat.title;
        } else if (dialog > 0) {
            TLRPC.User user = getMessagesController().getUser(dialog);
            if (user != null) return UserObject.getUserName(user);
        }
        return getString(R.string.AstraDownloadUnknownChat);
    }

    private ArrayList<AstraDownloadFile> chosen() {
        ArrayList<AstraDownloadFile> files = new ArrayList<>();
        for (MessageObject message : getDownloadController().recentDownloadingFiles) {
            AstraDownloadFile file = AstraDownloadFile.from(message);
            if (file != null && selected.contains(file.key)) files.add(file);
        }
        return files;
    }

    private void select(AstraDownloadFile file) {
        if (!selected.add(file.key)) selected.remove(file.key);
        rebuild();
    }

    private void actions(View anchor, ArrayList<AstraDownloadFile> files, boolean completed) {
        if (files.isEmpty()) return;
        AstraDownloadFile first = files.get(0);
        ItemOptions menu = ItemOptions.makeOptions(this, anchor);
        if (completed) {
            menu.add(R.drawable.msg_share, getString(R.string.AstraDownloadShare), () -> share(files));
            menu.add(getString(R.string.AstraDownloadSaveFolder), () -> chooseFolder(files));
            menu.add(getString(R.string.AstraDownloadRemoveHistory), () -> remove(files, false));
            if (files.size() == 1) menu.add(getString(R.string.GhostGoToMessage), () -> openMessage(first.message));
            menu.add(R.drawable.msg_delete, getString(R.string.GhostDeleteFile), true, () -> confirmDelete(files));
        } else {
            menu.add(getString(R.string.AstraDownloadNext), () -> getDownloadController().resumeDownload(first, true));
            menu.add(getString(R.string.GhostGoToMessage), () -> openMessage(first.message));
            menu.add(R.drawable.msg_delete, getString(R.string.GhostCancelDownload), true, () -> remove(files, false));
        }
        menu.show();
    }

    private void confirmDelete(ArrayList<AstraDownloadFile> files) {
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(getString(R.string.GhostDeleteFile))
                .setMessage(getString(R.string.AstraDownloadDeleteConfirm))
                .setPositiveButton(getString(R.string.Delete), (dialog, which) -> remove(files, true))
                .setNegativeButton(getString(R.string.Cancel), null).create());
    }

    private void remove(ArrayList<AstraDownloadFile> files, boolean delete) {
        ArrayList<MessageObject> messages = new ArrayList<>();
        for (AstraDownloadFile file : files) {
            selected.remove(file.key);
            messages.add(file.message);
        }
        getDownloadController().removeDownloads(messages, delete);
        rebuild();
    }

    private void openFile(AstraDownloadFile file) {
        File local = file.localFile(getFileLoader());
        if (local == null || !local.isFile()) {
            showResult(R.string.AstraDownloadFileMissing);
            return;
        }
        AndroidUtilities.openForView(local, file.name, file.mime(), getParentActivity(), getResourceProvider(), false);
    }

    private boolean canExport(ArrayList<AstraDownloadFile> files) {
        for (AstraDownloadFile file : files) {
            if (file.message.messageOwner.noforwards || getMessagesController().isPeerNoForwards(file.message.getDialogId())) {
                showResult(R.string.AstraDownloadProtected);
                return false;
            }
        }
        return true;
    }

    private void share(ArrayList<AstraDownloadFile> files) {
        if (!canExport(files)) return;
        Activity activity = getParentActivity();
        if (activity == null) return;
        try {
            ArrayList<Uri> uris = new ArrayList<>();
            String mime = files.get(0).mime();
            for (AstraDownloadFile file : files) {
                File local = file.localFile(getFileLoader());
                if (local == null || !local.isFile()) throw new IOException("Missing download");
                if (!mime.equals(file.mime())) mime = "*/*";
                uris.add(FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", local));
            }
            Intent intent = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE);
            intent.setType(mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ClipData clip = ClipData.newRawUri(files.get(0).name, uris.get(0));
            for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
            intent.setClipData(clip);
            if (uris.size() == 1) intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            activity.startActivity(Intent.createChooser(intent, null));
        } catch (Exception error) {
            FileLog.e(error);
            showResult(R.string.AstraDownloadFileError);
        }
    }

    private void chooseFolder(ArrayList<AstraDownloadFile> files) {
        if (!canExport(files)) return;
        saving = new ArrayList<>(files);
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION), PICK_FOLDER);
        } catch (Exception error) {
            saving = null;
            showResult(R.string.AstraDownloadFileError);
        }
    }

    @Override public void onActivityResultFragment(int request, int result, Intent data) {
        if (request != PICK_FOLDER) return;
        ArrayList<AstraDownloadFile> files = saving;
        saving = null;
        if (result != Activity.RESULT_OK || data == null || data.getData() == null || files == null || getParentActivity() == null) return;
        ContentResolver resolver = getParentActivity().getContentResolver();
        Uri tree = data.getData();
        FileLoader loader = getFileLoader();
        Utilities.globalQueue.postRunnable(() -> {
            int count = 0;
            try {
                Uri folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
                byte[] buffer = new byte[64 * 1024];
                for (AstraDownloadFile file : files) {
                    Uri target = null;
                    try {
                        File local = file.localFile(loader);
                        if (local == null || !local.isFile()) throw new IOException("Missing download");
                        String name = file.name.replace('/', '_').replace('\\', '_');
                        target = DocumentsContract.createDocument(resolver, folder, file.mime(), name);
                        if (target == null) throw new IOException("Cannot create document");
                        try (FileInputStream input = new FileInputStream(local); OutputStream output = resolver.openOutputStream(target, "w")) {
                            if (output == null) throw new IOException("Cannot write document");
                            int read;
                            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                        }
                        count++;
                    } catch (Exception error) {
                        if (target != null) {
                            try { DocumentsContract.deleteDocument(resolver, target); } catch (Exception ignored) { }
                        }
                        FileLog.e(error);
                    }
                }
            } catch (Exception error) {
                FileLog.e(error);
            }
            int saved = count;
            AndroidUtilities.runOnUIThread(() -> {
                if (saved == files.size()) showResult(R.string.AstraDownloadSaved);
                else if (saved == 0) showResult(R.string.AstraDownloadFileError);
                else if (getParentActivity() != null) showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                        .setMessage(LocaleController.formatString(R.string.AstraDownloadPartiallySaved, saved, files.size()))
                        .setPositiveButton(getString(R.string.OK), null).create());
            });
        });
    }

    private void showResult(int message) {
        if (getParentActivity() == null) return;
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setMessage(getString(message)).setPositiveButton(getString(R.string.OK), null).create());
    }

    private void openMessage(MessageObject message) {
        Bundle args = new Bundle();
        long dialog = message.getDialogId();
        if (DialogObject.isEncryptedDialog(dialog)) args.putInt("enc_id", DialogObject.getEncryptedChatId(dialog));
        else if (dialog > 0) args.putLong("user_id", dialog);
        else if (dialog < 0) args.putLong("chat_id", -dialog);
        else return;
        args.putInt("message_id", message.getId());
        if (!getMessagesController().checkCanOpenChat(args, this)) return;
        if (dialog < 0 && !DialogObject.isEncryptedDialog(dialog) && message.getTopicId() > 0) {
            TLRPC.TL_forumTopic topic = getMessagesController().getTopicsController().findTopic(-dialog, message.getTopicId());
            if (topic != null) {
                ForumUtilities.openTopic(this, -dialog, topic, message.getId());
                return;
            }
        }
        ChatActivity chat = new ChatActivity(args);
        chat.setCurrentAccount(currentAccount);
        presentFragment(chat);
    }

    private static final class Entry {
        final AstraDownloadFile file;
        final boolean completed;
        final String title;
        final boolean section;

        Entry(AstraDownloadFile file, boolean completed) {
            this.file = file;
            this.completed = completed;
            title = null;
            section = false;
        }

        Entry(String title, boolean section) {
            file = null;
            completed = false;
            this.title = title;
            this.section = section;
        }
    }

    private final class DownloadAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemCount() { return entries.size(); }

        @Override public int getItemViewType(int position) {
            Entry entry = entries.get(position);
            return entry.file != null ? 0 : entry.section ? 1 : 2;
        }

        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            View view;
            if (type == 0) view = new FileRow(parent.getContext());
            else {
                TextView header = label(parent.getContext(), type == 1 ? 17 : 13,
                        type == 1 ? Theme.key_windowBackgroundWhiteBlueText : Theme.key_windowBackgroundWhiteGrayText);
                header.setPaddingRelative(dp(16), dp(type == 1 ? 16 : 8), dp(16), dp(8));
                if (type == 1) header.setTypeface(AndroidUtilities.bold());
                view = header;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            return new RecyclerView.ViewHolder(view) { };
        }

        @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Entry entry = entries.get(position);
            if (holder.itemView instanceof FileRow) ((FileRow) holder.itemView).bind(entry);
            else ((TextView) holder.itemView).setText(entry.title);
        }
    }

    private final class FileRow extends FrameLayout {
        final LinearLayout body;
        final BackupImageView preview;
        final ImageView fallback, more;
        final TextView duration, title, detail;
        final ImageButton control;
        int controlIcon;
        final ProgressBar progress;
        Entry entry;

        FileRow(Context context) {
            super(context);
            body = new LinearLayout(context);
            body.setGravity(Gravity.CENTER_VERTICAL);
            body.setPaddingRelative(dp(9), dp(8), dp(2), dp(8));
            addView(body, LayoutHelper.createFrame(-1, -2, Gravity.TOP, 10, 3, 10, 3));
            FrameLayout cover = new FrameLayout(context);
            cover.setBackground(surface(color(Theme.key_windowBackgroundGray), 8, false));
            preview = new BackupImageView(context);
            preview.setRoundRadius(dp(8));
            preview.getImageReceiver().setCurrentAccount(currentAccount);
            cover.addView(preview, LayoutHelper.createFrame(-1, -1));
            fallback = new ImageView(context);
            fallback.setImageResource(R.drawable.msg_log);
            fallback.setColorFilter(color(Theme.key_windowBackgroundWhiteBlueText));
            cover.addView(fallback, LayoutHelper.createFrame(24, 24, Gravity.CENTER));
            duration = label(context, 11, Theme.key_windowBackgroundWhiteBlackText);
            duration.setTextColor(0xFFFFFFFF);
            duration.setGravity(Gravity.CENTER);
            duration.setPadding(dp(4), 0, dp(4), 0);
            duration.setBackground(surface(0xB9000000, 5, false));
            cover.addView(duration, LayoutHelper.createFrame(-2, 16, Gravity.BOTTOM | Gravity.LEFT, 4, 0, 0, 4));
            body.addView(cover, new LinearLayout.LayoutParams(dp(62), dp(52)));
            LinearLayout text = new LinearLayout(context);
            text.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, -2, 1);
            textParams.setMarginStart(dp(11));
            body.addView(text, textParams);
            title = label(context, 15, Theme.key_windowBackgroundWhiteBlackText);
            title.setSingleLine();
            title.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(title, new LinearLayout.LayoutParams(-1, -2));
            detail = label(context, 12, Theme.key_windowBackgroundWhiteGrayText);
            detail.setMaxLines(2);
            detail.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(detail, new LinearLayout.LayoutParams(-1, -2));
            progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
            progress.setProgressTintList(ColorStateList.valueOf(color(Theme.key_windowBackgroundWhiteBlueText)));
            progress.setProgressBackgroundTintList(ColorStateList.valueOf(color(Theme.key_divider)));
            LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(4));
            progressParams.topMargin = dp(6);
            text.addView(progress, progressParams);
            control = new ImageButton(context);
            control.setScaleType(ImageView.ScaleType.CENTER);
            control.setColorFilter(color(Theme.key_featuredStickers_buttonText));
            control.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(22),
                    color(Theme.key_featuredStickers_addButton), color(Theme.key_featuredStickers_addButtonPressed)));
            LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(dp(42), dp(42));
            buttonParams.setMarginStart(dp(7));
            body.addView(control, buttonParams);
            more = new ImageView(context);
            more.setImageResource(R.drawable.ic_ab_other);
            more.setColorFilter(color(Theme.key_windowBackgroundWhiteGrayText));
            more.setScaleType(ImageView.ScaleType.CENTER);
            more.setContentDescription(getString(R.string.AstraDownloadActions));
            body.addView(more, new LinearLayout.LayoutParams(dp(32), dp(44)));
            View line = new View(context);
            line.setBackgroundColor(color(Theme.key_divider));
            addView(line, LayoutHelper.createFrame(-1, 1, Gravity.BOTTOM, 18, 0, 18, 0));

            body.setOnClickListener(view -> {
                if (entry.completed) {
                    if (selected.isEmpty()) openFile(entry.file);
                    else select(entry.file);
                }
            });
            body.setOnLongClickListener(view -> {
                if (!entry.completed) return false;
                select(entry.file);
                return true;
            });
            more.setOnClickListener(view -> {
                ArrayList<AstraDownloadFile> files = new ArrayList<>();
                files.add(entry.file);
                actions(view, files, entry.completed);
            });
            control.setOnClickListener(view -> {
                AstraDownloadFile file = entry.file;
                DownloadController controller = getDownloadController();
                if (getFileLoader().isLoadingFile(file.key) && controller.downloadState(file.key) != DownloadController.DOWNLOAD_PAUSED) {
                    controller.pauseDownload(file);
                } else controller.resumeDownload(file, false);
                updateProgress();
            });
        }

        void bind(Entry entry) {
            this.entry = entry;
            AstraDownloadFile file = entry.file;
            boolean checked = selected.contains(file.key);
            title.setText(file.name);
            body.setSelected(checked);
            body.setBackground(surface(color(checked ? Theme.key_listSelector
                    : entry.completed ? Theme.key_windowBackgroundWhite : Theme.key_windowBackgroundGray), 12, false));
            control.setVisibility(entry.completed ? View.GONE : View.VISIBLE);
            progress.setVisibility(entry.completed ? View.GONE : View.VISIBLE);
            ImageLocation image = null;
            if (file.photo != null) {
                TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(file.photo.sizes, 100);
                if (thumb != file.size) image = ImageLocation.getForPhoto(thumb, file.photo);
                else if (entry.completed) image = ImageLocation.getForPhoto(file.size, file.photo);
            } else if (file.isVideo() || file.isPhoto()) {
                image = ImageLocation.getForDocument(FileLoader.getClosestPhotoSizeWithSize(file.document.thumbs, 100), file.document);
            }
            fallback.setVisibility(image == null ? View.VISIBLE : View.GONE);
            preview.setImage(image, "62_52", (android.graphics.drawable.Drawable) null, file.message);
            int seconds = file.isVideo() ? (int) file.message.getDuration() : 0;
            duration.setVisibility(seconds > 0 ? View.VISIBLE : View.GONE);
            duration.setText(AndroidUtilities.formatShortDuration(seconds));
            if (entry.completed) detail.setText(AndroidUtilities.formatFileSize(file.total) + " · " + chatName(file.message));
            else updateProgress();
        }

        void updateProgress() {
            if (entry == null || entry.completed) return;
            AstraDownloadFile file = entry.file;
            DownloadController controller = getDownloadController();
            int state = controller.downloadState(file.key);
            boolean active = getFileLoader().isLoadingFile(file.key);
            boolean paused = state == DownloadController.DOWNLOAD_PAUSED;
            boolean failed = !active && state == DownloadController.DOWNLOAD_FAILED;
            boolean waiting = active && !ApplicationLoader.isNetworkOnline() || state == DownloadController.DOWNLOAD_WAITING;
            long loaded = Math.max(0, controller.downloadProgress(file));
            long[] sizes = ImageLoader.getInstance().getFileProgressSizes(file.key);
            long total = sizes != null && sizes[1] > 0 ? sizes[1] : file.total;
            long now = SystemClock.elapsedRealtime();
            long[] sample = samples.computeIfAbsent(file.key, ignored -> new long[]{loaded, now, 0});
            if (!active || waiting || paused) sample[2] = 0;
            if (now - sample[1] >= 500) {
                sample[2] = active && !waiting && !paused ? Math.max(0, loaded - sample[0]) * 1000 / (now - sample[1]) : 0;
                sample[0] = loaded;
                sample[1] = now;
            }
            int action = active && !paused ? R.string.GhostPauseDownload : failed ? R.string.AstraDownloadRetry : R.string.GhostResumeDownload;
            int icon = active && !paused ? R.drawable.astra_pause : failed ? R.drawable.astra_retry : R.drawable.astra_play;
            if (controlIcon != icon) {
                controlIcon = icon;
                control.setImageResource(icon);
            }
            control.setContentDescription(getString(action));
            int status = paused ? R.string.GhostPaused : failed ? R.string.AstraDownloadFailed
                    : waiting ? R.string.AstraDownloadWaiting : active ? R.string.GhostDownloading : R.string.GhostPaused;
            String volume = AndroidUtilities.formatFileSize(loaded) + " / " + (total > 0 ? AndroidUtilities.formatFileSize(total) : "—");
            String speed = active && !waiting && !paused ? " · " + AndroidUtilities.formatFileSize(sample[2]) + "/" + getString(R.string.GhostSecondShort) : "";
            detail.setText(getString(status) + " · " + volume + speed);
            progress.setProgress(total > 0 ? (int) Math.min(100, loaded * 100.0 / total) : 0);
        }
    }
}
