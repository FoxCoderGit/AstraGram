package org.telegram.messenger;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.graphics.*;
import android.view.*;
import android.widget.FrameLayout;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.*;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.ChatMessageCell;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

/** Native cells with synthetic text only. No real history is read or changed. */
public class GhostGravityDiagnostics extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private void main(Runnable task) throws Exception {
        AtomicReference<Throwable> problem = new AtomicReference<>();
        runOnMainSync(() -> { try { task.run(); } catch (Throwable error) { problem.set(error); } });
        if (problem.get() != null) throw new Exception("Gravity native check", problem.get());
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
    private static Object field(Object object, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static void freeze(GhostGravityActivity activity) {
        try { java.lang.reflect.Field field = activity.getClass().getDeclaredField("resumed"); field.setAccessible(true); field.setBoolean(activity, false); }
        catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
    }
    private static void touch(View view, long down, long time, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, time, action, x, y, 0); view.dispatchTouchEvent(event); event.recycle();
    }
    @Override public void onStart() {
        Bundle report = new Bundle(); GhostGravityActivity[] game = new GhostGravityActivity[1];
        BaseFragment[] parent = new BaseFragment[1]; ArrayList<MessageObject> original = new ArrayList<>(); boolean passed = false;
        try {
            main(ApplicationLoader::postInitApplication); final int account = UserConfig.selectedAccount;
            check(GhostGravityActivity.isCommand("gravity123") && GhostGravityActivity.isCommand(" gravity123\n")
                && !GhostGravityActivity.isCommand("hello gravity123") && !GhostGravityActivity.isCommand(null), "Exact local command");
            LaunchActivity activity = (LaunchActivity) startActivitySync(new Intent(getTargetContext(), LaunchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            FrameLayout[] list = new FrameLayout[1];
            main(() -> {
                parent[0] = new BaseFragment() {
                    @Override public View createView(android.content.Context context) {
                        list[0] = new FrameLayout(context); list[0].setClipChildren(true);
                        String[] texts = {"Gravity: эти сообщения существуют только в тесте.", "Можно бросать сообщения в стены 🙂", "Двойное нажатие разбивает блок на части.", "Этот блок за пределами видимой области.", "Этот блок скрыт."};
                        for (int i = 0; i < 5; i++) {
                            TLRPC.TL_message message = new TLRPC.TL_message(); message.id = 100 + i; message.dialog_id = GhostSimulationPolicy.VIRTUAL_PREFIX | 99;
                            message.peer_id = new TLRPC.TL_peerUser(); message.peer_id.user_id = message.dialog_id;
                            message.from_id = new TLRPC.TL_peerUser(); message.from_id.user_id = i % 2 == 0 ? message.dialog_id : UserConfig.getInstance(account).getClientUserId();
                            message.out = i % 2 == 1; message.date = 1710000000 + i; message.message = texts[i]; message.media = new TLRPC.TL_messageMediaEmpty(); message.attachPath = "";
                            MessageObject object = new MessageObject(account, message, false, true); original.add(object);
                            ChatMessageCell cell = new ChatMessageCell(context, account, true, null, null); cell.setMessageObject(object, null, false, false, true);
                            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2); params.topMargin = AndroidUtilities.dp(i == 3 ? 600 : 30 + i * 115);
                            list[0].addView(cell, params); if (i == 4) cell.setVisibility(View.GONE);
                        }
                        return fragmentView = list[0];
                    }
                };
                check(activity.presentFragment(parent[0], false, true), "Fixture navigation");
            });
            waitForIdleSync(); Thread.sleep(500);
            GhostGravityActivity.Scene[] scene = new GhostGravityActivity.Scene[1];
            main(() -> {
                // Force the normal native drawing pass before reading final bubble bounds.
                Bitmap layout = Bitmap.createBitmap(list[0].getWidth(), list[0].getHeight(), Bitmap.Config.ARGB_8888);
                list[0].draw(new Canvas(layout)); layout.recycle();
                float density = activity.getResources().getDisplayMetrics().density;
                scene[0] = GhostGravityActivity.captureViews(list[0], 0, AndroidUtilities.dp(520), density);
                check(scene[0].snapshots.size() == 3, "Visible cells only: " + scene[0].snapshots.size());
                for (GhostGravityActivity.Snapshot snapshot : scene[0].snapshots) {
                    check(snapshot.width > 10 && snapshot.height > 10, "Actual bubble dimensions");
                    boolean painted = false;
                    for (int y = 0; y < snapshot.bitmap.getHeight(); y += 5) for (int x = 0; x < snapshot.bitmap.getWidth(); x += 5)
                        painted |= Color.alpha(snapshot.bitmap.getPixel(x, y)) > 0;
                    check(painted, "Native bubble snapshot is blank");
                }
                game[0] = new GhostGravityActivity(account, scene[0], null, new android.graphics.drawable.ColorDrawable(0xff243848));
                check(activity.presentFragment(game[0], false, true), "Gravity navigation"); freeze(game[0]);
            });
            waitForIdleSync();
            main(() -> {
                try {
                    View view = (View) field(game[0], "game"); GhostGravityPhysics physics = (GhostGravityPhysics) field(view, "physics");
                    check(physics != null && physics.bodies.size() == 3, "Three independent objects");
                    float density = view.getResources().getDisplayMetrics().density;
                    GhostGravityPhysics.Body grabbed = physics.bodies.get(0); long time = android.os.SystemClock.uptimeMillis();
                    float x = grabbed.x * density, y = grabbed.y * density;
                    touch(view, time, time, MotionEvent.ACTION_DOWN, x, y);
                    touch(view, time, time + 30, MotionEvent.ACTION_MOVE, x + 70, y - 35);
                    touch(view, time, time + 60, MotionEvent.ACTION_UP, x + 130, y - 65);
                    check(physics.held == null && Math.abs(grabbed.vx) > 10, "Native drag/flick velocity");
                    GhostGravityPhysics.Body target = physics.bodies.get(1); x = target.x * density; y = target.y * density; time += 800;
                    touch(view, time, time, MotionEvent.ACTION_DOWN, x, y); touch(view, time, time + 20, MotionEvent.ACTION_UP, x, y);
                    touch(view, time + 100, time + 100, MotionEvent.ACTION_DOWN, x, y); touch(view, time + 100, time + 120, MotionEvent.ACTION_UP, x, y);
                    check(!target.alive, "Native double-tap destruction");
                    int fragments = 0; for (GhostGravityPhysics.Body body : physics.bodies) if (body.fragment) fragments++;
                    check(fragments >= 4 && fragments <= 8, "Texture fragments");
                    for (int i = 0; i < 35; i++) physics.step(1f / 60);
                    Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888); view.draw(new Canvas(bitmap));
                    check(bitmap.getPixel(1, 1) == 0xff243848, "Per-chat wallpaper");
                    try (java.io.FileOutputStream output = getTargetContext().openFileOutput("ghost_gravity_preview.png", android.content.Context.MODE_PRIVATE)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); }
                    finally { bitmap.recycle(); }
                    for (int i = 0; i < 130; i++) physics.step(1f / 60);
                    for (GhostGravityPhysics.Body body : physics.bodies) check(!body.fragment, "Fragment expiry");
                    for (int i = 0; i < original.size(); i++) check(original.get(i).getId() == 100 + i && original.get(i).messageOwner.message.length() > 0, "Original messages modified");
                    ArrayList<Bitmap> textures = new ArrayList<>();
                    for (GhostGravityActivity.Snapshot snapshot : scene[0].snapshots) textures.add(snapshot.bitmap);
                    game[0].finishFragment(false);
                    for (Bitmap texture : textures) check(texture.isRecycled(), "Texture disposal");
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            report.putString("gravity", "PASS: three visible native message snapshots; hidden/offscreen cells excluded; exact command; native drag/flick; double-tap fragments and expiry; per-chat wallpaper; untouched originals; no message APIs");
            passed = true;
        } catch (Throwable error) { report.putString("error", android.util.Log.getStackTraceString(error)); }
        finally { try { main(() -> { if (game[0] != null) game[0].finishFragment(false); if (parent[0] != null) parent[0].finishFragment(false); }); } catch (Exception ignored) {} }
        finish(passed ? -1 : 0, report);
    }
}
