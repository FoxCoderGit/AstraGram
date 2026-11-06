package org.telegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.text.StaticLayout;
import android.text.Layout;
import org.telegram.messenger.*;
import org.telegram.ui.ActionBar.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Comparator;

/** Experimental local game. History is read-only; the command never reaches SendMessagesHelper. */
public final class GhostDoodleActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    public static boolean isCommand(long dialog, CharSequence text) {
        return dialog > 0 && text != null && "dodle123".equals(text.toString().trim());
    }
    private final long dialog;
    private final ArrayList<MessageObject> history = new ArrayList<>();
    private final HashSet<Integer> ids = new HashSet<>();
    private GameView game;
    private boolean loading, ended, resumed;
    private final Theme.ResourcesProvider chatResources;
    private final android.graphics.drawable.Drawable chatWallpaper;
    public GhostDoodleActivity(int account, long dialog, ArrayList<MessageObject> seed) {
        this(account, dialog, seed, null, null);
    }
    public GhostDoodleActivity(int account, long dialog, ArrayList<MessageObject> seed, Theme.ResourcesProvider resources, android.graphics.drawable.Drawable wallpaper) {
        currentAccount = account; this.dialog = dialog; chatResources = resources; chatWallpaper = wallpaper; addMessages(seed);
    }
    @Override public Theme.ResourcesProvider getResourceProvider() { return chatResources; }
    private void addMessages(ArrayList<MessageObject> messages) {
        ArrayList<MessageObject> ordered = new ArrayList<>(messages);
        boolean secret = DialogObject.isEncryptedDialog(dialog);
        Comparator<MessageObject> newestFirst = Comparator.<MessageObject>comparingInt(m -> m.messageOwner.date).reversed()
            .thenComparing(secret ? Comparator.comparingInt(MessageObject::getId) : Comparator.comparingInt(MessageObject::getId).reversed());
        ordered.sort(newestFirst);
        MessageObject oldest = history.isEmpty() ? null : history.get(history.size() - 1);
        for (MessageObject message : ordered) {
            if (message.getId() == 0 || !secret && message.getId() < 0 || message.type < 0 || message.getDialogId() != dialog
                    || oldest != null && newestFirst.compare(message, oldest) <= 0) continue;
            if (ids.add(message.getId())) history.add(message);
        }
        if (game != null) game.syncPlatforms();
    }
    @Override public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.messagesDidLoad);
        return super.onFragmentCreate();
    }
    @Override public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.GhostDoodleTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); else if (id == 1 && game != null) game.restart(); }
        });
        actionBar.createMenu().addItem(1, R.drawable.msg_retry).setContentDescription(LocaleController.getString(R.string.GhostDoodleRestart));
        game = new GameView(context);
        loadOlder();
        return fragmentView = game;
    }
    private void loadOlder() {
        if (loading || ended) return;
        loading = true;
        int max = history.isEmpty() ? 0 : history.get(history.size() - 1).getId();
        getMessagesController().loadMessages(dialog, 0, false, 50, max, 0, DialogObject.isEncryptedDialog(dialog), 0, classGuid, 0, 0, 0, 0, 0, 0, false);
    }
    @Override public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.messagesDidLoad || args.length < 11 || (Long) args[0] != dialog || (Integer) args[10] != classGuid) return;
        loading = false;
        int before = history.size();
        addMessages((ArrayList<MessageObject>) args[2]);
        ended = history.size() == before || Boolean.TRUE.equals(args[9]);
        if (game != null) game.invalidate();
    }
    @Override public void onResume() { super.onResume(); resumed = true; if (game != null) { game.sensors(true); game.last = 0; game.invalidate(); } }
    @Override public void onPause() { resumed = false; if (game != null) { game.sensors(false); game.setKeepScreenOn(false); } super.onPause(); }
    @Override public void onFragmentDestroy() { resumed = false; if (game != null) game.sensors(false); getNotificationCenter().removeObserver(this, NotificationCenter.messagesDidLoad); super.onFragmentDestroy(); }

    private final class GameView extends View implements SensorEventListener {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final android.text.TextPaint text = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        final RectF rect = new RectF();
        final android.graphics.Rect wallpaperBounds = new android.graphics.Rect();
        final float density = getResources().getDisplayMetrics().density;
        final ArrayList<StaticLayout> layouts = new ArrayList<>();
        final ArrayList<android.text.TextPaint> layoutPaints = new ArrayList<>();
        final SensorManager sensorManager = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
        final Sensor sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        boolean reading, sensorActive;
        float filtered, neutral, tilt;
        int facing = 1;
        GhostDoodlePhysics physics;
        Float target;
        long last;
        boolean started;
        GameView(Context context) { super(context); setContentDescription(LocaleController.getString(R.string.GhostDoodleHelp)); }
        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            physics = new GhostDoodlePhysics(w / density, h / density); layouts.clear(); layoutPaints.clear(); syncPlatforms(); physics.restart(); last = 0; reading = false; tilt = 0;
        }
        void syncPlatforms() {
            if (physics != null) while (physics.platforms.size() < history.size()) {
                MessageObject message = history.get(physics.platforms.size());
                android.text.TextPaint body = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG); body.setTextSize(14);
                String caption = message.messageText == null ? "" : message.messageText.toString();
                float bubbleWidth = Math.min(physics.width * .48f, Math.max(physics.width * .34f, body.measureText(caption) + 24));
                StaticLayout layout = StaticLayout.Builder.obtain(caption, 0, caption.length(), body, (int) (bubbleWidth - 20))
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE).build();
                layouts.add(layout); layoutPaints.add(body);
                physics.addMessagePlatform(message.isOutOwner(), bubbleWidth, layout.getHeight() + 30);
            }
            invalidate();
        }
        void restart() { if (physics != null) physics.restart(); started = false; last = 0; target = null; neutral = filtered; invalidate(); }
        void sensors(boolean enable) {
            sensorManager.unregisterListener(this); sensorActive = false;
            if (enable && sensor != null) sensorActive = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
        }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        @Override public void onSensorChanged(SensorEvent event) {
            int rotation = getDisplay() == null ? 0 : getDisplay().getRotation();
            float value = rotation == 1 ? -event.values[1] : rotation == 3 ? event.values[1] : rotation == 2 ? -event.values[0] : event.values[0];
            if (!reading) { filtered = neutral = value; reading = true; }
            else filtered += .18f * (value - filtered);
            tilt = -(filtered - neutral) / 1.8f;
        }
        int color(int key) { return Theme.getColor(key, getResourceProvider()); }
        String label(int id) { return LocaleController.getString(id); }
        void line(Canvas canvas, String value, float x, float y, float size, int color) {
            text.setTextSize(size); text.setColor(color); canvas.drawText(value, x, y, text);
        }
        void drawCharacter(Canvas canvas, float x, float feet) {
            canvas.save(); canvas.translate(x, feet); canvas.scale(facing, 1);
            android.graphics.Path body = new android.graphics.Path();
            body.moveTo(-14, -9); body.cubicTo(-18, -28, -10, -44, 4, -43);
            body.cubicTo(17, -43, 20, -33, 17, -17); body.lineTo(13, -7);
            body.quadTo(0, -3, -14, -9); body.close();
            paint.setStyle(Paint.Style.FILL); paint.setColor(0xffa8ce52); canvas.drawPath(body, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.6f); paint.setColor(0xff354222); canvas.drawPath(body, paint);
            paint.setStyle(Paint.Style.FILL); paint.setColor(0xffb4d95d);
            rect.set(10, -29, 28, -20); canvas.drawRoundRect(rect, 4, 4, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setColor(0xff354222); canvas.drawRoundRect(rect, 4, 4, paint);
            paint.setStyle(Paint.Style.FILL); paint.setColor(0xff26311b); canvas.drawOval(25, -28, 30, -21, paint);
            paint.setColor(0xffffffff); canvas.drawOval(1, -39, 7, -31, paint); canvas.drawOval(9, -38, 15, -30, paint);
            paint.setColor(0xff26311b); canvas.drawOval(4, -37, 7, -32, paint); canvas.drawOval(12, -36, 15, -31, paint);
            paint.setStrokeWidth(1.3f);
            for (int i = 0; i < 4; i++) canvas.drawLine(-11, -19 + i * 3, 10, -19 + i * 3, paint);
            paint.setStrokeWidth(2); canvas.drawLine(-8, -8, -9, -1, paint); canvas.drawLine(7, -8, 9, -1, paint);
            paint.setColor(0xffd4a83b); canvas.drawOval(-14, -4, -4, 0, paint); canvas.drawOval(5, -4, 15, 0, paint);
            canvas.restore();
        }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(color(Theme.key_windowBackgroundGray));
            android.graphics.drawable.Drawable wallpaper = chatWallpaper != null ? chatWallpaper : Theme.getCachedWallpaperNonBlocking();
            if (wallpaper != null) {
                wallpaperBounds.set(wallpaper.getBounds());
                wallpaper.setBounds(0, 0, getWidth(), getHeight());
                wallpaper.draw(canvas);
                wallpaper.setBounds(wallpaperBounds);
            }
            if (physics == null) return;
            long now = SystemClock.elapsedRealtime();
            boolean won = ended && !history.isEmpty() && physics.reached == history.size() - 1;
            if (resumed && started && !won && last != 0) {
                float previousX = physics.x;
                if (sensorActive) physics.stepTilt((now - last) / 1000f, tilt);
                else physics.step((now - last) / 1000f, target);
                if (Math.abs(physics.x - previousX) > .05f) facing = physics.x > previousX ? 1 : -1;
            }
            last = now;
            setKeepScreenOn(resumed && started && !won && !physics.lost);
            canvas.save(); canvas.scale(density, density);
            float width = physics.width, height = physics.height;
            for (int i = 0; i < physics.platforms.size(); i++) {
                GhostDoodlePhysics.Platform platform = physics.platforms.get(i);
                float top = height - (platform.y - physics.camera);
                if (top + platform.height < 57 || top > height + 45) continue;
                MessageObject message = history.get(i);
                rect.set(platform.x, top, platform.x + platform.width, top + platform.height);
                paint.setColor(color(message.isOutOwner() ? Theme.key_chat_outBubble : Theme.key_chat_inBubble)); canvas.drawRoundRect(rect, 11, 11, paint);
                layoutPaints.get(i).setColor(color(message.isOutOwner() ? Theme.key_chat_messageTextOut : Theme.key_chat_messageTextIn));
                canvas.save(); canvas.translate(platform.x + 10, top + 8); layouts.get(i).draw(canvas); canvas.restore();
                line(canvas, LocaleController.formatDateChat(message.messageOwner.date, true), platform.x + 9, top + platform.height - 8, 9,
                    color(message.isOutOwner() ? Theme.key_chat_outTimeText : Theme.key_chat_inTimeText));
            }
            drawCharacter(canvas, physics.x, height - (physics.y - physics.camera));
            paint.setColor(color(Theme.key_windowBackgroundWhite)); canvas.drawRect(0, 0, width, 57, paint);
            line(canvas, label(R.string.GhostDoodleScore) + ": " + physics.reached, 16, 23, 16, color(Theme.key_windowBackgroundWhiteBlackText));
            line(canvas, label(R.string.GhostDoodleHint), 16, 43, 11, color(Theme.key_windowBackgroundWhiteGrayText));
            if (!started || physics.lost || won || history.isEmpty()) {
                paint.setColor(0x99000000); canvas.drawRect(0, 57, width, height, paint);
                String title = history.isEmpty() ? label(loading ? R.string.Loading : R.string.GhostDoodleEmpty)
                    : physics.lost ? label(R.string.GhostDoodleLost) : won ? label(R.string.GhostDoodleFinished) : label(R.string.GhostDoodleTitle);
                text.setTextSize(22); text.setColor(0xffffffff); canvas.drawText(title, Math.max(12, (width - text.measureText(title)) / 2), height * .43f, text);
                String help = label(started ? R.string.GhostDoodleRestart : R.string.GhostDoodleHelp);
                text.setTextSize(13); help = TextUtils.ellipsize(help, text, width - 32, TextUtils.TruncateAt.END).toString();
                canvas.drawText(help, (width - text.measureText(help)) / 2, height * .43f + 32, text);
            }
            canvas.restore();
            if (resumed && started && !physics.lost && !won) {
                if (history.size() - physics.reached < 12) loadOlder();
                postInvalidateOnAnimation();
            }
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (physics == null) return true;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (physics.lost || ended && !history.isEmpty() && physics.reached == history.size() - 1) physics.restart();
                if (!history.isEmpty()) started = true;
                neutral = filtered; tilt = 0;
                target = event.getX() / density; last = 0; invalidate();
            } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) target = event.getX() / density;
            else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) { target = null; performClick(); }
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }
}
