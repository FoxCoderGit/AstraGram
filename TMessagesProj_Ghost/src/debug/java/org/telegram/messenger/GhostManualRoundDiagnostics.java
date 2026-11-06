package org.telegram.messenger;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import java.io.File;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.SecretVoicePlayer;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.VideoPlayer;

/** Synthetic local-only dialog. No real expiring messages or network actions. */
public final class GhostManualRoundDiagnostics extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o);
    }
    private void main(Runnable r) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> { try { r.run(); } catch (Throwable t) { error.set(t); } });
        if (error.get() != null) throw new Exception(error.get());
    }
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static class Preview extends BaseFragment {
        FrameLayout layout;
        @Override public View createView(Context context) {
            actionBar.setTitle("Synthetic round playback check");
            return fragmentView = layout = new FrameLayout(context);
        }
    }
    @Override public void onStart() {
        Bundle report = new Bundle();
        Preview preview = new Preview();
        SecretVoicePlayer[] dialog = new SecretVoicePlayer[1];
        AtomicInteger reads = new AtomicInteger(), deletes = new AtomicInteger(), destroys = new AtomicInteger();
        Throwable failure = null;
        try {
            main(ApplicationLoader::postInitApplication);
            File file = new File(getTargetContext().getExternalFilesDir(null), "ghost84-round.mp4");
            check(file.isFile(), "Synthetic MP4 missing");
            LaunchActivity activity = (LaunchActivity) startActivitySync(new Intent(getTargetContext(), LaunchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            main(() -> check(activity.presentFragment(preview, false, true), "Preview navigation"));
            waitForIdleSync();
            final MessageObject[] object = new MessageObject[1];
            final ChatMessageCell[] cell = new ChatMessageCell[1];
            main(() -> {
                int account = UserConfig.selectedAccount;
                long self = UserConfig.getInstance(account).getClientUserId();
                TLRPC.TL_message m = new TLRPC.TL_message();
                m.id = -2140000084; m.dialog_id = self; m.peer_id = new TLRPC.TL_peerUser(); m.peer_id.user_id = self;
                m.from_id = new TLRPC.TL_peerUser(); m.from_id.user_id = self;
                m.out = false; m.media_unread = true; m.date = 1700000000; m.message = ""; m.attachPath = file.getAbsolutePath();
                m.media = new TLRPC.TL_messageMediaDocument(); m.media.ttl_seconds = Integer.MAX_VALUE;
                TLRPC.TL_document doc = new TLRPC.TL_document(); doc.id = -2140000084; doc.mime_type = "video/mp4"; doc.size = file.length(); doc.file_reference = new byte[0];
                TLRPC.TL_documentAttributeVideo attr = new TLRPC.TL_documentAttributeVideo(); attr.round_message = true; attr.w = attr.h = 320; attr.duration = 2;
                doc.attributes.add(attr); m.media.document = doc;
                object[0] = new MessageObject(account, m, false, false); object[0].attachPathExists = true; object[0].mediaExists = true;
                cell[0] = new ChatMessageCell(activity, account, false, null, preview.getResourceProvider());
                cell[0].setMessageObject(object[0], null, false, false, false);
                preview.layout.addView(cell[0], new FrameLayout.LayoutParams(-1, -2));
                check(object[0].isRoundOnce() && GhostManualMedia.hold(object[0]), "Incoming once-round classification");
            });
            waitForIdleSync();
            main(() -> {
                dialog[0] = new SecretVoicePlayer(activity);
                dialog[0].setCell(cell[0], reads::incrementAndGet, deletes::incrementAndGet, destroys::incrementAndGet);
                try { ((VideoPlayer) field(dialog[0], "player")).setVolume(0); } catch (Exception e) { throw new RuntimeException(e); }
                dialog[0].show();
            });
            Thread.sleep(1200);
            final int[] point = new int[2];
            main(() -> {
                try {
                    check((boolean) field(dialog[0], "renderedFirstFrame"), "No decoded video frame");
                    RectF rect = (RectF) field(dialog[0], "rect");
                    ChatMessageCell clone = (ChatMessageCell) field(dialog[0], "myCell");
                    clone.getLocationOnScreen(point); point[0] += (int) rect.centerX(); point[1] += (int) rect.centerY();
                    check(reads.get() == 0 && deletes.get() == 0 && destroys.get() == 0, "Open consumed the fixture");
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            Bitmap screen = getUiAutomation().takeScreenshot();
            check(screen != null, "Screenshot unavailable");
            int pixel = screen.getPixel(point[0], point[1]); screen.recycle();
            check(Color.red(pixel) > 160 && Color.green(pixel) < 120 && Color.blue(pixel) < 120, "Live red video frame is covered by preview: " + Integer.toHexString(pixel));
            report.putString("frame", "PASS: decoded frame visible in composed dialog screenshot");
            Thread.sleep(2000);
            main(() -> {
                check(dialog[0].isShown(), "Playback end closed manual dialog");
                check(!object[0].forceExpired && object[0].messageOwner.media_unread, "Playback end expired/consumed fixture");
                check(reads.get() == 0 && deletes.get() == 0 && destroys.get() == 0, "Automatic read/delete action");
                dialog[0].onBackPressed();
                check(reads.get() == 0 && deletes.get() == 0 && destroys.get() == 0, "Close consumed fixture");
            });
            report.putString("lifecycle", "PASS: open, playback end and Back never read/delete/destroy synthetic message");
        } catch (Throwable t) { failure = t; }
        finally {
            try { main(() -> { if (dialog[0] != null && dialog[0].isShown()) dialog[0].dismiss(); preview.finishFragment(false); }); }
            catch (Throwable t) { if (failure == null) failure = t; }
        }
        if (failure != null) report.putString("error", android.util.Log.getStackTraceString(failure));
        finish(failure == null ? -1 : 0, report);
    }
}
