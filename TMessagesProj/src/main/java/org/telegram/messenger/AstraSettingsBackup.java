package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public final class AstraSettingsBackup {
    private static final String FORMAT = "astragram-settings";
    private static final DispatchQueue queue = new DispatchQueue("astraSettingsBackup");

    private AstraSettingsBackup() {}

    public interface Callback<T> { void done(T result, Throwable error); }

    private static Map<String, String> scopes() {
        Map<String, String> scopes = new LinkedHashMap<>();
        for (String name : new String[]{"mainconfig", "userconfing", "Notifications", "themeconfig", "ghost_drag", "ghost_background",
                "ghost_owner_control", "ghost_technical_diagnostics", "plugin_settings", "exteraConfig", "background_activity"}) {
            scopes.put(name, name);
        }
        for (int slot = 0; slot < UserConfig.MAX_ACCOUNT_COUNT; slot++) {
            UserConfig config = UserConfig.getInstance(slot);
            if (!config.isClientActivated()) continue;
            long user = config.getClientUserId();
            scopes.put("ghostmode:" + user, "ghostmode_" + slot);
            scopes.put("ghost_cloud_" + user, "ghost_cloud_" + user);
            scopes.put("ghost_online_peek_" + user, "ghost_online_peek_" + user);
            scopes.put("ghost_silent_entry_" + user, "ghost_silent_entry_" + user);
            scopes.put("ghost_collections:" + user, "ghost_collections_" + slot + "_" + user);
            if (slot != 0) {
                scopes.put("mainconfig:" + user, "mainconfig" + slot);
                scopes.put("Notifications:" + user, "Notifications" + slot);
            }
        }
        return scopes;
    }

    private static boolean included(String file, String key) {
        if (key.length() > 512) return false;
        if (file.startsWith("ghost_cloud_")) return key.equals("config");
        if (file.equals("ghost_owner_control")) return key.equals("automatic_updates") || key.equals("statistics");
        if (file.equals("ghost_technical_diagnostics")) return key.equals("enabled");
        if (file.equals("userconfing")) {
            return Arrays.asList("saveIncomingPhotos", "autoLockIn", "useFingerprint", "allowScreenCapture", "sortContactsByName",
                    "sortFilesByName", "proxyRotationEnabled", "proxyRotationTimeout").contains(key);
        }
        String lower = key.toLowerCase(Locale.ROOT);
        return !lower.equals("proxy_pass") && !lower.equals("proxy_list")
                && !lower.matches(".*(password|passcode|secret|token|api[_-]?(?:id|hash|key)|authkey|privatekey|certificate|session|lease|captcha|phone_number).*" );
    }

    private static SharedPreferences preferences(String name) {
        return ApplicationLoader.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    public static void encrypt(char[] password, Callback<byte[]> callback) {
        queue.postRunnable(() -> {
            byte[] plaintext = null, result = null;
            Throwable error = null;
            try {
                JSONObject groups = new JSONObject();
                for (Map.Entry<String, String> scope : scopes().entrySet()) {
                    JSONObject group = new JSONObject();
                    for (Map.Entry<String, ?> entry : preferences(scope.getValue()).getAll().entrySet()) {
                        if (included(scope.getValue(), entry.getKey())) group.put(entry.getKey(), encode(entry.getValue()));
                    }
                    groups.put(scope.getKey(), group);
                }
                plaintext = new JSONObject().put("format", FORMAT).put("version", 1).put("settings", groups).toString().getBytes(StandardCharsets.UTF_8);
                result = GhostSessionCrypto.encrypt(plaintext, password, GhostSessions::derive);
            } catch (Exception failure) {
                error = failure;
            } finally {
                Arrays.fill(password, '\0');
                if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
            }
            byte[] encrypted = result;
            Throwable failure = error;
            AndroidUtilities.runOnUIThread(() -> callback.done(encrypted, failure));
        });
    }

    public static void restore(byte[] encrypted, char[] password, Callback<Integer> callback) {
        queue.postRunnable(() -> {
            byte[] plaintext = null;
            int restored = 0;
            Throwable error = null;
            try {
                plaintext = GhostSessionCrypto.decrypt(encrypted, password, GhostSessions::derive);
                JSONObject root = new JSONObject(new String(plaintext, StandardCharsets.UTF_8));
                if (!FORMAT.equals(root.optString("format")) || root.optInt("version") != 1) throw new IOException("Settings format");
                restored = apply(root.getJSONObject("settings"));
            } catch (Exception failure) {
                error = failure;
            } finally {
                Arrays.fill(password, '\0');
                Arrays.fill(encrypted, (byte) 0);
                if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
            }
            int count = restored;
            Throwable failure = error;
            AndroidUtilities.runOnUIThread(() -> callback.done(count, failure));
        });
    }

    private static int apply(JSONObject groups) throws Exception {
        Map<SharedPreferences, Map<String, Object>> pending = new LinkedHashMap<>();
        for (Map.Entry<String, String> scope : scopes().entrySet()) {
            JSONObject group = groups.optJSONObject(scope.getKey());
            if (group == null) continue;
            Map<String, Object> values = new LinkedHashMap<>();
            SharedPreferences destination = preferences(scope.getValue());
            Map<String, ?> existing = destination.getAll();
            java.util.Iterator<String> keys = group.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!included(scope.getValue(), key)) continue;
                Object value = decode(group.getJSONArray(key));
                Object old = existing.get(key);
                if (old != null && !(old instanceof Set && value instanceof Set) && !old.getClass().equals(value.getClass())) continue;
                // A restore must not start uploading media on a different installation.
                if (scope.getValue().startsWith("ghost_cloud_")) {
                    GhostCloudStore.Config cloud = GhostCloudStore.Config.parse((String) value);
                    cloud.enabled = false;
                    value = cloud.json().toString();
                }
                values.put(key, value);
            }
            pending.put(destination, values);
        }
        Map<SharedPreferences, Map<String, Object>> originals = new LinkedHashMap<>();
        int count = 0;
        try {
            for (Map.Entry<SharedPreferences, Map<String, Object>> group : pending.entrySet()) {
                Map<String, Object> original = new LinkedHashMap<>();
                Map<String, ?> previous = group.getKey().getAll();
                for (String key : group.getValue().keySet()) original.put(key, previous.get(key));
                originals.put(group.getKey(), original);
                write(group.getKey(), group.getValue());
                count += group.getValue().size();
            }
        } catch (IOException failure) {
            for (Map.Entry<SharedPreferences, Map<String, Object>> entry : originals.entrySet()) {
                try { write(entry.getKey(), entry.getValue()); } catch (IOException problem) { failure.addSuppressed(problem); }
            }
            throw failure;
        }
        return count;
    }

    private static JSONArray encode(Object value) {
        String kind = value instanceof Boolean ? "bool" : value instanceof Integer ? "int" : value instanceof Long ? "long"
                : value instanceof Float ? "float" : value instanceof Set ? "set" : "string";
        return new JSONArray().put(kind).put(value instanceof Set ? new JSONArray((Set<?>) value) : value.toString());
    }

    private static Object decode(JSONArray encoded) throws Exception {
        switch (encoded.getString(0)) {
            case "bool": return Boolean.valueOf(encoded.getString(1));
            case "int": return Integer.valueOf(encoded.getString(1));
            case "long": return Long.valueOf(encoded.getString(1));
            case "float": return Float.valueOf(encoded.getString(1));
            case "string": return encoded.getString(1);
            case "set":
                Set<String> strings = new LinkedHashSet<>();
                JSONArray array = encoded.getJSONArray(1);
                for (int i = 0; i < array.length(); i++) strings.add(array.getString(i));
                return strings;
            default: throw new IOException("Invalid preference type");
        }
    }

    @SuppressWarnings("unchecked")
    private static void write(SharedPreferences prefs, Map<String, Object> values) throws IOException {
        SharedPreferences.Editor editor = prefs.edit();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null) editor.remove(key);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Set) editor.putStringSet(key, (Set<String>) value);
            else editor.putString(key, (String) value);
        }
        if (!editor.commit()) throw new IOException("Settings write failed");
    }
}
