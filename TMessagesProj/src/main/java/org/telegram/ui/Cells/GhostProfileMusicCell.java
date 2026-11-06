package org.telegram.ui.Cells;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.*;
import java.io.File;
import static org.telegram.messenger.AndroidUtilities.dp;

/** Profile music card, backed by the ordinary Telegram saved-music player. */
public class GhostProfileMusicCell extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {
    private static final DispatchQueue metadataQueue = new DispatchQueue("profileMusicMetadata");
    private final BackupImageView cover;
    private final TextView title, artist, album;
    private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final Path clip = new Path();
    private final Drawable plane;
    private final Theme.ResourcesProvider resourcesProvider;
    private AnimatedEmojiDrawable pattern;
    private TLRPC.Document document;
    private int account, generation;
    private boolean observing;
    private int color = 0xff591478;

    public GhostProfileMusicCell(Context context, Theme.ResourcesProvider provider) {
        super(context);
        resourcesProvider = provider;
        setWillNotDraw(false);
        cover = new BackupImageView(context);
        cover.setRoundRadius(dp(10));
        cover.getImageReceiver().setDelegate((receiver, set, thumb, cached) -> {
            Bitmap bitmap = receiver.getBitmap();
            if (set && bitmap != null && !bitmap.isRecycled()
                    && (android.os.Build.VERSION.SDK_INT < 26 || bitmap.getConfig() != Bitmap.Config.HARDWARE)) {
                long r = 0, g = 0, b = 0; int count = 0;
                for (int y = 1; y < 8; y++) for (int x = 1; x < 8; x++) {
                    int pixel = bitmap.getPixel(bitmap.getWidth() * x / 8, bitmap.getHeight() * y / 8);
                    r += Color.red(pixel); g += Color.green(pixel); b += Color.blue(pixel); count++;
                }
                color = ColorUtils.blendARGB(Color.rgb((int)(r / count), (int)(g / count), (int)(b / count)), Color.BLACK, .30f);
                invalidate();
            }
        });
        addView(cover, LayoutHelper.createFrame(64, 64, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, 24, 24, 24, 0));
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        title = label(context, 16, true, Color.WHITE);
        artist = label(context, 14, false, 0xffd6cdda);
        album = label(context, 14, false, 0xffbcb0c2);
        labels.addView(title, LayoutHelper.createLinear(-1, 23));
        labels.addView(artist, LayoutHelper.createLinear(-1, 21));
        labels.addView(album, LayoutHelper.createLinear(-1, 21));
        addView(labels, LayoutHelper.createFrame(-1, 72, Gravity.TOP, LocaleController.isRTL ? 24 : 100, 20, LocaleController.isRTL ? 100 : 24, 0));
        plane = context.getResources().getDrawable(R.drawable.msg_send).mutate();
        plane.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        plane.setAlpha(20);
    }

    private TextView label(Context context, int size, boolean bold, int color) {
        TextView view = new TextView(context);
        view.setTextSize(size); view.setTextColor(color);
        view.setSingleLine(true); view.setEllipsize(TextUtils.TruncateAt.END);
        view.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        if (bold) view.setTypeface(AndroidUtilities.bold());
        return view;
    }

