package org.telegram.messenger;

import android.app.Instrumentation;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises transient server failures without sending requests or waiting in real time. */
public class GhostFileroDiagnostics extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            for (int status : new int[]{502, 503, 504}) {
                AtomicInteger calls = new AtomicInteger();
                ArrayList<Long> waits = new ArrayList<>();
                String recovered = GhostTextService.withRetries(() -> {
                    if (calls.incrementAndGet() == 1) throw new GhostTextService.HttpStatusException(status, "Synthetic gateway failure");
                    return "Recovered";
                }, waits::add);
                if (!"Recovered".equals(recovered) || calls.get() != 2 || !waits.equals(Arrays.asList(2000L))) throw new AssertionError("Recovery: " + status);
                calls.set(0); waits.clear();
                try {
                    GhostTextService.withRetries(() -> { calls.incrementAndGet(); throw new GhostTextService.HttpStatusException(status, "Persistent synthetic failure"); }, waits::add);
                    throw new AssertionError("Persistent failure swallowed");
                } catch (GhostTextService.HttpStatusException expected) {
                    if (calls.get() != 3 || !waits.equals(Arrays.asList(2000L, 5000L))) throw new AssertionError("Unbounded retry: " + status);
                }
            }
            for (int status : new int[]{400, 401, 403, 429, 500}) {
                AtomicInteger calls = new AtomicInteger();
                try {
                    GhostTextService.withRetries(() -> { calls.incrementAndGet(); throw new GhostTextService.HttpStatusException(status, "Non-retryable fixture"); }, delay -> { throw new AssertionError("Non-retryable error waited"); });
                    throw new AssertionError("Error swallowed");
                } catch (GhostTextService.HttpStatusException expected) { if (calls.get() != 1) throw new AssertionError("Retried: " + status); }
            }
            String unique = "filero_retry_fixture_" + System.nanoTime();
            List<String> sources = Arrays.asList(unique + "a", unique + "b");
            AtomicInteger batchCalls = new AtomicInteger();
            GhostTextService.TextTransform transport = (text, correct) -> GhostTextService.withRetries(() -> {
                if (batchCalls.incrementAndGet() == 1) throw new GhostTextService.HttpStatusException(502, "Batch gateway fixture");
                return text;
            }, delay -> {});
            if (!GhostTextService.transformBatch(sources, false, transport).equals(sources) || batchCalls.get() != 2) throw new AssertionError("Batch retry lost boundaries");
            GhostTextService.transformBatch(sources, false, transport);
            if (batchCalls.get() != 2) throw new AssertionError("Recovered batch cache missed");
            result.putString("transient_errors", "PASS: 502/503/504 recover; at most three attempts, pauses of 2 and 5 seconds");
            result.putString("other_errors", "PASS: 429 and other statuses do not retry");
            result.putString("batch_cache", "PASS: batch mapping and cache survive retry; no extra requests after recovery");
            finish(-1, result);
        } catch (Throwable error) { result.putString("error", android.util.Log.getStackTraceString(error)); finish(0, result); }
    }
}
