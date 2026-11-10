package com.kooritea.fcmfix.zygisk;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.xposed.AutoStartFix;
import com.kooritea.fcmfix.xposed.BroadcastFix;
import com.kooritea.fcmfix.xposed.KeepNotification;
import com.kooritea.fcmfix.xposed.OplusBatteryNetworkFix;
import com.kooritea.fcmfix.xposed.OplusDeviceIdleFix;
import com.kooritea.fcmfix.xposed.OplusGoogleServiceFix;
import com.kooritea.fcmfix.xposed.OplusProxyFix;
import com.kooritea.fcmfix.xposed.OplusStartupFix;
import com.kooritea.fcmfix.xposed.XposedModule;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Called by the Zygisk library once the hook engine is up: in system_server, and in the
 * com.oplus.battery process. Installs the Fix classes and hands them a Context.
 */
public final class ZygiskEntry {
    private static final String TAG = "fcmfix";
    private static final String BATTERY_PACKAGE = "com.oplus.battery";
    /** Services that must be registered before a broadcast receiver can be added. */
    private static final String[] SYSTEM_SERVICES = {"activity", "package", "user", "window"};
    private static final long READY_TIMEOUT_MS = 5 * 60_000L;
    private static final long READY_POLL_MS = 200L;

    private static final AtomicBoolean systemInstalled = new AtomicBoolean();
    private static final AtomicBoolean batteryInstalled = new AtomicBoolean();

    private ZygiskEntry() {
    }

    /** late: NeoZygisk attached to a system_server that was already serving. */
    public static void systemServer(boolean late) {
        XposedModule.setSelfPackageName("android");
        XposedModule.setLateInject(late);
        XposedBridge.log("[fcmfix] zygisk " + BuildInfo.VERSION + "+" + BuildInfo.COMMIT
                + ", late=" + late + ", sdk=" + android.os.Build.VERSION.SDK_INT
                + ", oplusrom=" + systemProperty("ro.build.version.oplusrom.display")
                + ", display=" + android.os.Build.DISPLAY);
        try {
            if (late) {
                // services.jar is long loaded and SystemServer has started its services.
                installSystemServer(runningSystemServerClassLoader());
                return;
            }
            // Nothing of services.jar is loaded yet. handleSystemServerProcess creates its class
            // loader, and SystemServer.main only runs after it has returned.
            Class<?> zygoteInit = Class.forName("com.android.internal.os.ZygoteInit");
            int hooks = 0;
            for (Method method : zygoteInit.getDeclaredMethods()) {
                if (!"handleSystemServerProcess".equals(method.getName())) continue;
                Hooks.hook(method, chain -> {
                    Object result = chain.proceed();
                    hookBootstrap(Thread.currentThread().getContextClassLoader());
                    return result;
                });
                hooks++;
            }
            if (hooks == 0) throw new NoSuchMethodError("ZygoteInit#handleSystemServerProcess");
        } catch (Throwable e) {
            Log.e(TAG, "[zygisk] system_server entry failed", e);
        }
    }

    private static void hookBootstrap(ClassLoader classLoader) {
        try {
            Class<?> systemServer = Class.forName("com.android.server.SystemServer", false, classLoader);
            int hooks = 0;
            for (Method method : systemServer.getDeclaredMethods()) {
                if (!"startBootstrapServices".equals(method.getName())) continue;
                Hooks.hook(method, chain -> {
                    // A fault here must not stop the system from booting.
                    try {
                        installSystemServer(classLoader);
                    } catch (Throwable e) {
                        Log.e(TAG, "[zygisk] installing the system_server hooks failed", e);
                    }
                    return chain.proceed();
                });
                hooks++;
            }
            if (hooks == 0) throw new NoSuchMethodError("SystemServer#startBootstrapServices");
        } catch (Throwable e) {
            Log.e(TAG, "[zygisk] cannot hook SystemServer#startBootstrapServices", e);
        }
    }

    private static ClassLoader runningSystemServerClassLoader() throws ReflectiveOperationException {
        Object activity = service("activity");
        if (activity == null) throw new IllegalStateException("no activity service: not a running system_server");
        return activity.getClass().getClassLoader();
    }

