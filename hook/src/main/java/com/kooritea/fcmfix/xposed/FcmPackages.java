package com.kooritea.fcmfix.xposed;

import android.content.Context;

import com.kooritea.fcmfix.util.FcmApps;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Installed apps that can receive FCM, kept in system_server for the auto-allow option. The
 * delivery hooks only read the published set; scans run on one worker thread, never inside a
 * broadcast or AMS call. What counts as an FCM app is decided by FcmApps, the same rule the
 * module app uses for its list.
 */
final class FcmPackages {
    private static final ExecutorService worker =
            Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "FCMFix-fcm-apps"));
    /** null until the first scan finishes. */
    private static volatile Set<String> packages;
    private static boolean scanQueued;

    private FcmPackages() {
    }

    static Set<String> current() {
        Set<String> snapshot = packages;
        return snapshot == null ? Collections.emptySet() : snapshot;
    }

    /** Number of known FCM apps, or -1 before the first scan. */
    static int size() {
        Set<String> snapshot = packages;
        return snapshot == null ? -1 : snapshot.size();
    }

    static synchronized void ensureScanned(Context context) {
        if (scanQueued || context == null) return;
        scanQueued = true;
        worker.execute(() -> {
            try {
                Set<String> found = FcmApps.scan(context.getPackageManager());
                packages = Collections.unmodifiableSet(found);
                XposedModule.printLog("FCM apps scanned for auto-allow: " + found.size());
            } catch (Throwable e) {
                synchronized (FcmPackages.class) {
                    scanQueued = false;
                }
                XposedModule.printLog("FCM app scan failed: " + e);
            }
        });
    }

    /** Re-checks one package after it was installed, updated, changed or removed. */
    static void onPackageChanged(Context context, String packageName) {
        if (packageName == null || context == null) return;
        worker.execute(() -> {
            Set<String> snapshot = packages;
            if (snapshot == null) return;
            try {
                boolean fcm = FcmApps.packageHasFcm(context.getPackageManager(), packageName);
                if (fcm == snapshot.contains(packageName)) return;
                Set<String> updated = new HashSet<>(snapshot);
                if (fcm) updated.add(packageName);
                else updated.remove(packageName);
                packages = Collections.unmodifiableSet(updated);
                if (fcm) XposedModule.printLog("FCM app added for auto-allow: " + packageName);
            } catch (Throwable e) {
                XposedModule.printLog("FCM app check failed: " + packageName + ": " + e);
            }
        });
    }
}
