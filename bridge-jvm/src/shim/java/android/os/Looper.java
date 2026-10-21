package android.os;

public final class Looper {
    private static final Looper MAIN = new Looper();
    private Looper() {}
    public static Looper getMainLooper() { return MAIN; }
    /** No thread is a looper thread on the desktop. */
    public static Looper myLooper() { return null; }
}
