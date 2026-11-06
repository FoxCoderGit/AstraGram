package org.telegram.messenger;

import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

/** Parser and presentation for the bundled local registration-date estimates. */
public final class GhostRegistrationDate {
    private GhostRegistrationDate() { }
    public static final class Estimate {
        public final String date, flag;
        public final boolean local;
        public final String range;
        final long timestamp;
        private Estimate(String date, String flag, long timestamp) { this(date, flag, timestamp, false, null); }
        Estimate(String date, String flag, long timestamp, boolean local, String range) {
            this.date = date; this.flag = flag; this.timestamp = timestamp; this.local = local; this.range = range;
        }
        public String text() {
            if ("NEAREST".equals(flag)) return LocaleController.formatString(R.string.GhostRegistrationDateNearest, date);
            int label = "LT".equals(flag) ? R.string.GhostRegistrationDateBefore
                : "ET".equals(flag) ? R.string.GhostRegistrationDateAfter : R.string.GhostRegistrationDateApprox;
            String text = LocaleController.formatString(label, date);
            return local ? LocaleController.formatString(R.string.GhostRegistrationDateLocal, text) : text;
        }
    }
    static Estimate parse(String text) {
        try {
            JSONObject json = new JSONObject(text);
            String date = json.getString("date"), flag = json.getString("flag");
            if (!("EXACT".equals(flag) || "INTERPOLATED".equals(flag) || "LT".equals(flag) || "ET".equals(flag))) return null;
            if (!date.matches("[0-9]{2}\\.[0-9]{2}\\.[0-9]{4}")) return null;
            SimpleDateFormat format = new SimpleDateFormat("dd.MM.yyyy", Locale.US);
            format.setLenient(false); format.setTimeZone(TimeZone.getTimeZone("UTC"));
            long timestamp = format.parse(date).getTime();
            if (timestamp < 1356998400000L || timestamp > System.currentTimeMillis()) return null;
            return new Estimate(date, flag, timestamp);
        } catch (Exception ignored) { return null; }
    }
}
