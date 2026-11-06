package org.telegram.ui;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.BitmapDrawable;
import android.os.SystemClock;
import android.view.*;
import java.util.ArrayList;
import org.telegram.messenger.*;
import org.telegram.ui.ActionBar.*;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Cells.ChatActionCell;

/** A frozen visual snapshot, with no message IDs, controllers, observers or sending APIs. */
public final class GhostGravityActivity extends BaseFragment {
    public static boolean isCommand(CharSequence text) { return text != null && "gravity123".equals(text.toString().trim()); }
    public static final class Snapshot {
        public final Bitmap bitmap;
        public final float x, y, width, height;
        public Snapshot(Bitmap bitmap, float x, float y, float width, float height) {
            this.bitmap = bitmap; this.x = x; this.y = y; this.width = width; this.height = height;
        }
    }
    public static final class Scene {
        public final ArrayList<Snapshot> snapshots = new ArrayList<>();
        public float width, height;
        public void dispose() { for (Snapshot snapshot : snapshots) if (!snapshot.bitmap.isRecycled()) snapshot.bitmap.recycle(); snapshots.clear(); }
    }
    public static Scene capture(ChatActivity chat) {
        ViewGroup list = chat.getChatListView();
        float density = chat.getParentActivity().getResources().getDisplayMetrics().density;
        int top = Math.max(0, Math.round(chat.getChatListViewPadding())), bottom = list.getHeight() - list.getPaddingBottom();
        if (chat.getChatActivityEnterView() != null && chat.getChatActivityEnterView().getVisibility() == View.VISIBLE) {
            int[] a = new int[2], b = new int[2]; list.getLocationInWindow(a); chat.getChatActivityEnterView().getLocationInWindow(b);
            if (b[1] > a[1] + top) bottom = Math.min(bottom, b[1] - a[1]);
        }
        return captureViews(list, top, bottom, density);
    }
    public static Scene captureViews(ViewGroup list, int top, int bottom, float density) {
        Scene scene = new Scene(); scene.width = list.getWidth() / density; scene.height = Math.max(1, bottom - top) / density;
        try {
            for (int i = 0; i < list.getChildCount(); i++) {
                View child = list.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE || child.getAlpha() <= 0 || !(child instanceof ChatMessageCell || child instanceof ChatActionCell)) continue;
                int left, right, localTop, localBottom;
                if (child instanceof ChatMessageCell) {
                    ChatMessageCell cell = (ChatMessageCell) child;
                    left = cell.getBackgroundDrawableLeft() - AndroidUtilities.dp(3); right = cell.getBackgroundDrawableRight() + AndroidUtilities.dp(3);
                    localTop = cell.getBackgroundDrawableTop(); localBottom = cell.getBackgroundDrawableBottom();
                } else {
                    ChatActionCell cell = (ChatActionCell) child;
                    left = cell.getBoundsLeft() - AndroidUtilities.dp(3); right = cell.getBoundsRight() + AndroidUtilities.dp(3);
                    localTop = 0; localBottom = child.getHeight();
                }
                left = Math.max(0, left); right = Math.min(child.getWidth(), right);
                localTop = Math.max(Math.max(0, localTop), top - Math.round(child.getY()));
                localBottom = Math.min(Math.min(child.getHeight(), localBottom), bottom - Math.round(child.getY()));
                int width = right - left, height = localBottom - localTop;
                if (width < 2 || height < 2) continue;
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap); canvas.translate(-left, -localTop);
                if (child instanceof ChatMessageCell && ((ChatMessageCell) child).drawBackgroundInParent())
                    ((ChatMessageCell) child).drawBackgroundInternal(canvas, true);
                child.draw(canvas);
                if (child instanceof ChatMessageCell && ((ChatMessageCell) child).needDrawTime())
                    ((ChatMessageCell) child).drawTime(canvas, 1f, true);
                scene.snapshots.add(new Snapshot(bitmap, (child.getX() + left) / density,
                    (child.getY() + localTop - top) / density, width / density, height / density));
            }
        } catch (RuntimeException | OutOfMemoryError failure) { scene.dispose(); throw failure; }
        return scene;
    }
    public static boolean open(ChatActivity source) {
        Scene scene;
        try { scene = capture(source); }
        catch (RuntimeException | OutOfMemoryError failure) { FileLog.e(failure); return false; }
        if (scene.snapshots.isEmpty()) {
            source.showDialog(new AlertDialog.Builder(source.getParentActivity(), source.getResourceProvider())
                .setTitle("Gravity").setMessage(LocaleController.getString(R.string.GhostGravityEmpty))
                .setPositiveButton(LocaleController.getString(R.string.OK), null).create());
            scene.dispose(); return false;
        }
        if (!source.presentFragment(new GhostGravityActivity(source.getCurrentAccount(), scene, source.getResourceProvider(), source.getGameWallpaper()))) {
            scene.dispose(); return false;
        }
        return true;
    }
    private final Scene scene;
    private final Theme.ResourcesProvider resources;
    private final Drawable wallpaper;
    private GravityView game;
    private boolean resumed;
    public GhostGravityActivity(int account, Scene scene, Theme.ResourcesProvider resources, Drawable wallpaper) {
        setCurrentAccount(account); this.scene = scene; this.resources = resources; this.wallpaper = wallpaper;
    }
    @Override public Theme.ResourcesProvider getResourceProvider() { return resources; }
    @Override public View createView(Context context) {
        actionBar.setTitle("Gravity"); actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.createMenu().addItem(1, R.drawable.msg_retry).setContentDescription(LocaleController.getString(R.string.GhostGravityRestart));
        actionBar.createMenu().addItem(2, R.drawable.msg_info).setContentDescription(LocaleController.getString(R.string.GhostGravityHelp));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) {
                if (id == -1) finishFragment(); else if (id == 1 && game != null) game.restart();
                else if (id == 2) showDialog(new AlertDialog.Builder(getParentActivity(), resources).setTitle("Gravity")
                    .setMessage(LocaleController.getString(R.string.GhostGravityHelp)).setPositiveButton(LocaleController.getString(R.string.OK), null).create());
            }
        });
        return fragmentView = game = new GravityView(context);
    }
    @Override public void onResume() { super.onResume(); resumed = true; if (game != null) { game.last = 0; game.setKeepScreenOn(true); game.invalidate(); } }
    @Override public void onPause() { resumed = false; if (game != null) { game.last = 0; game.setKeepScreenOn(false); game.cancelDrag(); } super.onPause(); }
    @Override public void onFragmentDestroy() {
        resumed = false; if (game != null) { game.cancelDrag(); if (game.physics != null) game.physics.bodies.clear(); }
        scene.dispose(); super.onFragmentDestroy();
    }
    private final class GravityView extends View {
        GhostGravityPhysics physics;
        final float density = getResources().getDisplayMetrics().density;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Rect source = new Rect(), wallpaperBounds = new Rect();
        final RectF destination = new RectF();
        final GestureDetector gestures;
        VelocityTracker velocity;
        long last, touchTime;
        int pointer = -1;
        GravityView(Context context) {
            super(context); setLayerType(View.LAYER_TYPE_HARDWARE, null); setFocusable(true);
            setContentDescription(LocaleController.getString(R.string.GhostGravityHelp));
            gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent event) { return true; }
                @Override public boolean onDoubleTap(MotionEvent event) {
                    if (physics != null) {
                        GhostGravityPhysics.Body body = physics.pick(event.getX() / density, event.getY() / density);
                        if (body != null) { physics.shatter(body); performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); }
                    }
                    return true;
                }
            });
        }
        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { restart(); }
        void restart() {
            cancelDrag(); if (getWidth() == 0 || getHeight() == 0) return;
            physics = new GhostGravityPhysics(getWidth() / density, getHeight() / density);
            float scale = Math.min(1, physics.width / Math.max(1, scene.width));
            for (Snapshot snapshot : scene.snapshots) {
                GhostGravityPhysics.Body body = physics.add((snapshot.x + snapshot.width / 2) * scale,
                    (snapshot.y + snapshot.height / 2) * scale, snapshot.width * scale, snapshot.height * scale);
                body.asset = snapshot.bitmap; body.omega = (Utilities.random.nextFloat() - .5f) * .8f;
            }
            last = 0; invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            Drawable background = wallpaper != null ? wallpaper : Theme.getCachedWallpaperNonBlocking();
            if (background instanceof BitmapDrawable) {
                Bitmap bitmap = ((BitmapDrawable) background).getBitmap();
                float scale = Math.max((float)getWidth() / bitmap.getWidth(), (float)getHeight() / bitmap.getHeight());
                float width = bitmap.getWidth() * scale, height = bitmap.getHeight() * scale;
                destination.set((getWidth() - width) / 2, (getHeight() - height) / 2, (getWidth() + width) / 2, (getHeight() + height) / 2);
                paint.setAlpha(255); canvas.drawBitmap(bitmap, null, destination, paint);
            } else if (background != null) {
                wallpaperBounds.set(background.getBounds()); background.setBounds(0, 0, getWidth(), getHeight()); background.draw(canvas); background.setBounds(wallpaperBounds);
            } else canvas.drawColor(Theme.getColor(Theme.key_windowBackgroundWhite, resources));
            if (physics == null) return;
            long now = SystemClock.elapsedRealtimeNanos();
            if (resumed && last != 0) physics.step((now - last) / 1_000_000_000f); last = now;
            canvas.save(); canvas.scale(density, density);
            for (GhostGravityPhysics.Body body : physics.bodies) {
                if (!body.alive || !(body.asset instanceof Bitmap)) continue;
                Bitmap bitmap = (Bitmap) body.asset; if (bitmap.isRecycled()) continue;
                canvas.save(); canvas.translate(body.x, body.y); canvas.rotate((float)Math.toDegrees(body.angle));
                source.set(Math.round(body.u0 * bitmap.getWidth()), Math.round(body.v0 * bitmap.getHeight()), Math.round(body.u1 * bitmap.getWidth()), Math.round(body.v1 * bitmap.getHeight()));
                destination.set(-body.width / 2, -body.height / 2, body.width / 2, body.height / 2);
                paint.setAlpha(body.fragment ? (int)(255 * Math.min(1, body.life / .4f)) : 255); canvas.drawBitmap(bitmap, source, destination, paint); canvas.restore();
            }
            canvas.restore(); paint.setAlpha(255);
            if (resumed) postInvalidateOnAnimation();
        }
        void cancelDrag() { if (physics != null) physics.release(0, 0); pointer = -1; if (velocity != null) { velocity.recycle(); velocity = null; } }
        @Override public boolean onTouchEvent(MotionEvent event) {
            gestures.onTouchEvent(event);
            if (physics == null) return true;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                cancelDrag(); pointer = event.getPointerId(0); velocity = VelocityTracker.obtain(); velocity.addMovement(event); touchTime = event.getEventTime();
                GhostGravityPhysics.Body body = physics.pick(event.getX() / density, event.getY() / density);
                if (body != null) physics.grab(body, event.getX() / density, event.getY() / density);
                getParent().requestDisallowInterceptTouchEvent(true);
            } else if (action == MotionEvent.ACTION_MOVE) {
                int index = event.findPointerIndex(pointer); if (index < 0) { cancelDrag(); return true; }
                if (velocity != null) velocity.addMovement(event);
                physics.move(event.getX(index) / density, event.getY(index) / density, (event.getEventTime() - touchTime) / 1000f); touchTime = event.getEventTime();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.getActionIndex()) == pointer) {
                if (velocity != null) { velocity.addMovement(event); velocity.computeCurrentVelocity(1000, 2400 * density); physics.release(velocity.getXVelocity(pointer) / density, velocity.getYVelocity(pointer) / density); velocity.recycle(); velocity = null; }
                else physics.release(0, 0);
                pointer = -1; performClick();
            } else if (action == MotionEvent.ACTION_CANCEL) cancelDrag();
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }
}
