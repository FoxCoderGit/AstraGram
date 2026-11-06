package org.ghostgram.messenger.license;

import android.content.pm.*;
import android.os.Build;
import java.io.File;
import java.security.MessageDigest;
import java.util.*;

final class UpdateApk {
    static long code(PackageInfo info) { return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode; }
    static long installedCode() {
        try { return code(LicenseManager.context.getPackageManager().getPackageInfo(LicenseManager.context.getPackageName(), 0)); }
        catch (Exception e) { return Long.MAX_VALUE; } // Fail closed, never downgrade on lookup failure.
    }
    private static Set<String> signers(PackageInfo info) throws Exception {
        android.content.pm.Signature[] signatures = Build.VERSION.SDK_INT >= 28
            ? info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners() : info.signatures;
        if (signatures == null || signatures.length == 0) throw new SecurityException("No APK signers");
        Set<String> values = new HashSet<>();
        for (android.content.pm.Signature signature : signatures)
            values.add(android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()), android.util.Base64.NO_WRAP));
        return values;
    }
    static void verify(File candidate, UpdateSpec release) throws Exception {
        PackageManager pm = LicenseManager.context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo apk = pm.getPackageArchiveInfo(candidate.getAbsolutePath(), flags);
        PackageInfo installed = pm.getPackageInfo(LicenseManager.context.getPackageName(), flags);
        if (apk == null || !installed.packageName.equals(apk.packageName) || code(apk) != release.versionCode
            || code(apk) <= code(installed) || !signers(apk).equals(signers(installed)))
            throw new SecurityException("APK identity, version or signer mismatch");
    }
    private UpdateApk() {}
}
