package android.os;

/** JVM shim: an in-process binder is alive until [kill] is called (the stdio link to Kino is down). */
public class Binder implements IBinder {
    private volatile boolean alive = true;
    @Override public boolean isBinderAlive() { return alive; }
    public void kill() { alive = false; }
}
