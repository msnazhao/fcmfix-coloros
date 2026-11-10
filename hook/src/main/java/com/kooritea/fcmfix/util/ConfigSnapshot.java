package com.kooritea.fcmfix.util;

import android.os.Bundle;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** One immutable view of one config push or file read; partial updates are never published. */
public final class ConfigSnapshot {
    public static final String AUTO_ALLOW_FCM = "autoAllowFcm";
    public static final String ALLOW_LIST = "allowList";
    public static final String EXCLUDE_LIST = "excludeList";
    public static final String KEEP_NOTIFICATIONS = "disableAutoCleanNotification";
    public static final String ICE_BOX = "includeIceBoxDisableApp";

    /** Apps the user ticked by hand. */
    public final Set<String> allowList;
    /** FCM apps the user unticked while auto-allow is on; they stay out of the automatic set. */
    public final Set<String> excludeList;
    public final Map<String, Boolean> options;

    public ConfigSnapshot(Map<String, ?> values) {
        allowList = packageSet(values, ALLOW_LIST);
        excludeList = packageSet(values, EXCLUDE_LIST);
        Map<String, Boolean> flags = new HashMap<>();
        for (String key : new String[]{KEEP_NOTIFICATIONS, ICE_BOX, AUTO_ALLOW_FCM}) {
            Object value = values.get(key);
            if (value != null && !(value instanceof Boolean)) throw new IllegalArgumentException("Invalid " + key);
            flags.put(key, Boolean.TRUE.equals(value));
        }
        options = Collections.unmodifiableMap(flags);
    }

    /** The values of a config pushed by the module app; a missing key means false or empty. */
    public static Map<String, Object> valuesFromExtras(Bundle extras) {
        if (extras == null) throw new IllegalArgumentException("No config in the broadcast");
        Map<String, Object> values = new HashMap<>();
        for (String key : new String[]{ALLOW_LIST, EXCLUDE_LIST}) {
            String[] names = extras.getStringArray(key);
            values.put(key, names == null ? new HashSet<String>() : new HashSet<>(java.util.Arrays.asList(names)));
        }
        for (String key : new String[]{KEEP_NOTIFICATIONS, ICE_BOX, AUTO_ALLOW_FCM}) {
            values.put(key, extras.getBoolean(key, false));
        }
        return values;
    }

    public boolean autoAllowFcm() {
        return options.get(AUTO_ALLOW_FCM);
    }

    /** Whether a push to this package is let through; fcmPackages are the installed FCM apps. */
    public boolean allows(String packageName, Set<String> fcmPackages) {
        if (packageName == null) return false;
        if (allowList.contains(packageName)) return true;
        return autoAllowFcm() && !excludeList.contains(packageName) && fcmPackages.contains(packageName);
    }

    private static Set<String> packageSet(Map<String, ?> values, String key) {
        Set<String> packages = new HashSet<>();
        Object raw = values.get(key);
        if (raw != null) {
            if (!(raw instanceof Set<?>)) throw new IllegalArgumentException("Invalid " + key);
            for (Object name : (Set<?>) raw) {
                if (!(name instanceof String)) throw new IllegalArgumentException("Invalid package name");
                packages.add((String) name);
            }
        }
        return Collections.unmodifiableSet(packages);
    }
}
