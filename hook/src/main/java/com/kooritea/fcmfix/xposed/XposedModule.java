package com.kooritea.fcmfix.xposed;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Log;
import com.kooritea.fcmfix.util.ConfigFile;
import com.kooritea.fcmfix.util.ConfigSnapshot;
import com.kooritea.fcmfix.util.DiagnosticLogger;
import com.kooritea.fcmfix.util.FcmTrust;
import com.kooritea.fcmfix.util.HookStatus;
import com.kooritea.fcmfix.util.OplusAttribution;
import com.kooritea.fcmfix.util.SelfCheck;
import com.kooritea.fcmfix.zygisk.BuildInfo;
import java.io.File;
import java.lang.reflect.Method;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public abstract class XposedModule {
    protected static final String MODULE_PACKAGE_NAME = "io.github.artifical0.fcmfix.coloros";
    public static final String ACTION_UPDATE_CONFIG = MODULE_PACKAGE_NAME + ".update.config";
    private static final String ACTION_QUERY_STATUS = MODULE_PACKAGE_NAME + HookStatus.QUERY_ACTION_SUFFIX;
    /** system_server keeps the config here; the module app pushes it whenever it changes. */
    private static final File CONFIG_FILE = new File("/data/system/fcmfix/config.properties");
    private static String selfPackageName = "UNKNOWN";
    private static volatile boolean lateInject;

    protected final ClassLoader classLoader;
    static final String TAG = "FcmFix";
    private static volatile ConfigSnapshot config;
    private static final ExecutorService configWriter =
            Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "FCMFix-config"));
    /** Receivers already registered; a failed activation can be retried without doubling them. */
    private static final Set<String> registered = new HashSet<>();

    @SuppressLint("StaticFieldLeak")
    protected static volatile Context context = null;
    public static volatile boolean isBootComplete = false;
    private static final Set<String> loggedWarnings = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final DiagnosticLogger diagnosticLogger = new DiagnosticLogger(
            text -> XposedBridge.log("[fcmfix] " + text),
            error -> Log.w(TAG, "Diagnostic logging failed; further warnings suppressed", error));

    protected static void logOnce(String message) {
        // Bounded deduplication so third-party spam cannot grow system_server memory indefinitely.
        if (loggedWarnings.size() < 64 && loggedWarnings.add(message)) printLog(message);
    }

    protected XposedModule(final ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public static void setSelfPackageName(String packageName) {
        selfPackageName = packageName;
    }

    /** NeoZygisk attached to a system_server that was already serving. */
    public static void setLateInject(boolean late) {
        lateInject = late;
    }

    /**
     * The hooks are installed; give them a Context and start the parts that need one. Runs once
     * the process can use its services, and again after a failed attempt.
     */
    public static synchronized void activate(Context processContext) {
        if (isBootComplete) return;
        context = processContext;
        if (context.getSystemService(UserManager.class).isUserUnlocked()) {
            ready();
        } else {
            registerOnce("unlock", () -> {
                IntentFilter filter = new IntentFilter();
                filter.addAction(Intent.ACTION_USER_UNLOCKED);
                context.registerReceiver(unlockBroadcastReceive, filter);
            });
        }
    }

    private static void ready() {
        loadConfigFromDisk();
        initReceiver();
        // Upstream waited another 60 s here, but GMS reconnects and flushes the messages queued
        // during a reboot within that minute; stopped apps would miss them. Until the config
        // is loaded, targetIsAllow() is false anyway.
        isBootComplete = true;
    }

    protected static void printLog(String text) {
        printLog(text, false);
    }

    protected static void printLog(String text, boolean isDiagnosticsLog) {
        diagnosticLogger.log("[" + getSelfPackageName() + "]" + text, isDiagnosticsLog,
                SystemClock.elapsedRealtime());
    }

    private static final BroadcastReceiver unlockBroadcastReceive = new BroadcastReceiver() {
        public void onReceive(Context _context, Intent intent) {
            if (!Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) return;
            try {
                context.unregisterReceiver(unlockBroadcastReceive);
            } catch (Throwable ignored) {
            }
            // The unlock broadcast is delivered on the main thread; keep file and scan work off it.
            new Thread(() -> {
                synchronized (XposedModule.class) {
                    if (!isBootComplete) ready();
                }
            }, "FCMFix-ready").start();
        }
    };

    protected boolean targetIsAllow(String packageName) {
        ConfigSnapshot snapshot = config;
        return snapshot != null && snapshot.allows(packageName, FcmPackages.current());
    }

    protected String findAllowedPackageArgument(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof String && targetIsAllow((String) arg)) {
                return (String) arg;
            }
        }
        return null;
    }

    protected boolean getBooleanConfig(String key, boolean defaultValue) {
        ConfigSnapshot snapshot = config;
        return snapshot == null ? defaultValue : snapshot.options.getOrDefault(key, defaultValue);
    }

    private static boolean ownsConfig() {
        return "android".equals(getSelfPackageName());
    }

    private static void loadConfigFromDisk() {
        if (!ownsConfig()) return;
        try {
            Map<String, Object> values = ConfigFile.read(CONFIG_FILE);
            if (values == null) {
                printLog("No saved config yet; waiting for the FCMFix app to send it");
                return;
            }
            publish(new ConfigSnapshot(values));
        } catch (Throwable e) {
            printLog("Saved config unreadable, nothing is allowed until the app sends a new one: " + e);
        }
    }

    private static void publish(ConfigSnapshot loaded) {
        config = loaded;
        if (loaded.autoAllowFcm()) FcmPackages.ensureScanned(context);
    }

    /** The module app's pushed config: validated first, then published and saved. */
    private static void acceptConfig(Bundle extras) {
        try {
            ConfigSnapshot pushed = new ConfigSnapshot(ConfigSnapshot.valuesFromExtras(extras));
            publish(pushed);
            configWriter.execute(() -> {
                try {
                    ConfigFile.write(CONFIG_FILE, pushed);
                } catch (Throwable e) {
                    printLog("Config could not be saved: " + e);
                }
            });
        } catch (Throwable e) {
            printLog("Pushed config rejected, the previous one stays: " + e.getMessage());
        }
    }

    private static void registerOnce(String name, Runnable registration) {
        if (!registered.add(name)) return;
        try {
            registration.run();
        } catch (RuntimeException e) {
            registered.remove(name);
            throw e;
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private static void initReceiver() {
        if (Build.VERSION.SDK_INT < 34) {
            logOnce("The app can only reach the hooks on Android 14+.");
            return;
        }
        registerOnce("control", () -> {
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_UPDATE_CONFIG);
            filter.addAction(ACTION_QUERY_STATUS);
            context.registerReceiver(new BroadcastReceiver() {
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (ACTION_UPDATE_CONFIG.equals(action) && isConfigSender(this)) {
                        if (ownsConfig()) acceptConfig(intent.getExtras());
                    } else if (ACTION_QUERY_STATUS.equals(action) && isOrderedBroadcast() && isConfigSender(this)) {
                        if (intent.getBooleanExtra(SelfCheck.EXTRA_CHECK, false) && ownsConfig()) {
                            answerSelfCheck(goAsync());
                        } else {
                            setResultExtras(addHookStatus(getResultExtras(true),
                                    intent.getBooleanExtra(HookStatus.EXTRA_LOGS, false)));
                        }
                    }
                }
            }, filter, Context.RECEIVER_EXPORTED);
        });

        if (ownsConfig()) {
            registerOnce("packages", () -> {
                FcmConnectionMonitor.start(context);
                IntentFilter packageFilter = new IntentFilter();
                packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
                packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
                packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
                packageFilter.addDataScheme("package");
                context.registerReceiver(new BroadcastReceiver() {
                    public void onReceive(Context context, Intent intent) {
                        if (intent.getData() != null) {
                            FcmPackages.onPackageChanged(context, intent.getData().getSchemeSpecificPart());
                        }
                    }
                }, packageFilter);
            });
        }
    }

    protected boolean isFCMAction(String action) {
        return com.kooritea.fcmfix.util.FcmTrust.isAction(action);
    }

    private static Bundle addHookStatus(Bundle result, boolean logs) {
        String process = getSelfPackageName();
        if (logs) result.putByteArray(process + HookStatus.LOGS_SUFFIX, HookStatus.pack(XposedBridge.recentLogs()));
        result.putStringArrayList(process + HookStatus.ACTIVE_SUFFIX, OplusHooks.STATUS.active());
        result.putStringArrayList(process + HookStatus.FAILED_SUFFIX, OplusHooks.STATUS.failed());
        result.putStringArrayList(process + HookStatus.SKIPPED_SUFFIX, OplusHooks.STATUS.skipped());
        result.putString(process + HookStatus.VERSION_SUFFIX, BuildInfo.VERSION + "+" + BuildInfo.COMMIT);
        result.putBoolean(process + HookStatus.LATE_SUFFIX, lateInject);
        if (ownsConfig()) {
            result.putBoolean(HookStatus.KEY_IGNORE_GMS_USER_SET, OplusBatteryNetworkFix.batteryIgnoresGmsUserSet());
        }
        return result;
    }

    /** Self-check reads other services over Binder; keep it off system_server's main thread. */
    private static void answerSelfCheck(BroadcastReceiver.PendingResult pending) {
        new Thread(() -> {
            try {
                Bundle extras = addHookStatus(pending.getResultExtras(true), false);
                try {
                    SelfCheckCollector.collect(context, config, extras);
                } catch (Throwable e) {
                    printLog("Self-check failed: " + e);
                }
                pending.setResultExtras(extras);
            } finally {
                pending.finish();
            }
        }, "FCMFix-selfcheck").start();
    }

    private static boolean isConfigSender(BroadcastReceiver receiver) {
        if (Build.VERSION.SDK_INT < 34 || context == null) return false;
        try {
            return MODULE_PACKAGE_NAME.equals(receiver.getSentFromPackage())
                    && receiver.getSentFromUid() == context.getPackageManager().getPackageUid(MODULE_PACKAGE_NAME, 0);
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    protected static boolean isGmsUid(int uid) {
        if (context == null || uid < 10000) return false;
        // Existing unfreeze/UID resolution uses this process's user. Do not apply its
        // window to the personal-profile copy of a work-profile broadcast target.
        if (!android.os.UserHandle.getUserHandleForUid(uid).equals(android.os.Process.myUserHandle())) return false;
        try {
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            return com.kooritea.fcmfix.util.FcmTrust.isGmsSender(uid, packages);
        } catch (RuntimeException e) {
            printLog("Cannot verify GMS UID: " + e.getMessage());
        }
        return false;
    }

    protected static String explicitTarget(Intent intent) {
        if (intent == null) return null;
        return FcmTrust.target(intent.getPackage(), intent.getComponent() == null
                ? null : intent.getComponent().getPackageName());
    }

    protected static int attributionIndex(Method method) {
        String[] types = java.util.Arrays.stream(method.getParameterTypes()).map(Class::getName).toArray(String[]::new);
        return OplusAttribution.callerIndex(method.getDeclaringClass().getName(),
                method.getName(), method.getReturnType().getName(), types);
    }

    protected boolean trustedDelivery(Intent intent, String target, XC_MethodHook.MethodHookParam param) {
        // Require a coherent, explicit destination. Do not select an arbitrary allowlisted string argument.
        if (!isBootComplete || intent == null || target == null || !target.equals(explicitTarget(intent))
                || !targetIsAllow(target)) return false;
        try {
            // A queued broadcast is attributed by BroadcastRecord, never by ambient Binder identity.
            for (Object arg : param.args) {
                if (arg == null || !"com.android.server.am.BroadcastRecord".equals(arg.getClass().getName())) continue;
                return XposedHelpers.getObjectField(arg, "intent") == intent
                        && FcmTrust.RECEIVE.equals(intent.getAction())
                        && isGmsUid((Integer) XposedHelpers.getObjectField(arg, "callingUid"));
            }
            if (param.method instanceof Method) {
                Method method = (Method) param.method;
                int index = attributionIndex(method);
                if (index >= 0) {
                    int callerUid = (Integer) param.args[index];
                    String methodName = method.getName();
                    // Validate the actual callee too, not only the Intent destination.
                    if ("isAppClassifyRestricted".equals(methodName)) {
                        if (!target.equals(param.args[1])) return false;
                    } else {
                        int serviceIndex = "isAllowStartFromBindService".equals(methodName) ? 3 : 4;
                        Object info = XposedHelpers.getObjectField(param.args[serviceIndex], "appInfo");
                        if (!(info instanceof android.content.pm.ApplicationInfo)
                                || !target.equals(((android.content.pm.ApplicationInfo) info).packageName)) return false;
                    }
                    boolean gms = isGmsUid(callerUid);
                    String[] packages = context.getPackageManager().getPackagesForUid(callerUid);
                    boolean selfInWindow = packages != null && java.util.Arrays.asList(packages).contains(target)
                            && OplusProxyFix.isInFcmDeliveryWindow(callerUid);
                    boolean gcmBind = "isAllowStartFromBindService".equals(methodName)
                            && "bsgcm".equals(param.args[5]);
                    boolean trusted = FcmTrust.allowsService(intent.getAction(), gms, selfInWindow, gcmBind);
                    if (trusted && gms) OplusProxyFix.beginFcmDeliveryWindow(target);
                    if (!trusted) logOnce("FCM rejected: untrusted service sender uid=" + callerUid
                            + ", target=" + target + ", method=" + methodName);
                    return trusted;
                }
            }
            // Only the synchronous broadcast path may inherit the verified entry context.
            return FcmTrust.matches(intent.getAction(), target);
        } catch (Throwable error) {
            logOnce("Unsupported delivery attribution: " + param.method + ": " + error);
            return false;
        }
    }

    protected boolean isFCMIntent(Intent intent) {
        String action = intent.getAction();
        return isFCMAction(action);
    }

    protected static String getSelfPackageName() {
        return selfPackageName;
    }
}
