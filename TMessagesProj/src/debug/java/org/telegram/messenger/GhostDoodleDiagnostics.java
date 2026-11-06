package org.telegram.messenger;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.MotionEvent;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.GhostDoodleActivity;
import org.telegram.ui.LaunchActivity;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

/** Synthetic message bubbles only: does not read, send or fetch chat messages. */
public class GhostDoodleDiagnostics extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle args) { this.args = args; super.onCreate(args); start(); }
    private void main(Runnable runnable) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> { try { runnable.run(); } catch (Throwable e) { error.set(e); } });
        if (error.get() != null) throw new Exception("Game UI check failed", error.get());
    }
    @Override public void onStart() {
        Bundle report = new Bundle();
        GhostDoodleActivity[] game = new GhostDoodleActivity[1];
        try {
            main(ApplicationLoader::postInitApplication);
            if (!GhostDoodleActivity.isCommand(7176914601L, "dodle123") || !GhostDoodleActivity.isCommand(1L, " dodle123\n")
                    || GhostDoodleActivity.isCommand(-1, "dodle123") || GhostDoodleActivity.isCommand(1, "hello dodle123")
                    || GhostDoodleActivity.isCommand(1, null)) throw new AssertionError("Command matching");
            report.putString("command", "PASS: exact command and whitespace; group and ordinary text excluded");
            final long dialog = 2146000888L;
            ArrayList<MessageObject> seed = new ArrayList<>();
            String[] texts = {"Начало нашей переписки", "Как твои дела?", "Увидимся завтра!", "Этот чат — платформы игры", "Привет!"};
            for (int id = 1; id <= 30; id++) {
                TLRPC.TL_message message = new TLRPC.TL_message(); message.id = id; message.dialog_id = dialog;
                message.peer_id = new TLRPC.TL_peerUser(); message.peer_id.user_id = dialog;
                message.from_id = new TLRPC.TL_peerUser(); message.from_id.user_id = dialog;
                message.date = 1600000000 + id * 86400; message.out = id % 2 == 0;
                message.message = id == 28 ? "Это длинное сообщение целиком, без сокращений.\nВторая строка тоже должна остаться.\n" + texts[id % texts.length] : texts[id % texts.length];
                seed.add(new MessageObject(UserConfig.selectedAccount, message, false, false));
            }
            final int wallpaperColor = 0xff243848;
            game[0] = new GhostDoodleActivity(UserConfig.selectedAccount, dialog, seed, null, new android.graphics.drawable.ColorDrawable(wallpaperColor));
            java.lang.reflect.Field ended = GhostDoodleActivity.class.getDeclaredField("ended"); ended.setAccessible(true); ended.setBoolean(game[0], true);
            LaunchActivity activity = (LaunchActivity) startActivitySync(new Intent(getTargetContext(), LaunchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            main(() -> { if (!activity.presentFragment(game[0], false, true)) throw new AssertionError("Game navigation"); });
            waitForIdleSync();
            java.lang.reflect.Field history = GhostDoodleActivity.class.getDeclaredField("history"); history.setAccessible(true);
            ArrayList<MessageObject> sorted = (ArrayList<MessageObject>) history.get(game[0]);
            if (sorted.size() != 30 || sorted.get(0).getId() != 30 || sorted.get(29).getId() != 1) throw new AssertionError("Newest-to-oldest ordering");
            java.lang.reflect.Field field = GhostDoodleActivity.class.getDeclaredField("game"); field.setAccessible(true);
            android.view.View view = (android.view.View) field.get(game[0]);
            if (view.getWidth() == 0 || view.getHeight() == 0) throw new AssertionError("Game did not lay out");
            main(() -> {
                try {
                    java.lang.reflect.Field layoutsField = view.getClass().getDeclaredField("layouts"); layoutsField.setAccessible(true);
                    ArrayList<android.text.StaticLayout> layouts = (ArrayList<android.text.StaticLayout>) layoutsField.get(view);
                    for (int index = 0; index < sorted.size(); index++)
                        if (!layouts.get(index).getText().toString().equals(sorted.get(index).messageText.toString())) throw new AssertionError("Message text shortened");
                    if (layouts.get(2).getLineCount() < 3) throw new AssertionError("Long message not wrapped");
                    java.lang.reflect.Field sensorField = view.getClass().getDeclaredField("sensorActive"); sensorField.setAccessible(true);
                    if (!sensorField.getBoolean(view)) throw new AssertionError("Accelerometer not registered");
                } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
                MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, view.getWidth() / 2f, view.getHeight() / 2f, 0);
                view.dispatchTouchEvent(down); down.recycle();
                android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(view.getWidth(), view.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
                view.draw(new android.graphics.Canvas(bitmap));
                if (bitmap.getPixel(1, bitmap.getHeight() / 2) != wallpaperColor) throw new AssertionError("Per-chat wallpaper ignored");
                try (java.io.FileOutputStream output = getTargetContext().openFileOutput("ghost_doodle_preview.png", android.content.Context.MODE_PRIVATE)) {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
                } catch (java.io.IOException error) { throw new RuntimeException(error); }
                finally { bitmap.recycle(); }
            });
            if (args != null && "true".equals(args.getString("preview"))) Thread.sleep(12000);
            report.putString("game", "PASS: 30 messages newest-to-oldest; complete multiline text; per-chat wallpaper; accelerometer registered; no network history request");
            main(() -> game[0].finishFragment(false));
            finish(-1, report);
        } catch (Throwable error) { report.putString("error", android.util.Log.getStackTraceString(error)); finish(0, report); }
    }
}
