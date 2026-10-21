package android.os;

import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * JVM shim: one background thread plays the app's main looper, so every Handler's callbacks run serialised there, like
 * Android's. Plugins use it for timers (a delayed dialog, a debounce); nothing here draws.
 */
public final class Looper {
    private static final Looper MAIN = new Looper();
    private static volatile Thread mainThread;
    final ScheduledThreadPoolExecutor executor;

    private Looper() {
        executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "android-main");
            t.setDaemon(true);
            mainThread = t;
            return t;
        });
        executor.setRemoveOnCancelPolicy(true);
    }

    public static Looper getMainLooper() { return MAIN; }

    /** The looper of the calling thread: the main one on its own thread, none elsewhere. */
    public static Looper myLooper() { return Thread.currentThread() == mainThread ? MAIN : null; }

    public Thread getThread() { return mainThread; }

    public boolean isCurrentThread() { return Thread.currentThread() == mainThread; }
}
