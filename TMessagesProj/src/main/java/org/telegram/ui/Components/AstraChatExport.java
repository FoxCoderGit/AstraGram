package org.telegram.ui.Components;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Base64OutputStream;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.GhostBackgroundTasks;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.telegram.messenger.AndroidUtilities.dp;

public final class AstraChatExport {
    public static final int PICK_FOLDER = 9488;
    private static final String INDEX = "index.html";
    private static final String STYLE = "astra_chat_export.css";

    private AstraChatExport() {}

    public static void choose(ChatActivity chat, org.telegram.messenger.Utilities.Callback<Options> ready) {
        Activity activity = chat.getParentActivity();
        if (activity == null) return;
        Calendar end = Calendar.getInstance();
        Calendar start = (Calendar) end.clone();
        start.add(Calendar.DAY_OF_MONTH, -30);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        final Theme.ResourcesProvider theme = chat.getResourceProvider();
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setBackground(Theme.createRoundRectDrawable(dp(16), Theme.getColor(Theme.key_windowBackgroundWhite, theme)));
        form.setClipToOutline(true);
        content.addView(form, LayoutHelper.createLinear(-1, -2, 16, 4, 16, 8));
        addDate(activity, chat, form, "Начало периода", start);
        addDate(activity, chat, form, "Конец периода", end);

        TextCheckCell media = new TextCheckCell(activity, theme);
        media.setTextAndValueAndCheck("С файлами", "Сохранить оригиналы вложений в папку рядом с index.html", false, true, false);
        media.setBackground(Theme.getSelectorDrawable(false));
        media.setOnClickListener(view -> media.setChecked(!media.isChecked()));
        form.addView(media, LayoutHelper.createLinear(-1, -2));

        ButtonWithCounterView selectFolder = new ButtonWithCounterView(activity, true, theme).setRound();
        selectFolder.setText("Выбрать папку");
        content.addView(selectFolder, LayoutHelper.createLinear(-1, 48, 16, 8, 16, 14));
        BottomSheet sheet = new BottomSheet.Builder(activity, false, theme).setTitle("Экспорт чата", true)
                .setCustomView(content).create();
        selectFolder.setOnClickListener(view -> {
            long from = GhostChatScreenshot.startOfDay(start);
            long to = GhostChatScreenshot.endOfDay(end);
            if (from >= to) {
                Toast.makeText(activity, "Дата окончания раньше даты начала", Toast.LENGTH_SHORT).show();
                return;
            }
            ready.run(new Options(chat, (int) (from / 1000), (int) (to / 1000), media.isChecked()));
            sheet.dismiss();
            chat.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION), PICK_FOLDER);
        });
        chat.showDialog(sheet);
    }

    private static void addDate(Activity activity, ChatActivity chat, LinearLayout parent, String label, Calendar calendar) {
        TextCell view = new TextCell(activity, chat.getResourceProvider());
        Runnable update = () -> view.setTextAndValueAndIcon(label, DateFormat.getDateInstance().format(calendar.getTime()),
                R.drawable.msg_calendar2, true);
        update.run();
        view.setBackground(Theme.getSelectorDrawable(false));
        view.setOnClickListener(v -> {
            int year = Calendar.getInstance().get(Calendar.YEAR);
            AlertsCreator.createDatePickerDialog(activity, 2013 - year, 2037 - year, 0,
                    calendar.get(Calendar.DAY_OF_MONTH), calendar.get(Calendar.MONTH), calendar.get(Calendar.YEAR),
                    label, false, (selectedYear, month, day) -> {
                        calendar.set(selectedYear, month, day);
                        update.run();
                    }).create().show();
        });
        parent.addView(view, LayoutHelper.createLinear(-1, -2));
    }

    public static final class Options {
        private final int account, from, to, topic;
        private final long dialog, owner;
        private final boolean withMedia, dark;
        private final int backgroundColor;
        private final String title;
        private Bitmap wallpaper;

        Options(ChatActivity chat, int from, int to, boolean withMedia) {
            account = chat.getCurrentAccount();
            dialog = chat.getDialogId();
            topic = (int) chat.getTopicId();
            owner = UserConfig.getInstance(account).getClientUserId();
            this.from = from;
            this.to = to;
            this.withMedia = withMedia;
            Theme.ResourcesProvider theme = chat.getResourceProvider();
            dark = theme == null ? Theme.isCurrentThemeDark() : theme.isDark();
            backgroundColor = Theme.getColor(Theme.key_chat_wallpaper, theme);
            TLRPC.Chat peer = chat.getCurrentChat();
            TLRPC.User user = chat.getCurrentUser();
            title = peer != null ? peer.title : user != null ? UserObject.getUserName(user) : "Экспорт чата";
            wallpaper = captureWallpaper(chat, backgroundColor);
        }

        public void start(Uri tree) {
            if (tree != null) new Export(this, tree).start();
        }

        public void discard() {
            if (wallpaper != null) {
                wallpaper.recycle();
                wallpaper = null;
            }
        }
    }

    private static Bitmap captureWallpaper(ChatActivity chat, int backgroundColor) {
        Drawable drawable = chat.getGameWallpaper();
        View view = chat.getFragmentView();
        if (drawable == null || view == null || view.getWidth() == 0 || view.getHeight() == 0) return null;
        int width = view.getWidth(), height = view.getHeight();
        float scale = Math.min(1f, 1280f / Math.max(width, height));
        Rect original = new Rect(drawable.getBounds());
        Bitmap image = null;
        try {
            image = Bitmap.createBitmap(Math.max(1, Math.round(width * scale)),
                    Math.max(1, Math.round(height * scale)), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(image);
            canvas.drawColor(backgroundColor);
            canvas.scale(scale, scale);
            drawable.setBounds(0, 0, width, height);
            drawable.draw(canvas);
            return image;
        } catch (RuntimeException | OutOfMemoryError error) {
            if (image != null) image.recycle();
            FileLog.e(error);
            return null;
        } finally {
            drawable.setBounds(original);
        }
    }

    private static final class Export {
        private final Options options;
        private final Uri tree;
        private final ConnectionsManager network;
        private final MessagesController controller;
        private final FileLoader files;
        private final ContentResolver resolver = ApplicationLoader.applicationContext.getContentResolver();
        private final GhostBackgroundTasks.Task task;
        private final DispatchQueue queue = new DispatchQueue("AstraChatExport");
        private final ArrayList<Page> pages = new ArrayList<>();
        private final DateFormat dayFormat = DateFormat.getDateInstance(DateFormat.LONG);
        private final DateFormat timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT);
        private Uri exportFolder, mediaFolder;
        private volatile boolean cancelled;
        private volatile int request;
        private volatile CountDownLatch waiting;
        private int count, missingMedia;

        private static final class Page {
            final File html;
            String firstDay, lastDay;

            Page(File html) {
                this.html = html;
            }
        }

        Export(Options options, Uri tree) {
            this.options = options;
            this.tree = tree;
            network = ConnectionsManager.getInstance(options.account);
            controller = MessagesController.getInstance(options.account);
            files = FileLoader.getInstance(options.account);
            task = GhostBackgroundTasks.create("Экспорт чата", this::cancel);
        }

        void start() {
            queue.postRunnable(this::export);
        }

        private void cancel() {
            cancelled = true;
            if (request != 0) network.cancelRequest(request, true);
            CountDownLatch latch = waiting;
            if (latch != null) latch.countDown();
        }

        private void checkActive() throws IOException {
            if (cancelled || UserConfig.getInstance(options.account).getClientUserId() != options.owner
                    || controller.isPeerNoForwards(options.dialog)) throw new IOException("Export stopped");
        }

        private void export() {
            Uri output = null;
            boolean complete = false;
            try {
                checkActive();
                Uri directory = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
                if (options.withMedia) {
                    exportFolder = create(directory, DocumentsContract.Document.MIME_TYPE_DIR,
                            "AstraGram_chat_" + System.currentTimeMillis());
                    directory = exportFolder;
                }
                output = create(directory, "text/html", INDEX);
                if (!INDEX.equals(documentName(output, INDEX))) throw new ExistingIndexException();
                writeRecords();
                try (Writer writer = utf8(openOutput(output))) {
                    writeIndex(writer);
                }
                checkActive();
                complete = true;
                Uri result = output;
                String summary = "Экспортировано сообщений: " + count;
                if (missingMedia > 0) summary += " · Недоступно вложений: " + missingMedia;
                task.finish(summary, activity -> {
                    try {
                        activity.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(result, "text/html")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                    } catch (Exception error) {
                        Toast.makeText(activity, INDEX, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (ExistingIndexException error) {
                task.finish("В выбранной папке уже есть index.html. Выберите другую папку.", null);
            } catch (Exception error) {
                if (!cancelled) FileLog.e(error);
                task.finish(cancelled ? "Экспорт отменён" : "Ошибка экспорта чата", null);
            } finally {
                for (Page page : pages) page.html.delete();
                options.discard();
                if (!complete) delete(exportFolder != null ? exportFolder : output);
                queue.recycle();
            }
        }

        private void writeRecords() throws Exception {
            int offset = 0;
            boolean reachedStart = false;
            while (!reachedStart) {
                checkActive();
                TLRPC.messages_Messages result = page(offset);
                if (result.messages.isEmpty()) break;
                int next = offset == 0 ? Integer.MAX_VALUE : offset;
                ArrayList<TLRPC.Message> selected = new ArrayList<>();
                for (TLRPC.Message message : result.messages) {
                    if (message.id <= 0 || offset != 0 && message.id >= offset) continue;
                    next = Math.min(next, message.id);
                    if (message.date < options.from && message.date != 0) reachedStart = true;
                    if (message.date < options.from || message.date >= options.to || message.noforwards
                            || message instanceof TLRPC.TL_messageEmpty || MessageObject.isSecretMedia(message)) continue;
                    selected.add(message);
                }
                if (!selected.isEmpty()) writePage(selected);
                if (next == offset || next == Integer.MAX_VALUE) break;
                offset = next;
                task.progress("Обработано сообщений: " + count, -1);
            }
        }

        private void writePage(ArrayList<TLRPC.Message> selected) throws Exception {
            selected.sort(Comparator.comparingInt((TLRPC.Message message) -> message.date).thenComparingInt(message -> message.id));
            Page fragment = new Page(File.createTempFile("astra-chat-", ".part", ApplicationLoader.applicationContext.getCacheDir()));
            pages.add(fragment);
            try (Writer writer = utf8(new FileOutputStream(fragment.html))) {
                long previousSender = Long.MIN_VALUE;
                for (TLRPC.Message message : selected) {
                    checkActive();
                    MessageObject item = new MessageObject(options.account, message, false, true);
                    if (item.isRoundOnce() || item.isVoiceOnce()) continue;
                    Date date = new Date(message.date * 1000L);
                    String day = dayFormat.format(date);
                    if (fragment.firstDay == null) fragment.firstDay = day;
                    if (fragment.lastDay != null && !day.equals(fragment.lastDay)) writeDay(writer, day);
                    long sender = MessageObject.getFromChatId(message);
                    boolean grouped = message.action == null && sender != 0 && sender == previousSender && day.equals(fragment.lastDay);
                    writeItem(writer, item, sender, date, grouped);
                    fragment.lastDay = day;
                    previousSender = message.action == null ? sender : Long.MIN_VALUE;
                    count++;
                }
            }
        }

        private void writeIndex(Writer writer) throws IOException {
            writer.write("<!doctype html><html lang=\"ru\"" + (options.dark ? " class=\"theme-dark\"" : "") + "><head>"
                    + "<meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                    + "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; "
                    + "img-src data: 'self' file: content:; media-src 'self' file: content:\"><title>"
                    + escaped(options.title) + "</title><style>");
            try (Reader css = new InputStreamReader(ApplicationLoader.applicationContext.getAssets().open(STYLE), StandardCharsets.UTF_8)) {
                copyText(css, writer);
            }
            writer.write("body{--wall:#" + String.format(java.util.Locale.ROOT, "%06x", options.backgroundColor & 0xffffff));
            if (options.wallpaper != null) {
                writer.write(";--wallpaper:url('data:image/png;base64,");
                try (Base64OutputStream base64 = base64(writer)) {
                    if (!options.wallpaper.compress(Bitmap.CompressFormat.PNG, 100, base64)) throw new IOException("Wallpaper encoding failed");
                }
                writer.write("')");
            }
            writer.write("}</style></head><body><main class=\"message-list\">");
            String lastDay = null;
            for (int i = pages.size() - 1; i >= 0; i--) {
                Page page = pages.get(i);
                if (page.firstDay == null) continue;
                if (!page.firstDay.equals(lastDay)) writeDay(writer, page.firstDay);
                try (Reader content = new InputStreamReader(new FileInputStream(page.html), StandardCharsets.UTF_8)) {
                    copyText(content, writer);
                }
                lastDay = page.lastDay;
            }
            writer.write("</main></body></html>");
        }

        private void writeDay(Writer writer, String day) throws IOException {
            writer.write("<div class=\"date-divider\">" + escaped(day) + "</div>\n");
        }

        private static final class ExistingIndexException extends IOException {}

        private TLRPC.messages_Messages page(int offset) throws Exception {
            TLObject req;
            if (options.topic != 0) {
                TLRPC.TL_messages_search search = new TLRPC.TL_messages_search();
                search.peer = controller.getInputPeer(options.dialog);
                search.q = "";
                search.filter = new TLRPC.TL_inputMessagesFilterEmpty();
                search.limit = 100;
                search.offset_id = offset;
                search.min_date = options.from;
                search.max_date = options.to - 1;
                search.top_msg_id = options.topic;
                search.flags |= 2;
                req = search;
            } else {
                TLRPC.TL_messages_getHistory history = new TLRPC.TL_messages_getHistory();
                history.peer = controller.getInputPeer(options.dialog);
                history.limit = 100;
                history.offset_id = offset;
                history.offset_date = offset == 0 ? options.to : 0;
                req = history;
            }
            TLRPC.messages_Messages[] result = new TLRPC.messages_Messages[1];
            CountDownLatch latch = new CountDownLatch(1);
            waiting = latch;
            request = network.sendRequest(req, (value, error) -> {
                if (error == null && value instanceof TLRPC.messages_Messages) result[0] = (TLRPC.messages_Messages) value;
                latch.countDown();
            });
            try {
                if (!latch.await(60, TimeUnit.SECONDS) || result[0] == null) throw new IOException("History request failed");
                checkActive();
                controller.putUsers(result[0].users, false);
                controller.putChats(result[0].chats, false);
                return result[0];
            } finally {
                if (result[0] == null) network.cancelRequest(request, true);
                request = 0;
                waiting = null;
            }
        }

        private void writeMedia(Writer writer, MessageObject message) throws Exception {
            TLRPC.Document document = message.getDocument();
            TLRPC.Photo photo = message.messageOwner.media == null ? null : message.messageOwner.media.photo;
            boolean originalDocument = document != null && (options.withMedia || isImage(document.mime_type));
            TLRPC.PhotoSize size = null;
            ImageLocation location = null;
            if (photo != null) {
                size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, options.withMedia ? Integer.MAX_VALUE : 960);
                if (size != null) location = ImageLocation.getForPhoto(size, photo);
            } else if (document != null && !originalDocument) {
                size = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 480);
                if (size != null) location = ImageLocation.getForDocument(size, document);
            }
            TLObject media = originalDocument ? document : size;
            if (media == null || (!originalDocument && location == null)) return;
            String key = FileLoader.getAttachFileName(media);
            if (TextUtils.isEmpty(key)) return;
            File file = download(message, media, location, key);
            if (file == null) {
                missingMedia++;
                writer.write("<div class=\"missing-media\">Вложение недоступно</div>");
                return;
            }

            String mime = originalDocument && !TextUtils.isEmpty(document.mime_type) ? document.mime_type : "image/jpeg";
            if (!options.withMedia) {
                writer.write("<div class=\"visual-media\"><img loading=\"lazy\" alt=\"\" src=\"data:" + mime + ";base64,");
                try (Base64OutputStream base64 = base64(writer)) {
                    transfer(file, base64);
                }
                writer.write("\"></div>");
                return;
            }

            String filename = originalDocument ? FileLoader.getDocumentFileName(document) : key;
            if (TextUtils.isEmpty(filename)) filename = key;
            if (mediaFolder == null) mediaFolder = create(exportFolder, DocumentsContract.Document.MIME_TYPE_DIR, "media");
            String safeName = message.getId() + "_" + filename.replaceAll("[\\p{Cntrl}/\\\\]", "_");
            Uri destination = create(mediaFolder, safeMime(mime), safeName);
            try (OutputStream stream = openOutput(destination)) {
                transfer(file, stream);
            }
            String uri = Uri.encode(documentName(mediaFolder, "media")) + "/" + Uri.encode(documentName(destination, safeName));
            if (isImage(mime)) {
                writer.write("<a class=\"visual-media\" href=\"" + uri + "\"><img loading=\"lazy\" alt=\"\" src=\"" + uri + "\"></a>");
            } else if (mime.startsWith("video/")) {
                writer.write("<div class=\"visual-media\"><video " + (message.isRoundVideo() ? "class=\"round-video\" " : "")
                        + "controls playsinline preload=\"none\" src=\"" + uri + "\"></video></div>");
            } else if (mime.startsWith("audio/")) {
                writer.write("<audio controls preload=\"none\" src=\"" + uri + "\"></audio>");
            } else {
                writer.write("<a class=\"file-card\" download href=\"" + uri + "\">" + escaped(filename) + "</a>");
            }
        }

        private File download(MessageObject message, TLObject media, ImageLocation location, String key) throws Exception {
            File present = localFile(message, media, key);
            if (present != null) return present;
            task.progress("Загрузка вложения к сообщению " + message.getId(), -1);
            NotificationCenter center = NotificationCenter.getInstance(options.account);
            CountDownLatch latch = new CountDownLatch(1);
            waiting = latch;
            NotificationCenter.NotificationCenterDelegate observer = (id, account, args) -> {
                if (args.length > 0 && key.equals(args[0])) latch.countDown();
            };
            AndroidUtilities.runOnUIThread(() -> {
                if (cancelled || localFile(message, media, key) != null) {
                    latch.countDown();
                    return;
                }
                center.addObserver(observer, NotificationCenter.fileLoaded);
                center.addObserver(observer, NotificationCenter.fileLoadFailed);
                if (media instanceof TLRPC.Document) files.loadFile((TLRPC.Document) media, message, 1, 0);
                else files.loadFile(location, message, null, 1, 0);
            });
            try {
                latch.await(180, TimeUnit.SECONDS);
                checkActive();
                return localFile(message, media, key);
            } finally {
                waiting = null;
                AndroidUtilities.runOnUIThread(() -> {
                    center.removeObserver(observer, NotificationCenter.fileLoaded);
                    center.removeObserver(observer, NotificationCenter.fileLoadFailed);
                });
            }
        }

        private File localFile(MessageObject message, TLObject media, String key) {
            long expected = media instanceof TLRPC.Document ? ((TLRPC.Document) media).size
                    : media instanceof TLRPC.PhotoSize ? ((TLRPC.PhotoSize) media).size : 0L;
            File file = files.getPathToAttach(media, true);
            if (file.isFile() && file.length() >= Math.max(1, expected)) return file;
            file = files.getPathToAttach(media);
            if (file.isFile() && file.length() >= Math.max(1, expected)) return file;
            file = files.getPathToMessage(message.messageOwner);
            return (media instanceof TLRPC.Document || key.equals(file.getName()))
                    && file.isFile() && file.length() >= Math.max(1, expected) ? file : null;
        }

        private void writeItem(Writer writer, MessageObject item, long sender, Date date, boolean grouped) throws Exception {
            TLRPC.Message message = item.messageOwner;
            if (message.action != null) {
                writer.write("<div class=\"service-message\">" + escaped(item.messageText) + "</div>\n");
                return;
            }
            TLRPC.User user = sender > 0 ? controller.getUser(sender) : null;
            TLRPC.Chat chat = sender < 0 ? controller.getChat(-sender) : null;
            String author = user != null ? UserObject.getUserName(user) : chat != null ? chat.title : "";
            writer.write("<article class=\"message" + (message.out ? " outgoing" : "")
                    + (grouped ? " grouped" : "") + "\"><div class=\"bubble\">");
            if (!grouped && !TextUtils.isEmpty(author)) {
                writer.write("<div class=\"sender-name\">" + escaped(author) + "</div>");
            }
            if (item.hasMediaSpoilers()) writer.write("<details><summary>Скрытое медиа</summary>");
            writeMedia(writer, item);
            if (item.hasMediaSpoilers()) writer.write("</details>");
            CharSequence text = message.message;
            if (TextUtils.isEmpty(text) && message.media != null && message.media.photo == null && item.getDocument() == null) {
                text = item.messageText;
            }
            if (!TextUtils.isEmpty(text)) writer.write("<div class=\"message-text\">" + escaped(text) + "</div>");
            writer.write("<time class=\"meta\">" + escaped(timeFormat.format(date)) + "</time></div></article>\n");
        }

        private Uri create(Uri directory, String mime, String name) throws IOException {
            Uri document = DocumentsContract.createDocument(resolver, directory, mime, name);
            if (document == null) throw new IOException("Cannot create " + name);
            return document;
        }

        private String documentName(Uri uri, String fallback) {
            try (Cursor cursor = resolver.query(uri, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && !TextUtils.isEmpty(cursor.getString(0))) return cursor.getString(0);
            }
            return fallback;
        }

        private OutputStream openOutput(Uri uri) throws IOException {
            OutputStream stream = resolver.openOutputStream(uri, "w");
            if (stream == null) throw new IOException("Cannot write export document");
            return stream;
        }

        private void delete(Uri document) {
            if (document == null) return;
            try {
                DocumentsContract.deleteDocument(resolver, document);
            } catch (Exception error) {
                FileLog.e(error);
            }
        }

        private void copyText(Reader reader, Writer writer) throws IOException {
            char[] buffer = new char[16 * 1024];
            int amount;
            while ((amount = reader.read(buffer)) != -1) {
                checkActive();
                writer.write(buffer, 0, amount);
            }
        }

        private void transfer(File file, OutputStream output) throws IOException {
            try (InputStream input = new FileInputStream(file)) {
                byte[] bytes = new byte[64 * 1024];
                int amount;
                while ((amount = input.read(bytes)) >= 0) {
                    checkActive();
                    output.write(bytes, 0, amount);
                }
            }
        }
    }

    private static Writer utf8(OutputStream output) {
        return new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
    }

    private static Base64OutputStream base64(Writer writer) {
        return new Base64OutputStream(new OutputStream() {
            @Override public void write(int value) throws IOException {
                writer.write(value & 0xff);
            }

            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                writer.write(new String(bytes, offset, length, StandardCharsets.US_ASCII));
            }
        }, Base64.NO_WRAP | Base64.NO_CLOSE);
    }

    private static String escaped(CharSequence value) {
        return value == null ? "" : TextUtils.htmlEncode(value.toString());
    }

    private static boolean isImage(String mime) {
        return "image/jpeg".equals(mime) || "image/png".equals(mime) || "image/webp".equals(mime)
                || "image/gif".equals(mime) || "image/avif".equals(mime);
    }

    private static String safeMime(String mime) {
        return isImage(mime) || mime.startsWith("video/") || mime.startsWith("audio/")
                ? mime : "application/octet-stream";
    }
}
