package com.kooritea.fcmfix.zygisk;

import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Java side of the ART hook engine in the Zygisk library.
 *
 * The engine can hook a method once. Every interceptor registered for the same method is
 * therefore kept here and run as one chain, the first registered outermost.
 */
public final class Hooks {
    public interface Interceptor {
        /** Call chain.proceed() to run the remaining interceptors and the original method. */
        Object intercept(Chain chain) throws Throwable;
    }

    public static final class Chain {
        private final Hooker hooker;
        private final Interceptor[] interceptors;
        private final int index;
        private final Object thisObject;
        private final Object[] args;

        Chain(Hooker hooker, Interceptor[] interceptors, int index, Object thisObject, Object[] args) {
            this.hooker = hooker;
            this.interceptors = interceptors;
            this.index = index;
            this.thisObject = thisObject;
            this.args = args;
        }

        public Executable getExecutable() {
            return hooker.target;
        }

        /** Null for a static method. */
        public Object getThisObject() {
            return thisObject;
        }

        public Object[] getArgs() {
            return args;
        }

        public Object proceed() throws Throwable {
            return proceed(args);
        }

        /** Throws what the original method threw, unwrapped. */
        public Object proceed(Object[] newArgs) throws Throwable {
            if (index < interceptors.length) {
                return interceptors[index].intercept(
                        new Chain(hooker, interceptors, index + 1, thisObject, newArgs));
            }
            try {
                return hooker.backup().invoke(thisObject, newArgs);
            } catch (InvocationTargetException e) {
                throw e.getCause() == null ? e : e.getCause();
            }
        }
    }

    /**
     * What the engine calls in place of a hooked method. Public, with a public callback: the
     * engine's generated stub class lives in another package.
     */
    public static final class Hooker {
        final Executable target;
        private final boolean isStatic;
        private volatile Interceptor[] interceptors = new Interceptor[0];
        private Method backup;

        Hooker(Executable target) {
            this.target = target;
            this.isStatic = Modifier.isStatic(target.getModifiers());
        }

        /** args[0] is the receiver unless the method is static. */
        public Object callback(Object[] args) throws Throwable {
            Object thisObject = isStatic ? null : args[0];
            Object[] parameters = isStatic ? args : Arrays.copyOfRange(args, 1, args.length);
            return new Chain(this, interceptors, 0, thisObject, parameters).proceed();
        }

        synchronized void add(Interceptor interceptor) {
            Interceptor[] next = Arrays.copyOf(interceptors, interceptors.length + 1);
            next[next.length - 1] = interceptor;
            interceptors = next;
        }

        synchronized void publish(Method method) {
            backup = method;
            notifyAll();
        }

        /** Another thread can enter the hooked method before nativeHook has returned. */
        synchronized Method backup() throws InterruptedException {
            while (backup == null) wait();
            return backup;
        }
    }

    private static final Map<Executable, Hooker> HOOKERS = new HashMap<>();
    private static final Method CALLBACK;

    static {
        try {
            CALLBACK = Hooker.class.getDeclaredMethod("callback", Object[].class);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Hooks() {
    }

    /** Throws when the engine cannot hook the method; nothing is registered then. */
    public static void hook(Executable target, Interceptor interceptor) {
        synchronized (HOOKERS) {
            Hooker hooker = HOOKERS.get(target);
            if (hooker != null) {
                hooker.add(interceptor);
                return;
            }
            hooker = new Hooker(target);
            hooker.add(interceptor);
            target.setAccessible(true);
            Method backup = nativeHook(target, hooker, CALLBACK);
            if (backup == null) throw new IllegalStateException("cannot hook " + target);
            hooker.publish(backup);
            HOOKERS.put(target, hooker);
        }
    }

    /** Makes the method run interpreted, so hooks on methods it had inlined are reached. */
    public static boolean deoptimize(Executable target) {
        return nativeDeoptimize(target);
    }

    private static native Method nativeHook(Executable target, Object hooker, Method callback);

    private static native boolean nativeDeoptimize(Executable target);
}
