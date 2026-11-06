package org.ghostgram.messenger.plugins;

import android.app.Instrumentation;
import android.os.Bundle;
import org.telegram.messenger.*;
import org.telegram.tgnet.*;
import java.util.Arrays;

/** Uses synthetic messages only; never authenticates or sends chat messages. */
public class GhostTextDiagnostics extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle value) { args = value; super.onCreate(value); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            runOnMainSync(() -> {
                ApplicationLoader.postInitApplication();
                org.telegram.ui.ActionBar.Theme.createChatResources(getTargetContext(), false);
                if (UserConfig.MAX_PHONE_ACCOUNTS != 10 || UserConfig.MAX_ACCOUNT_COUNT != 64 || UserConfig.BOT_ACCOUNT != 10) throw new AssertionError("Account layout");
                for (int account : new int[]{0, 5, 9, 10, 63}) {
                    ConnectionsManager.getInstance(account);
                    if (ConnectionsManager.native_getAccountIndex(account) != account) throw new AssertionError("Native account aliases " + account);
                }
                TLRPC.TL_message owner = new TLRPC.TL_message();
                owner.id = 2146000123; owner.date = 100; owner.message = "Original fixture";
                owner.peer_id = new TLRPC.TL_peerUser(); owner.peer_id.user_id = Long.MAX_VALUE - 324;
                owner.dialog_id = owner.peer_id.user_id;
                owner.from_id = new TLRPC.TL_peerUser(); owner.from_id.user_id = owner.peer_id.user_id;
                owner.media = new TLRPC.TL_messageMediaEmpty();
                MessageObject message = new MessageObject(0, owner, true, false);
                SerializedData before = new SerializedData(); owner.serializeToStream(before);
                try {
                    LocalMessageEdits.set(message, "Edited fixture");
                    if (!"Original fixture".equals(owner.message) || !"Edited fixture".equals(message.messageText.toString())) throw new AssertionError("Edit leaked into TL or did not render");
                    MessageObject reload = new MessageObject(0, owner, true, false);
                    if (!"Edited fixture".equals(reload.messageText.toString())) throw new AssertionError("Edit lost on reload");
                    SerializedData after = new SerializedData(); owner.serializeToStream(after);
                    if (!Arrays.equals(before.toByteArray(), after.toByteArray())) throw new AssertionError("Wire message mutated");
                    LocalMessageEdits.set(message, null);
                    if (!"Original fixture".equals(message.messageText.toString())) throw new AssertionError("Undo failed");
                } finally { LocalMessageEdits.set(message, null); }
                PerChatQuickReaction.set(0, owner.dialog_id, "🔥");
                try {
                    if (!"🔥".equals(PerChatQuickReaction.get(0, owner.dialog_id, "👍"))) throw new AssertionError("Per-chat reaction lost");
                    PerChatQuickReaction.set(0, owner.dialog_id, null);
                    if (!"👍".equals(PerChatQuickReaction.get(0, owner.dialog_id, "👍"))) throw new AssertionError("Default not restored");
                } finally { PerChatQuickReaction.set(0, owner.dialog_id, null); }
                GhostBotAuthorization bot = new GhostBotAuthorization(); bot.token = "123:fixture";
                SerializedData wire = new SerializedData(); bot.serializeToStream(wire);
                SerializedData read = new SerializedData(wire.toByteArray());
                if (read.readInt32(true) != 0x67a3ff2c || read.readInt32(true) != 0 || read.readInt32(true) != BuildVars.APP_ID
                    || !read.readString(true).equals(BuildVars.APP_HASH) || !read.readString(true).equals("123:fixture")) throw new AssertionError("Bot authorization wire format");
            });
            result.putString("accounts", "PASS: ten phone slots and independently allocated bot slots");
            result.putString("local_edits", "PASS: display, reload and undo; serialized original unchanged");
            result.putString("reactions", "PASS: per-chat override and reset");
            result.putString("bot", "PASS: official authorization serialization; no login request sent");
            java.util.List<String> split = GhostTextService.unpackBatch("<gg_fixture_0>First</gg_fixture_0>\n<gg_fixture_1>Second</gg_fixture_1>", "fixture", 2);
            if (!split.equals(java.util.Arrays.asList("First", "Second"))) throw new AssertionError("Batch mapping");
            split = GhostTextService.unpackBatch("<gg_fixture_0>First\n<gg_fixture_1>Second", "fixture", 2);
            if (!split.equals(java.util.Arrays.asList("First", "Second"))) throw new AssertionError("Corrector batch mapping");
            boolean rejected = false;
            try { GhostTextService.unpackBatch("<gg_fixture_1>Second</gg_fixture_1><gg_fixture_0>First</gg_fixture_0>", "fixture", 2); } catch (Exception expected) { rejected = true; }
            if (!rejected) throw new AssertionError("Reordered batch was not rejected");
            result.putString("batch_mapping", "PASS: ordered messages restored; malformed boundaries rejected");
            result.putString("filero_connection", SharedConfig.isProxyEnabled() ? SharedConfig.currentProxy.settings.getType().name() : "DIRECT/SYSTEM VPN");
            if (args != null && "true".equals(args.getString("network"))) {
                java.util.List<String> translated = GhostTextService.transformBatch(java.util.Arrays.asList("Good morning!", "How are you?"), false);
                java.util.List<String> corrected = GhostTextService.transformBatch(java.util.Arrays.asList("Превет как дела", "Я севодня приду"), true);
                if (translated.size() != 2 || corrected.size() != 2 || corrected.get(0).contains("Превет")) throw new AssertionError("Filero batch result");
                result.putString("filero", "PASS: four messages in two requests, translation and correction with preserved boundaries");
            }
            finish(-1, result);
        } catch (Throwable error) { result.putString("error", android.util.Log.getStackTraceString(error)); finish(0, result); }
    }
}
