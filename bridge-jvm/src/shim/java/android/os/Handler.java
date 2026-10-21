package android.os;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** JVM shim: runnables and messages run on {@link Looper}'s thread, after their delay, in order. */
public class Handler {
    public interface Callback { boolean handleMessage(Message msg); }

    private final Looper looper;
    private final Callback callback;
    private final List<Pending> pending = new ArrayList<>();

    private static final class Pending {
        final Object token;      // the Runnable, or the Message
        final Object tag;        // the optional token given to postAtTime / obj of a message
        final ScheduledFuture<?> future;
        Pending(Object token, Object tag, ScheduledFuture<?> future) { this.token = token; this.tag = tag; this.future = future; }
    }

    public Handler() { this(Looper.getMainLooper(), null); }
    public Handler(Looper looper) { this(looper, null); }
    public Handler(Callback callback) { this(Looper.getMainLooper(), callback); }

    public Handler(Looper looper, Callback callback) {
        this.looper = looper == null ? Looper.getMainLooper() : looper;
        this.callback = callback;
    }

    public Looper getLooper() { return looper; }

    /** Messages that arrive here when no Callback took them. */
    public void handleMessage(Message msg) { }

    public void dispatchMessage(Message msg) {
        if (msg.callback != null) { msg.callback.run(); return; }
        if (callback != null && callback.handleMessage(msg)) return;
        handleMessage(msg);
    }

    private boolean schedule(Object token, Object tag, Runnable run, long delayMs) {
        final Pending[] self = new Pending[1];
        ScheduledFuture<?> f = looper.executor.schedule(() -> {
            synchronized (pending) { pending.remove(self[0]); }
            try { run.run(); } catch (Throwable t) { System.err.println("Handler: " + t); }
        }, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
        synchronized (pending) {
            self[0] = new Pending(token, tag, f);
            if (!f.isDone()) pending.add(self[0]);
        }
        return true;
    }

    public boolean post(Runnable r) { return schedule(r, null, r, 0); }
    public boolean postDelayed(Runnable r, long ms) { return schedule(r, null, r, ms); }
    public boolean postAtTime(Runnable r, long uptimeMillis) { return schedule(r, null, r, uptimeMillis - SystemClock.uptimeMillis()); }
    public boolean postAtTime(Runnable r, Object token, long uptimeMillis) { return schedule(r, token, r, uptimeMillis - SystemClock.uptimeMillis()); }
    public boolean postAtFrontOfQueue(Runnable r) { return post(r); }

    public boolean sendMessage(Message m) { return sendMessageDelayed(m, 0); }
    public boolean sendEmptyMessage(int what) { return sendMessageDelayed(Message.obtain(this, what), 0); }
    public boolean sendEmptyMessageDelayed(int what, long ms) { return sendMessageDelayed(Message.obtain(this, what), ms); }

    public boolean sendMessageDelayed(Message m, long ms) {
        m.target = this;
        return schedule(m, m.obj, () -> dispatchMessage(m), ms);
    }

    public Message obtainMessage() { return Message.obtain(this); }
    public Message obtainMessage(int what) { return Message.obtain(this, what); }
    public Message obtainMessage(int what, Object obj) { return Message.obtain(this, what, obj); }

    public void removeCallbacks(Runnable r) { remove(r, null, false); }
    public void removeCallbacks(Runnable r, Object token) { remove(r, token, false); }
    public void removeMessages(int what) {
        synchronized (pending) {
            for (Pending p : new ArrayList<>(pending)) {
                if (p.token instanceof Message && ((Message) p.token).what == what) { p.future.cancel(false); pending.remove(p); }
            }
        }
    }
    public void removeCallbacksAndMessages(Object token) { remove(null, token, true); }

    private void remove(Runnable r, Object token, boolean any) {
        synchronized (pending) {
            for (Pending p : new ArrayList<>(pending)) {
                boolean match = any ? (token == null || token == p.tag) : (p.token == r && (token == null || token == p.tag));
                if (match) { p.future.cancel(false); pending.remove(p); }
            }
        }
    }

    public boolean hasCallbacks(Runnable r) {
        synchronized (pending) {
            for (Pending p : pending) if (p.token == r) return true;
        }
        return false;
    }

    public boolean hasMessages(int what) {
        synchronized (pending) {
            for (Pending p : pending) if (p.token instanceof Message && ((Message) p.token).what == what) return true;
        }
        return false;
    }
}
