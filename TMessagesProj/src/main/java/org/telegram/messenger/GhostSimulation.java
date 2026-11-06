package org.telegram.messenger;

import org.telegram.tgnet.*;
import org.telegram.ui.GhostSimulationActivity;
import org.telegram.ui.ChatActivity;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

/** A separate in-memory conversation. No real peer, message or user is mutated. */
public final class GhostSimulation {
    private static final ConcurrentHashMap<Integer, Session> sessions = new ConcurrentHashMap<>();
    private static final AtomicInteger nextId = new AtomicInteger(-1700000000);
    private GhostSimulation() {}
    public static boolean active(int account) { return sessions.containsKey(account); }
    public static Session session(int account, long peer) {
        Session value = sessions.get(account); return value != null && value.peer == peer ? value : null;
    }
    public static boolean save(int account) { return MessagesController.getMainSettings(account).getBoolean("ghost_sim_save", false); }
    public static boolean rough(int account) { return MessagesController.getMainSettings(account).getBoolean("ghost_sim_rough", false); }
    public static Session begin(ChatActivity source) {
        int account = source.getCurrentAccount();
        if (active(account)) return null;
        TLRPC.User original = source.getCurrentUser();
        if (original == null) return null;
        Session result = new Session(account, source.getDialogId(), copyUser(original));
        result.user.id = result.peer; result.user.self = false; result.user.bot = false;
        MessagesController.getInstance(account).putUser(result.user, false);
        ArrayList<MessageObject> seed = new ArrayList<>(source.messages);
        seed.sort(Comparator.comparingInt(m -> m.messageOwner.date));
        HashMap<Integer, Integer> ids = new HashMap<>();
        for (MessageObject object : seed) {
            if (object.type < 0) continue;
            TLRPC.Message message = copyMessage(object.messageOwner, account);
            if (message == null) continue;
            int oldId = message.id;
            message.id = nextId.getAndDecrement(); message.local_id = message.id; ids.put(oldId, message.id);
            message.dialog_id = result.peer; message.peer_id = new TLRPC.TL_peerUser(); message.peer_id.user_id = result.peer;
            if (!message.out) { message.from_id = new TLRPC.TL_peerUser(); message.from_id.user_id = result.peer; }
            message.unread = false; message.send_state = MessageObject.MESSAGE_SEND_STATE_SENT;
            result.history.add(new MessageObject(account, message, false, true));
        }
        result.relink(ids, result.history);
        if (save(account)) result.restore();
        sessions.put(account, result);
        return result;
    }
    private static TLRPC.User copyUser(TLRPC.User user) {
        SerializedData writer = new SerializedData(); user.serializeToStream(writer);
        SerializedData reader = new SerializedData(writer.toByteArray());
        TLRPC.User copy = TLRPC.User.TLdeserialize(reader, reader.readInt32(true), true); reader.cleanup(); writer.cleanup(); return copy;
    }
    private static TLRPC.Message copyMessage(TLRPC.Message message, int account) {
        try {
            SerializedData writer = new SerializedData(); message.serializeToStream(writer);
            SerializedData reader = new SerializedData(writer.toByteArray());
            TLRPC.Message copy = TLRPC.Message.TLdeserialize(reader, reader.readInt32(true), true);
            copy.readAttachPath(reader, UserConfig.getInstance(account).getClientUserId());
            reader.cleanup(); writer.cleanup(); return copy;
        } catch (Exception error) { FileLog.e(error); return null; }
    }
    public static boolean capture(int account, SendMessagesHelper.SendMessageParams params) {
        if (params == null) return false;
        Session value = sessions.get(account);
        if (value == null) return GhostSimulationPolicy.virtual(params.peer);
        // Fail closed for helper calls while the simulated conversation owns the account.
        AndroidUtilities.runOnUIThread(() -> { if (sessions.get(account) == value) value.send(params); });
        return true;
    }
    public static boolean forward(int account, long peer, ArrayList<MessageObject> messages) {
        if (!active(account)) return GhostSimulationPolicy.virtual(peer) || messages != null && messages.stream().anyMatch(m -> GhostSimulationPolicy.virtual(m.getDialogId()));
        if (messages != null) for (MessageObject message : messages) {
            SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(message);
            params.peer = peer; capture(account, params);
        }
        return true;
    }
    public static boolean loadHistory(int account, long peer, int guid, int count, int loadType, int loadIndex, int maxId) {
        if (!GhostSimulationPolicy.virtual(peer)) return false;
        Session value = session(account, peer);
        ArrayList<MessageObject> messages = value == null ? new ArrayList<>() : new ArrayList<>(value.history);
        messages.sort(Comparator.<MessageObject>comparingInt(m -> m.messageOwner.date).reversed());
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messagesDidLoad,
            peer, count, messages, true, 0, 0, 0, 0, loadType, true, guid, loadIndex, maxId, 0, 0));
        return true;
    }
    public static void typing(int account, long peer) { Session value = session(account, peer); if (value != null) value.idle(); }
    public static void end(Session value) {
        if (!sessions.remove(value.account, value)) return;
        value.closedFrom = android.util.Log.getStackTraceString(new Exception("Local simulation closed"));
        value.cancel(); value.owner = null;
        MessagesController.getInstance(value.account).getUsers().remove(value.peer);
        MessagesController.getInstance(value.account).purgeSimulationDialog(value.peer);
        if (save(value.account)) value.archive();
        else {
            new File(value.root, "history.json").delete();
            File[] files = value.media.listFiles(); if (files != null) for (File file : files) if (file.isFile()) file.delete();
            value.media.delete(); value.root.delete();
        }
    }
    public static final class Session {
        public void remove(ArrayList<Integer> ids) {
            history.removeIf(message -> ids.contains(message.getId()));
            generated.removeIf(message -> ids.contains(message.getId()));
            if (owner != null) owner.didReceivedNotification(NotificationCenter.messagesDeleted, account, ids, 0L, false, true);
        }
        public final int account;
        public final long realPeer, peer;
        public final TLRPC.User user;
        public final ArrayList<MessageObject> history = new ArrayList<>(), generated = new ArrayList<>();
        public GhostSimulationActivity owner;
        public boolean typing, blocked, paused;
        public String closedFrom;
        private final Random random = new Random();
        private final ArrayList<Runnable> callbacks = new ArrayList<>();
        private final ArrayDeque<String> recent = new ArrayDeque<>();
        private final File root, media;
        private int characters;
        private boolean hasSent;
        private Session(int account, long realPeer, TLRPC.User user) {
            this.account = account; this.realPeer = realPeer; this.user = user;
            peer = GhostSimulationPolicy.VIRTUAL_PREFIX | (Math.abs(Utilities.random.nextLong()) & 0x0000ffffffffffffL);
            root = new File(ApplicationLoader.applicationContext.getFilesDir(), "simulations/" + account + "_" + realPeer);
            media = new File(root, "media");
        }
        private void later(Runnable task, int delay) {
            Runnable safe = () -> { if (sessions.get(account) == this && !paused) task.run(); };
            callbacks.add(safe); AndroidUtilities.runOnUIThread(safe, delay);
        }
        public void cancel() { for (Runnable callback : callbacks) AndroidUtilities.cancelRunOnUIThread(callback); callbacks.clear(); typing = false; }
        public void idle() {
            cancel(); if (owner != null) owner.refreshSimulationHeader();
            if (!hasSent || blocked || paused) return;
            later(() -> {
                typing = true; if (owner != null) owner.refreshSimulationHeader();
                later(this::react, GhostSimulationResponses.typingMillis(random));
            }, 5000);
        }
        private void react() {
            typing = false;
            int scenario = GhostSimulationResponses.scenario(random.nextInt(100));
            if (scenario == 0) {
                blocked = true; user.photo = new TLRPC.TL_userProfilePhotoEmpty(); user.status = new TLRPC.TL_userStatusEmpty();
                if (owner != null) owner.refreshSimulationHeader();
                return;
            }
            int count = GhostSimulationResponses.count(characters);
            String[] phrases = characters < 20 ? GhostSimulationResponses.SHORT[scenario]
                : scenario == 3 && rough(account) && random.nextBoolean() ? GhostSimulationResponses.ROUGH : GhostSimulationResponses.PHRASES[scenario];
            characters = 0; reply(phrases, count);
        }
        private void reply(String[] phrases, int remaining) {
            typing = false;
            String selected = phrases[random.nextInt(phrases.length)];
            for (int attempt = 0; attempt < 20 && recent.contains(selected); attempt++) selected = phrases[random.nextInt(phrases.length)];
            recent.add(selected); while (recent.size() > 12) recent.removeFirst();
            TLRPC.Message message = base(false); message.message = selected; append(message, true);
            if (owner != null) owner.refreshSimulationHeader();
            if (remaining > 1) later(() -> {
                typing = true; if (owner != null) owner.refreshSimulationHeader();
                later(() -> reply(phrases, remaining - 1), GhostSimulationResponses.typingMillis(random));
            }, 2000 + random.nextInt(3001));
        }
        private TLRPC.Message base(boolean outgoing) {
            TLRPC.TL_message message = new TLRPC.TL_message(); message.id = nextId.getAndDecrement(); message.local_id = message.id;
            message.dialog_id = peer; message.peer_id = new TLRPC.TL_peerUser(); message.peer_id.user_id = peer;
            message.from_id = new TLRPC.TL_peerUser(); message.from_id.user_id = outgoing ? UserConfig.getInstance(account).getClientUserId() : peer;
            message.flags |= 256; message.out = outgoing; message.date = ConnectionsManager.getInstance(account).getCurrentTime();
            message.message = ""; message.attachPath = ""; message.media = new TLRPC.TL_messageMediaEmpty();
            message.send_state = MessageObject.MESSAGE_SEND_STATE_SENT; return message;
        }
        private void send(SendMessagesHelper.SendMessageParams params) {
            cancel(); hasSent = true;
            TLRPC.Message message = base(true);
            if (params.retryMessageObject != null) {
                TLRPC.Message source = copyMessage(params.retryMessageObject.messageOwner, account);
                if (source != null) {
                    message.message = source.message; message.media = source.media; message.entities = source.entities;
                    message.flags |= source.flags & (128 | 512); message.attachPath = source.attachPath;
                }
            }
            message.message = params.message != null ? params.message : params.caption != null ? params.caption : message.message;
            characters += message.message.isEmpty() ? 120 : message.message.length();
            if (params.entities != null) { message.entities = new ArrayList<>(params.entities); message.flags |= 128; }
            if (params.photo != null) { TLRPC.TL_messageMediaPhoto photo = new TLRPC.TL_messageMediaPhoto(); photo.photo = params.photo; photo.flags |= 1; message.media = photo; }
            else if (params.document != null) { TLRPC.TL_messageMediaDocument document = new TLRPC.TL_messageMediaDocument(); document.document = params.document; document.flags |= 1; message.media = document; }
            else if (params.location != null) message.media = params.location;
            else if (params.poll != null) message.media = params.poll;
            else if (params.todo != null) message.media = params.todo;
            else if (params.user != null) {
                TLRPC.TL_messageMediaContact contact = new TLRPC.TL_messageMediaContact(); contact.user_id = params.user.id;
                contact.phone_number = params.user.phone; contact.first_name = params.user.first_name; contact.last_name = params.user.last_name; contact.vcard = ""; message.media = contact;
            }
            if (!(message.media instanceof TLRPC.TL_messageMediaEmpty)) message.flags |= 512;
            if (params.replyToMsg != null) {
                TLRPC.TL_messageReplyHeader reply = new TLRPC.TL_messageReplyHeader(); reply.reply_to_msg_id = params.replyToMsg.getId(); reply.flags |= 16;
                message.reply_to = reply; message.flags |= 8;
            }
            File input = params.path == null ? null : new File(params.path);
            if (input != null && input.isFile()) {
                Utilities.globalQueue.postRunnable(() -> {
                    File output = null;
                    try {
                        media.mkdirs(); String suffix = input.getName().contains(".") ? input.getName().substring(input.getName().lastIndexOf('.')) : "";
                        output = new File(media, UUID.randomUUID() + suffix); Files.copy(input.toPath(), output.toPath()); message.attachPath = output.getAbsolutePath();
                    } catch (Exception error) { FileLog.e(error); }
                    File copied = output;
                    AndroidUtilities.runOnUIThread(() -> {
                        if (sessions.get(account) != this) { if (copied != null) copied.delete(); return; }
                        append(message, true); idle();
                    });
                });
            } else { append(message, true); idle(); }
        }
        private void append(TLRPC.Message message, boolean remember) {
            MessageObject object = new MessageObject(account, message, true, true); object.mediaExists = true; object.attachPathExists = !message.attachPath.isEmpty();
            if (message.reply_to != null) for (MessageObject previous : history)
                if (previous.getId() == message.reply_to.reply_to_msg_id) { object.replyMessageObject = previous; break; }
            history.add(object); if (remember) generated.add(object);
            if (owner != null) {
                ArrayList<MessageObject> added = new ArrayList<>(); added.add(object);
                owner.didReceivedNotification(NotificationCenter.didReceiveNewMessages, account, peer, added, false, 0);
            }
        }
        private void archive() {
            try {
                root.mkdirs(); JSONArray array = new JSONArray();
                for (MessageObject message : generated) {
                    SerializedData data = new SerializedData(); message.messageOwner.serializeToStream(data);
                    array.put(android.util.Base64.encodeToString(data.toByteArray(), android.util.Base64.NO_WRAP)); data.cleanup();
                }
                // Small metadata only. Finish before reopening can restore the archive.
                Files.write(new File(root, "history.json").toPath(), array.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception error) { FileLog.e(error); }
        }
        private void restore() {
            File archive = new File(root, "history.json"); if (!archive.isFile()) return;
            try {
                JSONArray array = new JSONArray(new String(Files.readAllBytes(archive.toPath()), java.nio.charset.StandardCharsets.UTF_8));
                HashMap<Integer, Integer> ids = new HashMap<>();
                for (int index = 0; index < array.length(); index++) {
                    SerializedData reader = new SerializedData(android.util.Base64.decode(array.getString(index), android.util.Base64.DEFAULT));
                    TLRPC.Message message = TLRPC.Message.TLdeserialize(reader, reader.readInt32(true), true);
                    message.readAttachPath(reader, UserConfig.getInstance(account).getClientUserId()); reader.cleanup();
                    int oldId = message.id;
                    message.id = nextId.getAndDecrement(); message.local_id = message.id; ids.put(oldId, message.id); message.dialog_id = peer; message.peer_id.user_id = peer;
                    if (!message.out) message.from_id.user_id = peer;
                    append(message, true);
                }
                relink(ids, generated);
            } catch (Exception error) { FileLog.e(error); }
        }
        private void relink(HashMap<Integer, Integer> ids, ArrayList<MessageObject> messages) {
            for (MessageObject message : messages) {
                if (message.messageOwner.reply_to == null) continue;
                Integer id = ids.get(message.messageOwner.reply_to.reply_to_msg_id);
                if (id == null) { message.messageOwner.reply_to = null; message.messageOwner.flags &= ~8; continue; }
                message.messageOwner.reply_to.reply_to_msg_id = id;
                message.messageOwner.reply_to.reply_to_peer_id = null;
                message.messageOwner.reply_to.flags &= ~1;
                for (MessageObject previous : messages) if (previous.getId() == id) { message.replyMessageObject = previous; break; }
            }
        }
    }
}
