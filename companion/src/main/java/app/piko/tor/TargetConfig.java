package app.piko.tor;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class TargetConfig {
    final String packageName;
    final String certificate;
    final int uid;

    TargetConfig(String packageName, String certificate, int uid) {
        this.packageName = packageName;
        this.certificate = certificate;
        this.uid = uid;
    }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("tor_configuration", Context.MODE_PRIVATE);
    }

    static TargetConfig load(Context context) {
        SharedPreferences prefs = preferences(context);
        return new TargetConfig(prefs.getString("target", ""), prefs.getString("certificate", ""), prefs.getInt("uid", -1));
    }

    static TargetConfig inspect(Context context, String packageName)
            throws PackageManager.NameNotFoundException {
        PackageInfo info = context.getPackageManager().getPackageInfo(packageName,
                PackageManager.GET_SIGNING_CERTIFICATES);
        if (info.applicationInfo == null || info.applicationInfo.uid == context.getApplicationInfo().uid
                || info.signingInfo == null || info.sharedUserId != null) {
            throw new SecurityException("Target must have a distinct UID and signing identity");
        }
        List<String> hashes = new ArrayList<>();
        for (Signature signer : info.signingInfo.getApkContentsSigners()) {
            try {
                byte[] bytes = MessageDigest.getInstance("SHA-256").digest(signer.toByteArray());
                StringBuilder hex = new StringBuilder();
                for (byte value : bytes) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
                hashes.add(hex.toString());
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
        if (hashes.isEmpty()) throw new SecurityException("No signing identity");
        Collections.sort(hashes);
        return new TargetConfig(packageName, String.join("\n", hashes), info.applicationInfo.uid);
    }

    boolean valid(Context context) {
        if (packageName.isEmpty() || certificate.isEmpty()) return false;
        try {
            TargetConfig installed = inspect(context, packageName);
            return uid == installed.uid && certificate.equals(installed.certificate);
        } catch (PackageManager.NameNotFoundException | SecurityException e) {
            return false;
        }
    }

    void save(Context context) {
        if (!preferences(context).edit().putString("target", packageName)
                .putString("certificate", certificate).putInt("uid", uid).commit()) {
            throw new IllegalStateException("Could not persist target identity");
        }
    }
}
