package com.kooritea.fcmfix.util;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.HashSet;
import java.util.Set;

/**
 * The one rule for "this app can receive FCM", used by system_server (auto-allow) and by the
 * module app (the list and its "包含 FCM" filter). Two copies of the rule drifted apart once: the
 * app counted 39 apps and the status page 38. An app is an FCM app when it is installed for this
 * user, is not a system-uid package, and declares the FCM receive receiver, the
 * MESSAGING_EVENT service, or Firebase's own receiver.
 */
public final class FcmApps {
    public static final String MESSAGING_EVENT = "com.google.firebase.MESSAGING_EVENT";
    private static final String FIREBASE_IID_RECEIVER = "com.google.firebase.iid.FirebaseInstanceIdReceiver";
    private static final int COMPONENT_FLAGS = PackageManager.MATCH_DISABLED_COMPONENTS
            | PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
    /** Packages removed for this user but with data kept are deliberately not matched. */
    private static final int PACKAGE_FLAGS = PackageManager.GET_RECEIVERS | PackageManager.MATCH_DISABLED_COMPONENTS;

    private FcmApps() {
    }

    /** Every installed app package that can receive FCM. Walks all packages: not for a hot path. */
    @SuppressWarnings("deprecation") // The flag overloads need API 33; minSdk is 29.
    public static Set<String> scan(PackageManager pm) {
        Set<String> found = new HashSet<>();
        for (ResolveInfo info : pm.queryBroadcastReceivers(new Intent(FcmTrust.RECEIVE), COMPONENT_FLAGS)) {
            if (info.activityInfo != null) addApp(found, info.activityInfo.packageName, info.activityInfo.applicationInfo);
        }
        for (ResolveInfo info : pm.queryIntentServices(new Intent(MESSAGING_EVENT), COMPONENT_FLAGS)) {
            if (info.serviceInfo != null) addApp(found, info.serviceInfo.packageName, info.serviceInfo.applicationInfo);
        }
        for (PackageInfo info : pm.getInstalledPackages(PACKAGE_FLAGS)) {
            if (declaresFirebaseReceiver(info)) addApp(found, info.packageName, info.applicationInfo);
        }
        return found;
    }

    /** The same rule as scan() for one package, e.g. after it was installed or updated. */
    @SuppressWarnings("deprecation")
    public static boolean packageHasFcm(PackageManager pm, String packageName) {
        try {
            PackageInfo info = pm.getPackageInfo(packageName, PACKAGE_FLAGS);
            if (info.applicationInfo == null || !FcmTrust.isAppUid(info.applicationInfo.uid)) return false;
            if (declaresFirebaseReceiver(info)) return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        return !pm.queryBroadcastReceivers(new Intent(FcmTrust.RECEIVE).setPackage(packageName), COMPONENT_FLAGS).isEmpty()
                || !pm.queryIntentServices(new Intent(MESSAGING_EVENT).setPackage(packageName), COMPONENT_FLAGS).isEmpty();
    }

    private static void addApp(Set<String> found, String packageName, ApplicationInfo info) {
        if (packageName != null && info != null && FcmTrust.isAppUid(info.uid)) found.add(packageName);
    }

    private static boolean declaresFirebaseReceiver(PackageInfo info) {
        if (info.receivers == null) return false;
        for (android.content.pm.ActivityInfo receiver : info.receivers) {
            if (FIREBASE_IID_RECEIVER.equals(receiver.name)) return true;
        }
        return false;
    }
}
