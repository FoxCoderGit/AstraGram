package org.telegram.messenger;

import android.app.Instrumentation;
import android.os.Bundle;
import org.telegram.tgnet.TLRPC;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

/** Synthetic isolated preference keys only; does not send RPCs or modify real collections. */
public class GhostLocalLimitsDiagnostics extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private static TLRPC.Document document(long id) {
        TLRPC.TL_document doc = new TLRPC.TL_document();
        doc.id = id; doc.access_hash = id; doc.file_reference = new byte[]{1}; doc.date = 100;
        doc.mime_type = "image/webp"; doc.size = 0; doc.dc_id = 1;
        return doc;
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            String unique = "fixture_" + System.nanoTime();
            java.util.List<String> sources = java.util.Arrays.asList(unique + "_first", unique + "_second");
            java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            GhostTextService.TextTransform invalidBatch = (text, correct) -> { calls.incrementAndGet(); return text.contains("<gg_") ? "Missing markers" : "Corrected " + text; };
            java.util.List<String> recovered = GhostTextService.transformBatch(sources, true, invalidBatch);
            if (!recovered.equals(java.util.Arrays.asList("Corrected " + sources.get(0), "Corrected " + sources.get(1))) || calls.get() != 3) throw new AssertionError("Invalid batch recovery");
            GhostTextService.transformBatch(sources, true, invalidBatch);
            if (calls.get() != 3) throw new AssertionError("Recovered results were not cached");
            calls.set(0);
            try {
                GhostTextService.transformBatch(java.util.Arrays.asList(unique + "_429a", unique + "_429b"), false, (text, correct) -> { calls.incrementAndGet(); throw new java.io.IOException("HTTP 429 fixture"); });
                throw new AssertionError("HTTP failure swallowed");
            } catch (java.io.IOException expected) { if (calls.get() != 1) throw new AssertionError("HTTP 429 retried"); }
            if (!GhostTextService.unpackBatch("```xml\n&lt;GG_FIXTURE_0&gt;First&lt;/GG_FIXTURE_0&gt;\n<GG_fixture_1 >Second</GG_fixture_1 >\n```", "fixture", 2).equals(java.util.Arrays.asList("First", "Second"))) throw new AssertionError("Marker normalization");
            result.putString("filero_recovery", "PASS: missing markers recovered, results cached, markup normalized; HTTP 429 not retried");
        } catch (Throwable error) { result.putString("error", android.util.Log.getStackTraceString(error)); finish(0, result); return; }
        runOnMainSync(() -> {
            try {
                ApplicationLoader.postInitApplication();
                int account = UserConfig.selectedAccount;
                try {
                    for (String kind : new String[]{"fixture_stickers", "fixture_gifs", "fixture_recent"}) {
                        int limit = kind.endsWith("gifs") ? GhostLocalLimits.gifLimit(account) : kind.endsWith("recent") ? GhostLocalLimits.recentLimit(account) : GhostLocalLimits.stickerLimit(account);
                        ArrayList<TLRPC.Document> input = new ArrayList<>();
                        for (int i = 0; i < limit + 3; i++) input.add(document(2145000000L + i));
                        ArrayList<TLRPC.Document> stored = GhostLocalLimits.merge(account, kind, input);
                        if (stored.size() != limit) throw new AssertionError("Collection cap: " + kind);
                        ArrayList<TLRPC.Document> shortServer = new ArrayList<>(input.subList(0, 3));
                        stored = GhostLocalLimits.merge(account, kind, shortServer);
                        if (stored.size() != limit) throw new AssertionError("Local items lost on server refresh");
                        TLRPC.Document removed = input.get(0);
                        GhostLocalLimits.change(account, kind, stored, removed, true);
                        stored = GhostLocalLimits.merge(account, kind, shortServer);
                        if (stored.stream().anyMatch(d -> d.id == removed.id)) throw new AssertionError("Removed item resurrected");
                        stored = GhostLocalLimits.change(account, kind, stored, removed, false);
                        if (stored.get(0).id != removed.id || stored.size() != limit) throw new AssertionError("Re-add/order");
                        stored = GhostLocalLimits.change(account, kind, stored, removed, false);
                        if (stored.size() != limit) throw new AssertionError("Duplicate added");
                    }
                    MessagesController c = MessagesController.getInstance(account);
                    int serverLimit = UserConfig.getInstance(account).isPremium() ? c.maxPinnedDialogsCountPremium : c.maxPinnedDialogsCountDefault;
                    ArrayList<TLRPC.Dialog> dialogs = new ArrayList<>();
                    for (int i = 0; i < serverLimit; i++) {
                        TLRPC.TL_dialog d = new TLRPC.TL_dialog(); d.id = 2144000000L + i; d.pinned = true; d.pinnedNum = i + 1; dialogs.add(d);
                    }
                    TLRPC.TL_dialog extra = new TLRPC.TL_dialog(); extra.id = 2144000999L; dialogs.add(extra);
                    if (!GhostLocalLimits.changePin(account, extra, true, dialogs)) throw new AssertionError("Local overflow pin not created");
                    extra.pinned = false; extra.pinnedNum = 0;
                    GhostLocalLimits.applyPins(account, dialogs);
                    if (!extra.pinned || !GhostLocalLimits.isLocalPin(account, extra)) throw new AssertionError("Pin lost after refresh");
                    GhostLocalLimits.changePin(account, extra, false, dialogs);
                    GhostLocalLimits.applyPins(account, dialogs);
                    if (extra.pinned || GhostLocalLimits.isLocalPin(account, extra)) throw new AssertionError("Unpin failed");
                    result.putString("collections", "PASS: caps, serialization/reload, server merge, deletion, re-add and deduplication");
                    result.putString("pins", "PASS: extra local pin, refresh and unpin; no server requests");
                    result.putInt("stickers_limit", GhostLocalLimits.stickerLimit(account));
                    result.putInt("gifs_limit", GhostLocalLimits.gifLimit(account));
                    result.putInt("pins_limit", GhostLocalLimits.pinLimit(account, 0));
                    result.putInt("recent_stickers_limit", GhostLocalLimits.recentLimit(account));
                } finally {
                    android.content.SharedPreferences.Editor edit = GhostLocalLimits.preferences(account).edit();
                    for (String kind : new String[]{"fixture_stickers", "fixture_gifs", "fixture_recent"}) edit.remove(kind).remove(kind + "_removed");
                    edit.remove("pin_0_2144000999").apply();
                }
            } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) { result.putString("error", android.util.Log.getStackTraceString(failure.get())); finish(0, result); }
        else finish(-1, result);
    }
}
