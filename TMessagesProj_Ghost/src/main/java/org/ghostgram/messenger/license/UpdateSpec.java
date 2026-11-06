package org.ghostgram.messenger.license;

import org.json.JSONObject;

/** Release metadata is accepted only from the signed owner policy. */
public final class UpdateSpec {
    public final String id, path, sha256, version, notes;
    public final long versionCode, size;
    public final boolean mandatory;
    public UpdateSpec(JSONObject value) throws Exception {
        id = value.getString("id"); path = value.getString("path"); sha256 = value.getString("sha256");
        versionCode = value.getLong("version_code"); size = value.getLong("size");
        version = value.getString("version"); notes = value.optString("notes", "");
        mandatory = value.optBoolean("mandatory", false);
        if (!id.matches("[a-f0-9]{32}") || !path.equals("/downloads/" + id + ".apk")
            || !sha256.matches("[a-f0-9]{64}") || versionCode <= 0 || size <= 0 || size > 200000000
            || version.isEmpty() || version.length() > 100 || notes.length() > 4000)
            throw new IllegalArgumentException("Invalid update metadata");
    }
    public boolean newerThan(long installed) { return versionCode > installed; }
    public boolean canSkip() { return !mandatory; }
    public static boolean validRange(String header, long offset, long size) {
        return ("bytes " + offset + "-" + (size - 1) + "/" + size).equals(header);
    }
}
