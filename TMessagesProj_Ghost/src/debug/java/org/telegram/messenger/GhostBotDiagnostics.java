package org.telegram.messenger;

import android.app.Instrumentation;
import android.os.Bundle;
import org.telegram.tgnet.*;
import java.util.Arrays;
import java.lang.reflect.Method;

/** Synthetic wire data and isolated preference namespaces; never sends bot messages. */
public class GhostBotDiagnostics extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        final long fixtureBot = 2146000999L;
        try {
            runOnMainSync(ApplicationLoader::postInitApplication);
            // Construct the reported NETWORK update independently of its serializer.
            for (int flags : new int[]{0, 1, 2, 3}) {
                SerializedData wire = new SerializedData();
                wire.writeInt32(0xb9cfc48d); wire.writeInt32(flags);
                wire.writeInt64(1234567890123L); wire.writeInt64(2146000001L);
                wire.writeInt32(0x59511722); wire.writeInt64(2146000001L); // peerUser
                wire.writeInt32(7); wire.writeInt64(8765432101234L);
                if ((flags & 1) != 0) wire.writeByteArray(new byte[]{1, 2, 3});
                if ((flags & 2) != 0) wire.writeString("fixture_game");
                wire.writeInt32(0x1234abcd);
                SerializedData read = new SerializedData(wire.toByteArray());
                GhostBotUpdates.UpdateBotCallbackQuery update = (GhostBotUpdates.UpdateBotCallbackQuery)
                    TLRPC.Update.TLdeserialize(read, read.readInt32(true), true);
                check(update.user_id == 2146000001L && update.msg_id == 7 && update.query_id == 1234567890123L, "Callback values");
                check((flags & 1) == 0 || Arrays.equals(update.data, new byte[]{1, 2, 3}), "Callback bytes");
                check((flags & 2) == 0 || "fixture_game".equals(update.game_short_name), "Callback game");
                check(read.readInt32(true) == 0x1234abcd, "Callback consumed neighboring update");
            }
            GhostBotUpdates.UpdateBotCallbackQuery callback = new GhostBotUpdates.UpdateBotCallbackQuery();
            callback.peer = new TLRPC.TL_peerChat(); callback.peer.chat_id = 2146000002L;
            GhostBotUpdates.UpdateBotStopped stopped = new GhostBotUpdates.UpdateBotStopped();
            stopped.user_id = 2146000001L; stopped.date = 200; stopped.stopped = true; stopped.qts = 5;
            TLRPC.TL_updates batch = new TLRPC.TL_updates();
            batch.updates.add(callback); batch.updates.add(stopped); batch.date = 201; batch.seq = 17;
            SerializedData wire = new SerializedData(); batch.serializeToStream(wire);
            SerializedData read = new SerializedData(wire.toByteArray());
            TLRPC.Updates parsed = TLRPC.Updates.TLdeserialize(read, read.readInt32(true), true);
            check(parsed.updates.size() == 2 && parsed.seq == 17 && parsed.date == 201, "NETWORK update vector truncated");
            check(((GhostBotUpdates.UpdateBotStopped) parsed.updates.get(1)).stopped, "Block update bool");
            Method type = MessagesController.class.getDeclaredMethod("getUpdateType", TLRPC.Update.class);
            Method qts = MessagesController.class.getDeclaredMethod("getUpdateQts", TLRPC.Update.class);
            type.setAccessible(true); qts.setAccessible(true);
            check((int)type.invoke(MessagesController.getInstance(0), stopped) == 1 && (int)qts.invoke(null, stopped) == 5, "Bot qts queue");
            for (String flag : new String[]{"EXACT", "INTERPOLATED", "LT", "ET"}) {
                GhostRegistrationDate.Estimate estimate = GhostRegistrationDate.parse("{\"flag\":\"" + flag + "\",\"date\":\"18.09.2024\"}");
                check(estimate != null && estimate.flag.equals(flag) && estimate.date.equals("18.09.2024"), "Registration date flag");
            }
            for (String invalid : new String[]{"failed", "{}", "{\"flag\":\"UNKNOWN\",\"date\":\"18.09.2024\"}", "{\"flag\":\"EXACT\",\"date\":\"31.02.2024\"}", "{\"flag\":\"EXACT\",\"date\":\"01.01.2099\"}", "{\"flag\":\"EXACT\",\"date\":\"18x09x2024\"}"}) {
                check(GhostRegistrationDate.parse(invalid) == null, "Invalid registration date accepted");
            }
            result.putString("registration_dates", "PASS: four AyuGram date flags; malformed, impossible and future dates rejected; no external bot query sent");
            java.util.TreeMap<Long, GhostRegistrationDate.Estimate> observations = new java.util.TreeMap<>();
            GhostRegistrationDate.Estimate earlier = GhostRegistrationDate.parse("{\"flag\":\"EXACT\",\"date\":\"01.09.2024\"}");
            GhostRegistrationDate.Estimate later = GhostRegistrationDate.parse("{\"flag\":\"INTERPOLATED\",\"date\":\"21.09.2024\"}");
            observations.put(1000L, earlier); observations.put(2000L, later);
            GhostRegistrationDate.Estimate middle = GhostRegistrationDateStore.estimate(1500, observations);
            check(middle != null && middle.local && middle.date.equals("11.09.2024") && middle.range != null, "Local midpoint estimate");
            check(GhostRegistrationDateStore.estimate(999, observations).flag.equals("NEAREST") && GhostRegistrationDateStore.estimate(999, observations).date.equals(earlier.date)
                && GhostRegistrationDateStore.estimate(2001, observations).date.equals(later.date), "Outside-range nearest estimate");
            observations.put(1500L, GhostRegistrationDate.parse("{\"flag\":\"LT\",\"date\":\"01.09.2023\"}"));
            check(GhostRegistrationDateStore.estimate(1400, observations).date.equals("09.09.2024"), "LT treated as dated anchor");
            observations.remove(1500L); observations.put(2000L, earlier); observations.put(1000L, later);
            check(GhostRegistrationDateStore.estimate(1500, observations).flag.equals("NEAREST"), "Contradictory observations interpolated instead of nearest fallback");
            observations.clear(); observations.put(1000L, earlier); observations.put(600000001L, later);
            check(GhostRegistrationDateStore.estimate(1500, observations).flag.equals("NEAREST"), "Wide ID gap interpolated instead of nearest fallback");
            check(GhostRegistrationDateStore.estimate(0, observations) == null && GhostRegistrationDateStore.estimate(123, new java.util.TreeMap<>()) == null, "Invalid or empty data fabricated");
            java.util.TreeMap<Long, GhostRegistrationDate.Estimate> bundled = new java.util.TreeMap<>();
            try (java.io.InputStream input = ApplicationLoader.applicationContext.getAssets().open("ghost_registration_dates.json")) {
                GhostRegistrationDateStore.loadSeeds(input, bundled);
            }
            check(bundled.size() == 152 && GhostRegistrationDateStore.count() == 152, "Full offline database missing from APK");
            check(GhostRegistrationDateStore.estimate(9000000000L).flag.equals("ET"), "One-sided bound lost");
            check(GhostRegistrationDateStore.estimate(6000000000L) != null, "Covered offline range unavailable");
            check(GhostRegistrationDateStore.estimate(13000000000L).flag.equals("NEAREST"), "Unknown ID nearest estimate missing");
            result.putString("local_date_database", "PASS: bundled 152 observations load without preferences; offline known and unknown IDs, bounds, interpolation and invalid ranges");
            result.putString("network_updates", "PASS: b9cfc48d all optional flags; complete NETWORK vector; bot block update and qts queue");

            GhostBotRecipients.preferences(0, fixtureBot).edit().clear().commit();
            long user = 2146000001L, group = -2146000002L;
            GhostBotRecipients.record(0, fixtureBot, user, false, 100);
            GhostBotRecipients.record(0, fixtureBot, group, false, 110);
            check(GhostBotRecipients.known(0, fixtureBot).equals(Arrays.asList(group, user)), "Recipient ordering");
            GhostBotRecipients.record(0, fixtureBot, user, true, 200);
            GhostBotRecipients.record(0, fixtureBot, user, false, 150);
            check(GhostBotRecipients.known(0, fixtureBot).equals(Arrays.asList(group)), "Blocked or stale user visible");
            GhostBotRecipients.record(0, fixtureBot, user, false, 210);
            GhostBotRecipients.record(0, fixtureBot, fixtureBot, false, 220);
            GhostBotRecipients.record(0, fixtureBot, 0, false, 220);
            check(GhostBotRecipients.known(0, fixtureBot).equals(Arrays.asList(user, group)), "Unblock, dedup or self filtering");
            TLRPC.TL_message message = new TLRPC.TL_message();
            message.peer_id = new TLRPC.TL_peerUser(); message.peer_id.user_id = fixtureBot;
            message.from_id = new TLRPC.TL_peerUser(); message.from_id.user_id = user;
            check(GhostBotRecipients.messageDialog(fixtureBot, message) == user, "Incoming private recipient");
            message.peer_id = callback.peer;
            check(GhostBotRecipients.messageDialog(fixtureBot, message) == group, "Group recipient misidentified as user");
            check(!GhostBotRecipients.isBot(0) || UserConfig.getInstance(0).getClientUserId() != fixtureBot, "Fixture namespace collision");
            check(GhostBotRecipients.preferences(0, fixtureBot).edit().commit(), "Recipient preference persistence");
            result.putString("recipients", "PASS: private and group IDs, ordering, block/unblock, stale updates, duplicate filtering and stored preferences");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("error", android.util.Log.getStackTraceString(error)); finish(0, result);
        } finally { GhostBotRecipients.preferences(0, fixtureBot).edit().clear().commit(); }
    }
}
