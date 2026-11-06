package org.telegram.messenger;

import org.json.JSONObject;
import org.json.JSONArray;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TimeZone;
import java.util.TreeMap;

/** Read-only bundled ID/date estimates; no network access or mutable training state. */
public final class GhostRegistrationDateStore {
    private static TreeMap<Long, GhostRegistrationDate.Estimate> samples;
    private GhostRegistrationDateStore() { }
    private static synchronized TreeMap<Long, GhostRegistrationDate.Estimate> samples() {
        if (samples == null) {
            samples = new TreeMap<>();
            try (InputStream input = ApplicationLoader.applicationContext.getAssets().open("ghost_registration_dates.json")) {
                loadSeeds(input, samples);
            } catch (Exception error) { FileLog.e(error); }
        }
        return samples;
    }
    static void loadSeeds(InputStream input, TreeMap<Long, GhostRegistrationDate.Estimate> destination) throws Exception {
        InputStreamReader reader = new InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder content = new StringBuilder();
        char[] buffer = new char[4096];
        for (int size; (size = reader.read(buffer)) != -1;) content.append(buffer, 0, size);
        JSONArray records = new JSONArray(content.toString());
        for (int i = 0; i < records.length(); i++) {
            try {
                JSONObject record = records.getJSONObject(i);
                long id = record.getLong("id");
                GhostRegistrationDate.Estimate date = GhostRegistrationDate.parse(record.toString());
                if (id > 0 && date != null && !destination.containsKey(id)) destination.put(id, date);
            } catch (Exception ignored) { }
        }
    }
    public static synchronized int count() { return samples().size(); }
    public static synchronized int ranges() {
        TreeMap<Long, GhostRegistrationDate.Estimate> anchors = anchors(samples());
        int result = 0;
        Map.Entry<Long, GhostRegistrationDate.Estimate> previous = null;
        for (Map.Entry<Long, GhostRegistrationDate.Estimate> current : anchors.entrySet()) {
            if (previous != null && usable(previous, current)) result++;
            previous = current;
        }
        return result;
    }
    public static synchronized GhostRegistrationDate.Estimate estimate(long user) { return estimate(user, samples()); }
    static GhostRegistrationDate.Estimate estimate(long user, NavigableMap<Long, GhostRegistrationDate.Estimate> observations) {
        if (user <= 0) return null;
        GhostRegistrationDate.Estimate exactSample = observations.get(user);
        if (exactSample != null) return new GhostRegistrationDate.Estimate(exactSample.date, exactSample.flag, exactSample.timestamp, true, null);
        TreeMap<Long, GhostRegistrationDate.Estimate> anchors = anchors(observations);
        Map.Entry<Long, GhostRegistrationDate.Estimate> left = anchors.floorEntry(user), right = anchors.ceilingEntry(user);
        if (left == null || right == null || !usable(left, right)) return nearest(user, observations);
        double position = (double)(user - left.getKey()) / (right.getKey() - left.getKey());
        long timestamp = left.getValue().timestamp + Math.round(position * (right.getValue().timestamp - left.getValue().timestamp));
        SimpleDateFormat format = new SimpleDateFormat("dd.MM.yyyy", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return new GhostRegistrationDate.Estimate(format.format(new java.util.Date(timestamp)), "INTERPOLATED", timestamp,
            true, left.getValue().date + " – " + right.getValue().date);
    }
    private static GhostRegistrationDate.Estimate nearest(long user, NavigableMap<Long, GhostRegistrationDate.Estimate> observations) {
        Map.Entry<Long, GhostRegistrationDate.Estimate> left = observations.floorEntry(user), right = observations.ceilingEntry(user);
        Map.Entry<Long, GhostRegistrationDate.Estimate> closest = left == null ? right : right == null ? left
            : user - left.getKey() <= right.getKey() - user ? left : right;
        if (closest == null) return null;
        GhostRegistrationDate.Estimate value = closest.getValue();
        return new GhostRegistrationDate.Estimate(value.date, "NEAREST", value.timestamp, true, null);
    }
    private static TreeMap<Long, GhostRegistrationDate.Estimate> anchors(NavigableMap<Long, GhostRegistrationDate.Estimate> observations) {
        TreeMap<Long, GhostRegistrationDate.Estimate> result = new TreeMap<>();
        for (Map.Entry<Long, GhostRegistrationDate.Estimate> entry : observations.entrySet()) {
            // LT/ET are one-sided bounds, not dated interpolation anchors.
            if (!entry.getValue().local && ("EXACT".equals(entry.getValue().flag) || "INTERPOLATED".equals(entry.getValue().flag))) result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }
    private static boolean usable(Map.Entry<Long, GhostRegistrationDate.Estimate> left, Map.Entry<Long, GhostRegistrationDate.Estimate> right) {
        long ids = right.getKey() - left.getKey(), time = right.getValue().timestamp - left.getValue().timestamp;
        return ids > 0 && ids <= 500000000L && time >= 0 && time <= 180L * 24 * 60 * 60 * 1000;
    }
}
