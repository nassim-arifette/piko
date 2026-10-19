/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */
package app.morphe.extension.twitter.settings;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import app.morphe.extension.crimera.PikoUtils;

public final class TorCompanion {
    private static final String PACKAGE = "app.piko.tor";

    private TorCompanion() {}

    public static String summary(Context context) {
        if (Build.VERSION.SDK_INT < 29) return str("piko_tor_unsupported");
        try {
            String version = context.getPackageManager().getPackageInfo(PACKAGE, 0).versionName;
            return str("piko_tor_installed", version) + "\n" + str("piko_tor_unverified");
        } catch (PackageManager.NameNotFoundException e) {
            return str("piko_tor_missing");
        }
    }

    public static void open(Context context) {
        if (Build.VERSION.SDK_INT < 29) {
            PikoUtils.toast(str("piko_tor_unsupported"));
            return;
        }
        while (context instanceof ContextWrapper && !(context instanceof Activity)) {
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) break;
            context = base;
        }
        if (!(context instanceof Activity)) {
            PikoUtils.toast(str("piko_tor_open_failed"));
            return;
        }
        Intent intent = new Intent().setComponent(new ComponentName(PACKAGE, PACKAGE + ".MainActivity"));
        try {
            // Android supplies getCallingPackage() to the companion; no trusted state is returned to X.
            ((Activity) context).startActivityForResult(intent, 0x5049);
        } catch (RuntimeException e) {
            PikoUtils.toast(str("piko_tor_open_failed"));
        }
    }
}