    public void set(int account, TLRPC.Document document, MessagesController.PeerColor peerColor, long emojiId) {
        if (observing && this.account != account) {
            NotificationCenter.getInstance(this.account).removeObserver(this, NotificationCenter.fileLoaded);
            NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.fileLoaded);
        }
        this.account = account;
        if (pattern != null) pattern.removeView(this);
        pattern = emojiId == 0 ? null : AnimatedEmojiDrawable.make(account, AnimatedEmojiDrawable.CACHE_TYPE_ALERT_PREVIEW_STATIC, emojiId);
        if (pattern != null && isAttachedToWindow()) pattern.addView(this);
        if (this.document == document) { invalidate(); return; }
        this.document = document;
        generation++;
        color = peerColor == null ? 0xff591478 : ColorUtils.blendARGB(peerColor.getBgColor1(Theme.isCurrentThemeDark()), Color.BLACK, .25f);
        CharSequence name = ProfileMusicView.getTitle(document), performer = ProfileMusicView.getAuthor(document);
        title.setText(TextUtils.isEmpty(name) ? LocaleController.getString(R.string.AudioUnknownTitle) : name);
        artist.setText(TextUtils.isEmpty(performer) ? LocaleController.getString(R.string.AudioUnknownArtist) : performer);
        int duration = 0;
        if (document != null) for (TLRPC.DocumentAttribute attr : document.attributes) {
            if (attr instanceof TLRPC.TL_documentAttributeAudio) duration = (int) Math.ceil(attr.duration);
        }
        album.setText(duration > 0 ? AndroidUtilities.formatShortDuration(duration) : LocaleController.getString(R.string.AttachMusic));
        Drawable placeholder = getContext().getResources().getDrawable(R.drawable.files_music).mutate();
        placeholder.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        if (document != null) {
            TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 160);
            ImageLocation location = thumb == null ? null : ImageLocation.getForDocument(thumb, document);
            String artwork = MessageObject.getArtworkUrl(document, false);
            cover.setImage(artwork == null ? null : ImageLocation.getForPath(artwork), "160_160", location, "160_160", placeholder, document);
        } else cover.setImageDrawable(placeholder);
        updateDescription();
        readLocalMetadata();
        invalidate();
    }

    private void updateDescription() {
        setContentDescription(LocaleController.getString(R.string.AccDescrProfileMusic) + ": " + title.getText() + ", " + artist.getText() + ", " + album.getText());
    }

    private void readLocalMetadata() {
        if (document == null) return;
        File file = FileLoader.getInstance(account).getPathToAttach(document, true);
        if (!file.isFile()) file = FileLoader.getInstance(account).getPathToAttach(document, false);
        if (!file.isFile()) return;
        final File audio = file; final int token = generation;
        metadataQueue.postRunnable(() -> {
            MediaMetadataRetriever reader = new MediaMetadataRetriever();
            String albumName = null; Bitmap bitmap = null;
            try {
                reader.setDataSource(audio.getAbsolutePath());
                albumName = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
                byte[] picture = reader.getEmbeddedPicture();
                if (picture != null && picture.length <= 8 * 1024 * 1024) {
                    BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(picture, 0, picture.length, options);
                    options.inSampleSize = 1;
                    while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 320) options.inSampleSize *= 2;
                    options.inJustDecodeBounds = false;
                    bitmap = BitmapFactory.decodeByteArray(picture, 0, picture.length, options);
                }
            } catch (Exception ignored) {
                // Missing/unsupported tags keep the ordinary Telegram artwork and duration.
            } finally { try { reader.release(); } catch (Exception ignored) {} }
            final String tag = albumName; final Bitmap image = bitmap;
            AndroidUtilities.runOnUIThread(() -> {
                if (token != generation) { if (image != null) image.recycle(); return; }
                if (!TextUtils.isEmpty(tag)) album.setText(tag);
                if (image != null) cover.setImageBitmap(image);
                updateDescription();
            });
        });
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (pattern != null) pattern.addView(this);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.fileLoaded);
        observing = true;
        readLocalMetadata();
    }
    @Override protected void onDetachedFromWindow() {
        if (pattern != null) pattern.removeView(this);
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.fileLoaded);
        observing = false;
        super.onDetachedFromWindow();
    }
    @Override public void didReceivedNotification(int id, int account, Object... args) {
        if (document != null && args.length > 0 && FileLoader.getAttachFileName(document).equals(args[0])) readLocalMetadata();
    }
    @Override protected void onMeasure(int width, int height) { super.onMeasure(width, MeasureSpec.makeMeasureSpec(dp(112), MeasureSpec.EXACTLY)); }
    @Override protected void onDraw(Canvas canvas) {
        bounds.set(dp(12), dp(12), getWidth() - dp(12), getHeight() - dp(12));
        background.setShader(new LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom, color, ColorUtils.blendARGB(color, Color.BLACK, .16f), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(bounds, dp(18), dp(18), background);
        clip.rewind(); clip.addRoundRect(bounds, dp(18), dp(18), Path.Direction.CW);
        canvas.save(); canvas.clipPath(clip);
        for (int row = -1; row < 4; row++) for (int col = 0; col < 5; col++) {
            int x = getWidth() / 2 + dp(col * 38 + (row % 2) * 19), y = dp(row * 38 + 10);
            if (pattern != null) {
                canvas.saveLayerAlpha(x, y, x + dp(24), y + dp(24), 24);
                pattern.setBounds(x, y, x + dp(24), y + dp(24)); pattern.draw(canvas);
                canvas.restore();
            }
            else { plane.setBounds(x, y, x + dp(22), y + dp(22)); plane.draw(canvas); }
        }
        canvas.restore();
    }
}
