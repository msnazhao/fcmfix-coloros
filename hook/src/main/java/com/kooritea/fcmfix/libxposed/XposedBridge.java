package com.kooritea.fcmfix.libxposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;

import com.kooritea.fcmfix.util.LogRing;
import com.kooritea.fcmfix.zygisk.Hooks;

/** The hook API the Fix classes use, on top of the Zygisk library's ART hook engine. */
public final class XposedBridge {

    private static final LogRing RECENT = new LogRing(1000);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS");

    private XposedBridge() {
    }

    public static void log(String text) {
        android.util.Log.i("fcmfix", text);
        RECENT.add(LocalDateTime.now().format(TIME) + " " + text);
    }

    /** This process's recent module log lines, oldest first. */
    public static ArrayList<String> recentLogs() {
        return RECENT.snapshot();
    }

    /**
     * Runs the callback around the method. Several registrations on one method each run once,
     * the first registered outermost; the engine itself hooks the method only once.
     */
    public static void hookMethod(Member member, XC_MethodHook callback) {
        if (!(member instanceof Method) && !(member instanceof Constructor<?>)) {
            throw new IllegalArgumentException("Only Method/Constructor can be hooked");
        }
        Hooks.hook((Executable) member, chain -> {
            XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
            param.method = chain.getExecutable();
            param.thisObject = chain.getThisObject();
            param.args = chain.getArgs().clone();
            return HookInvocation.invoke(param, callback, chain::proceed,
                    error -> log("Hook failed: " + member + ": " + error));
        });
    }

    /**
     * Force a caller back to non-inlined execution so hooks on its short callees fire.
     * Returns false instead of throwing; a failed deoptimization only loses that layer.
     */
    public static boolean deoptimize(Member member) {
        if (!(member instanceof Executable)) return false;
        try {
            return Hooks.deoptimize((Executable) member);
        } catch (Throwable e) {
            log("deoptimize failed: " + member + ": " + e);
            return false;
        }
    }
}