    private static void installSystemServer(ClassLoader classLoader) {
        if (!systemInstalled.compareAndSet(false, true)) return;
        XposedBridge.log("[fcmfix] start hook com.android.server.am.ActivityManagerService/BroadcastController");
        new BroadcastFix(classLoader);
        new AutoStartFix(classLoader);
        new KeepNotification(classLoader);
        new OplusProxyFix(classLoader);
        new OplusStartupFix(classLoader);
        new OplusGoogleServiceFix(classLoader);
        new OplusDeviceIdleFix(classLoader);
        activateWhenReady("system_server", ZygiskEntry::systemContext);
    }

    /** Only called in the process named com.oplus.battery, uid system. */
    public static void app(String processName) {
        XposedModule.setSelfPackageName(BATTERY_PACKAGE);
        XposedBridge.log("[fcmfix] zygisk " + BuildInfo.VERSION + "+" + BuildInfo.COMMIT + ", process=" + processName);
        try {
            // The package's class loader does not exist yet.
            Class<?> loadedApk = Class.forName("android.app.LoadedApk");
            Field packageName = loadedApk.getDeclaredField("mPackageName");
            Field loader = loadedApk.getDeclaredField("mClassLoader");
            packageName.setAccessible(true);
            loader.setAccessible(true);
            int hooks = 0;
            for (Method method : loadedApk.getDeclaredMethods()) {
                if (!"createOrUpdateClassLoaderLocked".equals(method.getName())) continue;
                Hooks.hook(method, chain -> {
                    Object result = chain.proceed();
                    if (BATTERY_PACKAGE.equals(packageName.get(chain.getThisObject()))
                            && batteryInstalled.compareAndSet(false, true)) {
                        try {
                            installBattery((ClassLoader) loader.get(chain.getThisObject()));
                        } catch (Throwable e) {
                            Log.e(TAG, "[zygisk] installing the battery hooks failed", e);
                        }
                    }
                    return result;
                });
                hooks++;
            }
            if (hooks == 0) throw new NoSuchMethodError("LoadedApk#createOrUpdateClassLoaderLocked");
        } catch (Throwable e) {
            Log.e(TAG, "[zygisk] battery entry failed", e);
        }
    }

    private static void installBattery(ClassLoader classLoader) {
        XposedBridge.log("[fcmfix] start hook com.oplus.battery GMS network policy");
        new OplusBatteryNetworkFix(classLoader);
        activateWhenReady("battery", ZygiskEntry::batteryContext);
    }

    /**
     * The Context only exists once the process is further along than the point the hooks are
     * installed at, so wait for it on a thread of our own instead of blocking the process.
     */
    private static void activateWhenReady(String process, Callable<Context> source) {
        Thread thread = new Thread(() -> {
            long deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS;
            Throwable last = null;
            while (SystemClock.elapsedRealtime() < deadline) {
                try {
                    Context context = source.call();
                    if (context != null) {
                        XposedModule.activate(context);
                        return;
                    }
                } catch (Throwable e) {
                    last = e;
                }
                try {
                    Thread.sleep(READY_POLL_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
            Log.e(TAG, "[zygisk] " + process + " never became ready", last);
        }, "FCMFix-activate");
        thread.setDaemon(true);
        thread.start();
    }

    private static Context systemContext() throws ReflectiveOperationException {
        for (String name : SYSTEM_SERVICES) {
            if (service(name) == null) return null;
        }
        Object thread = Class.forName("android.app.ActivityThread").getMethod("currentActivityThread").invoke(null);
        return thread == null ? null : (Context) thread.getClass().getMethod("getSystemContext").invoke(thread);
    }

    private static Context batteryContext() throws ReflectiveOperationException {
        return (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
    }

    private static Object service(String name) throws ReflectiveOperationException {
        return Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, name);
    }

    private static String systemProperty(String key) {
        try {
            return (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class).invoke(null, key, "unknown");
        } catch (Throwable e) {
            return "unknown";
        }
    }
}
