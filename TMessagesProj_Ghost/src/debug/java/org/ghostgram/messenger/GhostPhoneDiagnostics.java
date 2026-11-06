package org.ghostgram.messenger;

import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.GhostPhoneInfo;
import org.telegram.messenger.DialogObject;
import org.telegram.tgnet.TLRPC;

/** Offline synthetic examples only. Does not query real contacts or Telegram. */
public class GhostPhoneDiagnostics extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            long started = System.nanoTime();
            JSONObject ru = new JSONObject(PhoneInfoHost.lookup(ApplicationLoader.applicationContext, "+79161234567", ""));
            if (!ru.optBoolean("registry") || !ru.optString("date").equals("2026-10-01")) throw new AssertionError("Russian database lookup");
            String formatted = GhostPhoneInfo.describe(ru);
            if (!formatted.contains(ru.getString("operator")) || !formatted.contains(ru.getString("region"))) throw new AssertionError("Missing display fields");
            JSONObject domestic = new JSONObject(PhoneInfoHost.lookup(ApplicationLoader.applicationContext, "8 (916) 123-45-67", ""));
            if (!domestic.getString("number").equals(ru.getString("number"))) throw new AssertionError("Domestic number parsing");
            JSONObject gb = new JSONObject(PhoneInfoHost.lookup(ApplicationLoader.applicationContext, "+442083661177", ""));
            if (!gb.optString("region").equals("London") || gb.optBoolean("registry")) throw new AssertionError("Foreign lookup");
            JSONObject kz = new JSONObject(PhoneInfoHost.lookup(ApplicationLoader.applicationContext, "+77011234567", ""));
            if (kz.optBoolean("registry") || kz.optString("country").equals(ru.getString("country"))) throw new AssertionError("Kazakhstan treated as Russia");
            JSONObject anonymous = new JSONObject(PhoneInfoHost.lookup(ApplicationLoader.applicationContext, "+88812345678", ""));
            if (!anonymous.optBoolean("anonymous") || anonymous.has("operator")) throw new AssertionError("Anonymous phone");
            TLRPC.TL_emojiStatus emoji = new TLRPC.TL_emojiStatus();
            emoji.flags = 1; emoji.until = 1800000000;
            if (DialogObject.getEmojiStatusUntil(emoji) != 1800000000) throw new AssertionError("Emoji expiry");
            emoji.flags = 0;
            if (DialogObject.getEmojiStatusUntil(emoji) != 0) throw new AssertionError("Permanent emoji expiry");
            result.putString("result", "PASS: bundled Russian ranges, local parsing, foreign metadata, shared +7, anonymous phone, native display, emoji expiration");
            result.putLong("lookup_ms", (System.nanoTime() - started) / 1000000L);
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("result", android.util.Log.getStackTraceString(error));
            finish(0, result);
        }
    }
}
