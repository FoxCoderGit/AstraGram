package org.telegram.messenger;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import org.telegram.tgnet.*;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.GhostSimulationActivity;
import org.telegram.ui.LaunchActivity;
import java.io.File;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Uses synthetic messages and virtual peers only. No test sends to a real account. */
public class GhostSimulationDiagnostics extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private void main(Runnable task) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> { try { task.run(); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() != null) throw new Exception("Simulation UI check", error.get());
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
    private void denied(int account, TLObject request) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> result = new AtomicReference<>();
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
            result.set(error == null ? "unexpected success" : error.text); done.countDown();
        });
        check(done.await(4, TimeUnit.SECONDS), "Guard callback timeout");
        check("SIMULATION_LOCAL_ONLY".equals(result.get()), "Transport guard: " + result.get());
    }
    private TLRPC.TL_messages_sendMessage request(long peer) {
        TLRPC.TL_messages_sendMessage request = new TLRPC.TL_messages_sendMessage();
        TLRPC.TL_inputPeerUser input = new TLRPC.TL_inputPeerUser(); input.user_id = peer;
        request.peer = input; request.message = "synthetic isolation check"; request.random_id = 123;
        return request;
    }
    private static final class Source extends ChatActivity {
        final TLRPC.User fixture;
        Source(int account, TLRPC.User fixture) { super(new Bundle()); setCurrentAccount(account); this.fixture = fixture; }
        @Override public TLRPC.User getCurrentUser() { return fixture; }
        @Override public long getDialogId() { return fixture.id; }
    }
    @Override public void onStart() {
        Bundle report = new Bundle(); GhostSimulation.Session[] session = new GhostSimulation.Session[1];
        GhostSimulationActivity[] chat = new GhostSimulationActivity[1];
        org.telegram.ui.ActionBar.BaseFragment[] fixtureParent = new org.telegram.ui.ActionBar.BaseFragment[1];
        LaunchActivity[] launched = new LaunchActivity[1]; boolean passed = false;
        try { main(ApplicationLoader::postInitApplication); }
        catch (Exception failure) { report.putString("error", android.util.Log.getStackTraceString(failure)); finish(0, report); return; }
        final int account = UserConfig.selectedAccount;
        boolean saved = MessagesController.getMainSettings(account).getBoolean("ghost_sim_save", false);
        try {
            main(ApplicationLoader::postInitApplication);
            LaunchActivity activity = (LaunchActivity) startActivitySync(new Intent(getTargetContext(), LaunchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            launched[0] = activity;
            main(() -> activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            waitForIdleSync(); Thread.sleep(1200);
            // Enter from a stable fragment, like a real chat, rather than the launch screen.
            main(() -> activity.presentFragment(fixtureParent[0] = new org.telegram.ui.ActionBar.BaseFragment() {
                @Override public android.view.View createView(android.content.Context context) {
                    return fragmentView = new android.widget.FrameLayout(context);
                }
            }, false, true));
            waitForIdleSync(); Thread.sleep(1800);
            main(() -> MessagesController.getMainSettings(account).edit().putBoolean("ghost_sim_save", false).commit());
            TLRPC.TL_user user = new TLRPC.TL_user(); user.id = 2146000999L; user.first_name = "Тест симуляции"; user.last_name = "";
            user.phone = "70000000000"; user.flags |= 16;
            user.photo = new TLRPC.TL_userProfilePhotoEmpty(); user.status = new TLRPC.TL_userStatusOnline(); user.flags |= 2 | 32 | 64;
            Source source = new Source(account, user);
            for (int id = 1; id <= 4; id++) {
                TLRPC.TL_message message = new TLRPC.TL_message(); message.id = id; message.dialog_id = user.id;
                message.peer_id = new TLRPC.TL_peerUser(); message.peer_id.user_id = user.id;
                message.from_id = new TLRPC.TL_peerUser(); message.from_id.user_id = id % 2 == 0 ? UserConfig.getInstance(account).getClientUserId() : user.id;
                message.flags |= 256; message.out = id % 2 == 0; message.date = ConnectionsManager.getInstance(account).getCurrentTime() - 60 + id;
                message.message = id % 2 == 0 ? "Это копия истории. Настоящий чат не изменяется." : "Привет! Здесь можно попробовать разговор.";
                source.messages.add(new MessageObject(account, message, false, true));
            }
            main(() -> session[0] = GhostSimulation.begin(source));
            GhostSimulation.Session value = session[0];
            check(value != null && value.user != user && value.user.id != user.id, "Independent virtual user");
            check(value.history.size() == 4 && source.messages.get(0).getId() == 1, "Cloned history");
            denied(account, request(value.peer));
            denied(account, request(user.id)); // Synthetic non-existent peer, denied while active.
            denied(account, new TLRPC.TL_upload_saveFilePart());
            report.putString("transport", "PASS: virtual peer, real-peer helper bypass and uploads rejected before network serialization");
            main(() -> { chat[0] = new GhostSimulationActivity(value); check(activity.presentFragment(chat[0], false, true), "Native chat navigation"); });
            waitForIdleSync(); Thread.sleep(600);
            main(() -> {
                check(chat[0].avatarContainer.getTitleTextView().getText().toString().equals(user.first_name), "Contact name replaced by phone");
                value.typing = true; chat[0].refreshSimulationHeader();
                try {
                    android.view.View subtitle = chat[0].avatarContainer.getSubtitleTextView();
                    check(subtitle.getClass().getMethod("getText").invoke(subtitle).toString().equals(LocaleController.getString(R.string.GhostSimTyping)), "Typing text");
                } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
                value.typing = false;
            });
            main(() -> {
                SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of("Привет! Это локальный ответ 🙂", value.peer);
                params.replyToMsg = value.history.get(0); SendMessagesHelper.getInstance(account).sendMessage(params);
            });
            waitForIdleSync();
            check(value.generated.size() == 1 && value.generated.get(0).replyMessageObject != null, "Local text and reply: count=" + value.generated.size() + " active=" + GhostSimulation.active(account) + " owner=" + (value.owner != null) + " reply=" + (value.generated.isEmpty() ? "missing" : value.generated.get(0).replyMessageObject != null) + " closed=" + value.closedFrom);
            MessageObject text = value.generated.get(0);
            main(() -> check(chat[0].messages.contains(text), "Local message missing from the visible native history"));
            check(text.messageOwner.send_state == MessageObject.MESSAGE_SEND_STATE_SENT && text.getDialogId() == value.peer, "Local message delivered");
            File input = new File(getTargetContext().getCacheDir(), "simulation-fixture.txt");
            java.nio.file.Files.write(input.toPath(), "synthetic document".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            main(() -> {
                TLRPC.TL_document document = new TLRPC.TL_document(); document.id = 0; document.file_reference = new byte[0]; document.mime_type = "text/plain"; document.size = input.length();
                TLRPC.TL_documentAttributeFilename name = new TLRPC.TL_documentAttributeFilename(); name.file_name = "Локальный документ.txt"; document.attributes.add(name);
                SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of("", value.peer); params.document = document; params.path = input.getAbsolutePath();
                SendMessagesHelper.getInstance(account).sendMessage(params);
            });
            for (int step = 0; step < 30 && value.generated.size() < 2; step++) { Thread.sleep(100); waitForIdleSync(); }
            check(value.generated.size() == 2, "Local document captured");
            File copiedDocument = new File(value.generated.get(1).messageOwner.attachPath);
            check(copiedDocument.isFile() && !input.getAbsolutePath().equals(copiedDocument.getAbsolutePath()), "Attachment copied into isolated storage");
            main(() -> {
                ArrayList<MessageObject> forward = new ArrayList<>(); forward.add(text);
                check(GhostSimulation.forward(account, value.peer, forward), "Local forwarding capture");
            });
            waitForIdleSync(); check(value.generated.size() == 3 && value.generated.get(2).messageOwner.message.equals(text.messageOwner.message), "Forward preserves text");
            main(() -> {
                SendMessagesHelper.getInstance(account).editMessage(text, "Изменённый локальный ответ 🙂", false, chat[0], null, 0, 0);
                check(text.messageOwner.message.equals("Изменённый локальный ответ 🙂"), "Local edit");
            });
            // A complete quiet period must produce local typing, without a Telegram typing RPC.
            main(() -> { value.paused = false; value.idle(); });
            Thread.sleep(5500);
            main(() -> check(value.typing, "5-second idle timer starts local typing: active=" + GhostSimulation.active(account) + " paused=" + value.paused + " blocked=" + value.blocked + " closed=" + value.closedFrom));
            main(() -> {
                value.cancel(); value.typing = true; chat[0].refreshSimulationHeader();
                android.view.View view = chat[0].getFragmentView(); check(view.getWidth() > 0, "Native chat layout");
                android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(view.getWidth(), view.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
                view.draw(new android.graphics.Canvas(bitmap));
                try (java.io.FileOutputStream output = getTargetContext().openFileOutput("ghost_simulation_preview.png", android.content.Context.MODE_PRIVATE)) {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
                } catch (java.io.IOException error) { throw new RuntimeException(error); }
                finally { bitmap.recycle(); }
            });
            main(() -> {
                try {
                    java.lang.reflect.Field randomField = value.getClass().getDeclaredField("random"); randomField.setAccessible(true); Random random = (Random) randomField.get(value);
                    java.lang.reflect.Method react = value.getClass().getDeclaredMethod("react"); react.setAccessible(true);
                    for (int scenario = 0; scenario < 4; scenario++) {
                        value.cancel(); value.blocked = false; long seed = 0;
                        while (GhostSimulationResponses.scenario(new Random(seed).nextInt(100)) != scenario) seed++;
                        random.setSeed(seed); int before = value.generated.size(); react.invoke(value);
                        check(scenario == 0 ? value.blocked && value.generated.size() == before : value.generated.size() == before + 1 && !value.generated.get(before).isOut(), "Response scenario " + scenario);
                    }
                    value.cancel(); value.blocked = false; chat[0].refreshSimulationHeader();
                } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
                ArrayList<Integer> remove = new ArrayList<>(); remove.add(text.getId()); value.remove(remove);
                check(!value.history.contains(text), "Local deletion");
                check(user.id == 2146000999L && user.status instanceof TLRPC.TL_userStatusOnline && source.messages.size() == 4 && source.messages.get(0).getId() == 1, "Original user and history untouched");
                try { java.lang.reflect.Field approved = GhostSimulationActivity.class.getDeclaredField("approved"); approved.setAccessible(true); approved.setBoolean(chat[0], true); }
                catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
                chat[0].finishFragment(false);
            });
            waitForIdleSync();
            check(!GhostSimulation.active(account), "Exit clears simulation");
            check(MessagesController.getInstance(account).getUser(value.peer) == null, "Virtual user removed");
            check(!copiedDocument.exists(), "Discarded media removed");
            denied(account, request(value.peer));
            report.putString("chat", "PASS: native chat, local text/reply/document/forward/edit/delete, copied attachment, 5s timer, all four scenarios, untouched real history, exit cleanup and permanent virtual-peer guard");
            passed = true;
        } catch (Throwable failure) { report.putString("error", android.util.Log.getStackTraceString(failure)); }
        finally {
            try { main(() -> {
                if (launched[0] != null) launched[0].getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (chat[0] != null) {
                    try { java.lang.reflect.Field approved = GhostSimulationActivity.class.getDeclaredField("approved"); approved.setAccessible(true); approved.setBoolean(chat[0], true); }
                    catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
                    chat[0].finishFragment(false);
                }
                if (session[0] != null) GhostSimulation.end(session[0]);
                if (fixtureParent[0] != null) fixtureParent[0].finishFragment(false);
                MessagesController.getMainSettings(account).edit().putBoolean("ghost_sim_save", saved).commit();
            }); }
            catch (Exception ignored) {}
        }
        finish(passed ? -1 : 0, report);
    }
}
