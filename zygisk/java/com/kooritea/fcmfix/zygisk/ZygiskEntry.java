package com.kooritea.fcmfix.zygisk;

import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Called by the Zygisk library once the hook engine is up.
 *
 * Phase 1 of docs/zygisk-dev-plan.md: this finds the two places the real hooks will be
 * installed from and proves the engine there with a few logging hooks. It changes no system
 * behaviour. Phase 2 replaces install*() with the Fix classes.
 */
public final class ZygiskEntry {
    private static final String TAG = "fcmfix";
    private static final String BATTERY_PACKAGE = "com.oplus.battery";
    private static final int LOGGED_CALLS = 3;
    private static final AtomicBoolean batteryInstalled = new AtomicBoolean();

    private ZygiskEntry() {
    }

    /** late: NeoZygisk attached to a system_server that was already serving. */
    public static void systemServer(boolean late) {
        log("system_server pid=" + Process.myPid() + " late=" + late + " sdk=" + Build.VERSION.SDK_INT
                + " display=" + Build.DISPLAY);
        try {
            if (late) {
                installSystemServer(runningSystemServerClassLoader(), true);
                return;
            }
            // Nothing of services.jar is loaded yet. handleSystemServerProcess creates its
            // class loader; SystemServer.main only runs after it has returned.
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
                    installSystemServer(classLoader, false);
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
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        IBinder activity = (IBinder) serviceManager.getMethod("getService", String.class)
                .invoke(null, "activity");
        if (activity == null) throw new IllegalStateException("no activity service: not a running system_server");
        return activity.getClass().getClassLoader();
    }

    private static void installSystemServer(ClassLoader classLoader, boolean late) {
        try {
            Class<?> controller = Class.forName("com.android.server.am.BroadcastController", false, classLoader);
            int hooks = 0;
            for (Method method : controller.getDeclaredMethods()) {
                if (!"broadcastIntentWithFeature".equals(method.getName())) continue;
                // Two registrations on one method, as BroadcastFix and OplusGoogleServiceFix do.
                AtomicInteger calls = new AtomicInteger();
                Hooks.hook(method, chain -> {
                    boolean logged = calls.incrementAndGet() <= LOGGED_CALLS;
                    if (logged) log("probe outer before: " + action(chain.getArgs()));
                    Object result = chain.proceed();
                    if (logged) log("probe outer after: result=" + result);
                    return result;
                });
                Hooks.hook(method, chain -> {
                    if (calls.get() <= LOGGED_CALLS) log("probe inner");
                    return chain.proceed();
                });
                hooks++;
            }
            int deoptimized = 0;
            Class<?> ams = Class.forName("com.android.server.am.ActivityManagerService", false, classLoader);
            for (Method method : ams.getDeclaredMethods()) {
                if ("broadcastIntentWithFeature".equals(method.getName()) && Hooks.deoptimize(method)) deoptimized++;
            }
            log("system_server probe: hooked=" + hooks + " deoptimized=" + deoptimized);
            if (late) log("late: OplusProxyWakeLock.sInstance " + (proxyWakeLockInstance(classLoader) ? "set" : "missing"));
        } catch (Throwable e) {
            Log.e(TAG, "[zygisk] system_server probe failed", e);
        }
    }

    /** The constructor hook that captures this instance cannot fire after a late load. */
    private static boolean proxyWakeLockInstance(ClassLoader classLoader) {
        try {
            Field instance = Class.forName("com.android.server.power.OplusProxyWakeLock", false, classLoader)
                    .getDeclaredField("sInstance");
            instance.setAccessible(true);
            return instance.get(null) != null;
        } catch (Throwable e) {
            log("late: OplusProxyWakeLock.sInstance unreadable: " + e);
            return false;
        }
    }

    /** Only called in the process named com.oplus.battery, uid system. */
    public static void app(String processName) {
        log("app process=" + processName + " pid=" + Process.myPid());
        try {
            // The package's class loader does not exist yet.
            Class<?> loadedApk = Class.forName("android.app.LoadedApk");
            Field packageName = loadedApk.getDeclaredField("mPackageName");
            packageName.setAccessible(true);
            int hooks = 0;
            for (Method method : loadedApk.getDeclaredMethods()) {
                if (!"createOrUpdateClassLoaderLocked".equals(method.getName())) continue;
                Hooks.hook(method, chain -> {
                    Object result = chain.proceed();
                    if (BATTERY_PACKAGE.equals(packageName.get(chain.getThisObject()))
                            && batteryInstalled.compareAndSet(false, true)) {
                        installBattery();
                    }
                    return result;
                });
                hooks++;
            }
            if (hooks == 0) throw new NoSuchMethodError("LoadedApk#createOrUpdateClassLoaderLocked");
        } catch (Throwable e) {
            Log.e(TAG, "[zygisk] app entry failed", e);
        }
    }

    private static void installBattery() {
        try {
            int hooks = 0;
            AtomicInteger calls = new AtomicInteger();
            for (Method method : Class.forName("android.app.ContextImpl").getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!method.getName().startsWith("sendBroadcast") || parameters.length == 0
                        || parameters[0] != Intent.class) {
                    continue;
                }
                Hooks.hook(method, chain -> {
                    if (calls.incrementAndGet() <= LOGGED_CALLS) {
                        log("battery probe " + chain.getExecutable().getName() + ": " + action(chain.getArgs()));
                    }
                    return chain.proceed();
                });
                hooks++;
            }
            log("battery probe: hooked=" + hooks + " OplusNetworkingControlManager="
                    + exists("android.net.OplusNetworkingControlManager") + " OAppNetControlManager="
                    + exists("android.nwpower.OAppNetControlManager"));
        } catch (Throwable e) {
            Log.e(TAG, "[zygisk] battery probe failed", e);
        }
    }

    private static boolean exists(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static String action(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof Intent) return String.valueOf(((Intent) arg).getAction());
        }
        return "no Intent";
    }

    private static void log(String text) {
        Log.i(TAG, "[zygisk] " + text);
    }
}
