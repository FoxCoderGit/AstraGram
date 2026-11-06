package org.telegram.messenger;

import android.app.Instrumentation;
import android.os.Bundle;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.tgnet.TLRPC;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Removes reserved local peers only; reports counts, never real message content or IDs. */
public class GhostSimulationCleanupDiagnostics extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            runOnMainSync(ApplicationLoader::postInitApplication);
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                final int index = account; MessagesController controller = MessagesController.getInstance(account);
                AtomicReference<Throwable> problem = new AtomicReference<>();
                runOnMainSync(() -> {
                    try {
                        TLRPC.TL_dialog local = new TLRPC.TL_dialog(); local.id = GhostSimulationPolicy.VIRTUAL_PREFIX | 444;
                        TLRPC.TL_dialog real = new TLRPC.TL_dialog(); real.id = 2146000555L;
                        TLRPC.Dialog prior = controller.dialogs_dict.get(real.id);
                        if (prior == null) controller.dialogs_dict.put(real.id, real);
                        controller.dialogs_dict.put(local.id, local); controller.allDialogs.add(local);
                        controller.purgeSimulationDialogs();
                        if (controller.dialogs_dict.get(local.id) != null || controller.allDialogs.contains(local)) throw new AssertionError("Virtual dialog remains");
                        if (controller.dialogs_dict.get(real.id) != (prior == null ? real : prior)) throw new AssertionError("Real dialog changed");
                        if (prior == null) controller.dialogs_dict.remove(real.id);
                    } catch (Throwable error) { problem.set(error); }
                });
                if (problem.get() != null) throw new Exception("Local-only cleanup", problem.get());
                MessagesStorage storage = MessagesStorage.getInstance(account); storage.purgeSimulationDialogs();
                CountDownLatch finished = new CountDownLatch(1);
                // The first barrier allows the purge to enqueue its per-dialog deletions.
                storage.getStorageQueue().postRunnable(() -> storage.getStorageQueue().postRunnable(() -> {
                    SQLiteCursor cursor = null;
                    try {
                        long first = GhostSimulationPolicy.VIRTUAL_PREFIX, last = first | 0x0000ffffffffffffL;
                        cursor = storage.getDatabase().queryFinalized("SELECT COUNT(*) FROM dialogs WHERE did >= " + first + " AND did <= " + last);
                        if (!cursor.next() || cursor.intValue(0) != 0) throw new AssertionError("Reserved dialogs remain in storage: account " + index);
                    } catch (Throwable error) { problem.set(error); }
                    finally { if (cursor != null) cursor.dispose(); finished.countDown(); }
                }));
                if (!finished.await(8, TimeUnit.SECONDS)) throw new AssertionError("Cleanup queue timeout");
                if (problem.get() != null) throw new Exception("Database cleanup", problem.get());
            }
            result.putString("cleanup", "PASS: virtual dialogs removed from every account and database; ordinary fixture preserved; no server deletion requests");
            finish(-1, result);
        } catch (Throwable error) { result.putString("error", android.util.Log.getStackTraceString(error)); finish(0, result); }
    }
}
