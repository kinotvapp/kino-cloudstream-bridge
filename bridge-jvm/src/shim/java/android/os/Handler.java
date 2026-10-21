package android.os;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** JVM shim: posts run on one daemon thread. */
public class Handler {
    private static final ScheduledExecutorService POOL = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "android-handler");
        t.setDaemon(true);
        return t;
    });
    public Handler() {}
    public Handler(Looper looper) {}
    public boolean post(Runnable r) { POOL.execute(r); return true; }
    public boolean postDelayed(Runnable r, long ms) { POOL.schedule(r, ms, TimeUnit.MILLISECONDS); return true; }
}
