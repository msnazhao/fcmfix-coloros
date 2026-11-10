package com.kooritea.fcmfix.zygisk;

import static org.junit.Assert.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class HooksTest {
    public static int twice(int value) {
        return value * 2;
    }

    public static void fail() {
        throw new IllegalStateException("original failed");
    }

    private static Method method(String name, Class<?>... types) throws Exception {
        return HooksTest.class.getDeclaredMethod(name, types);
    }

    private static Hooks.Hooker hooker(Method target, Method backup) {
        Hooks.Hooker hooker = new Hooks.Hooker(target);
        hooker.publish(backup);
        return hooker;
    }

    @Test public void interceptorsRunOutermostFirstAndReachTheOriginalOnce() throws Throwable {
        Method target = method("twice", int.class);
        Hooks.Hooker hooker = hooker(target, target);
        List<String> order = new ArrayList<>();
        hooker.add(chain -> { order.add("first before"); Object result = chain.proceed(); order.add("first after"); return result; });
        hooker.add(chain -> { order.add("second before"); Object result = chain.proceed(); order.add("second after"); return result; });
        assertEquals(42, hooker.callback(new Object[]{21}));
        assertEquals(java.util.Arrays.asList("first before", "second before", "second after", "first after"), order);
    }

    @Test public void interceptorCanChangeArgumentsForTheOnesBelow() throws Throwable {
        Method target = method("twice", int.class);
        Hooks.Hooker hooker = hooker(target, target);
        hooker.add(chain -> chain.proceed(new Object[]{5}));
        assertEquals(10, hooker.callback(new Object[]{21}));
    }

    @Test public void interceptorCanReturnWithoutCallingTheOriginal() throws Throwable {
        Method target = method("twice", int.class);
        Hooks.Hooker hooker = hooker(target, target);
        hooker.add(chain -> -1);
        assertEquals(-1, hooker.callback(new Object[]{21}));
    }

    @Test public void originalExceptionIsThrownUnwrapped() throws Exception {
        Method target = method("fail");
        Hooks.Hooker hooker = hooker(target, target);
        hooker.add(chain -> chain.proceed());
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> hooker.callback(new Object[0]));
        assertEquals("original failed", thrown.getMessage());
    }

    @Test public void instanceMethodGetsItsReceiverSeparatedFromTheArguments() throws Throwable {
        Method target = String.class.getDeclaredMethod("concat", String.class);
        Hooks.Hooker hooker = hooker(target, target);
        AtomicReference<Object> seenThis = new AtomicReference<>();
        AtomicReference<Object[]> seenArgs = new AtomicReference<>();
        hooker.add(chain -> { seenThis.set(chain.getThisObject()); seenArgs.set(chain.getArgs()); return chain.proceed(); });
        assertEquals("ab", hooker.callback(new Object[]{"a", "b"}));
        assertEquals("a", seenThis.get());
        assertArrayEquals(new Object[]{"b"}, seenArgs.get());
    }

    @Test public void aCallBeforePublishWaitsForTheBackupAndThenUsesIt() throws Exception {
        Method target = method("twice", int.class);
        Hooks.Hooker hooker = new Hooks.Hooker(target);
        hooker.add(chain -> chain.proceed());
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Object> result = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                started.countDown();
                result.set(hooker.callback(new Object[]{4}));
            } catch (Throwable e) {
                result.set(e);
            }
        });
        caller.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        caller.join(200);
        assertTrue("The call must wait while there is no backup", caller.isAlive());
        hooker.publish(target);
        caller.join(5000);
        assertFalse(caller.isAlive());
        assertEquals(8, result.get());
    }

    @Test public void publishedBackupIsReturnedWithoutWaiting() throws Exception {
        Method target = method("twice", int.class);
        Hooks.Hooker hooker = hooker(target, target);
        assertSame(target, hooker.backup());
        assertSame(target, hooker.backup());
    }
}
