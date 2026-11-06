package org.ghostgram.messenger.plugins;

import android.app.Instrumentation;
import android.os.Bundle;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessageHistoryStore;
import org.telegram.messenger.MessageTextDiff;
import org.telegram.tgnet.TLRPC;
import com.chaquo.python.Python;
import java.util.Arrays;

/** Runs locally via ADB. Never sends Telegram messages or requests. */
public class GhostDiagnostics extends Instrumentation {
    public static class HookProbe {
        public String value() { return "original"; }
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            MessageTextDiff diff = MessageTextDiff.compare("Привет, друг!", "Привет, друзья! 🔥");
            if (diff.removed.isEmpty() || diff.added.isEmpty()) throw new AssertionError("Diff missing changes");
            MessageTextDiff digitDiff = MessageTextDiff.compare("123456", "1234567");
            if (!digitDiff.removed.isEmpty() || digitDiff.added.size() != 1
                    || digitDiff.added.get(0).start != 6 || digitDiff.added.get(0).end != 7) {
                throw new AssertionError("Digit diff must highlight only the appended 7");
            }
            if (!MessageTextDiff.compare("🙂 unchanged", "🙂 unchanged").removed.isEmpty()) throw new AssertionError("Equal text changed");
            MessageHistoryStore store = MessageHistoryStore.getInstance();
            long dialog = Long.MAX_VALUE - 111;
            int message = -2146000001;
            TLRPC.TL_message original = new TLRPC.TL_message();
            original.id = message;
            original.date = 100;
            original.message = "123456";
            TLRPC.TL_message edited = new TLRPC.TL_message();
            edited.id = message;
            edited.date = 100;
            edited.edit_date = 101;
            edited.message = "1234567";
            try {
                store.saveRevision(0, dialog, original, edited);
                if (store.getRevisions(0, dialog, message).size() != 1
                        || !"123456".equals(store.getRevisions(0, dialog, message).get(0).text)) {
                    throw new AssertionError("Revision was not persisted");
                }
                store.markDeleted(0, dialog, Arrays.asList(message));
                if (!store.isDeleted(0, dialog, message)) throw new AssertionError("Remote marker missing");
                store.forgetMessages(0, dialog, Arrays.asList(message));
                if (store.isDeleted(0, dialog, message)) throw new AssertionError("Local deletion retained");
                store.markDeleted(0, 0, Arrays.asList(message));
                if (store.isDeleted(0, dialog, message)) throw new AssertionError("Late remote deletion resurrected message");
            } finally {
                String[] ids = {"0", String.valueOf(message)};
                store.getWritableDatabase().delete("deleted_messages", "account=? AND message_id=?", ids);
                store.getWritableDatabase().delete("local_deletions", "account=? AND message_id=?", ids);
                store.getWritableDatabase().delete("message_revisions", "account=? AND message_id=?", ids);
            }
            runOnMainSync(() -> {
                try {
                    PluginHost.initialize(ApplicationLoader.applicationContext);
                    String report = Python.getInstance().getModule("ghost_regression_checks").callAttr("run", ApplicationLoader.applicationContext).toString();
                    result.putString("plugin_checks", report);
                    result.putString("message_tools", Python.getInstance().getModule("ghost_message_tools_checks").callAttr("run", ApplicationLoader.applicationContext).toString());
                } catch (Throwable error) {
                    result.putString("plugin_error", android.util.Log.getStackTraceString(error));
                }
            });
            if (result.containsKey("plugin_error")) throw new AssertionError(result.getString("plugin_error"));
            result.putString("result", "PASS: diff, deletion markers, own deletion and late updates");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("result", android.util.Log.getStackTraceString(error));
            finish(0, result);
        }
    }
}
