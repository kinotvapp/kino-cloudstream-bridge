package android.util;

/** JVM shim: logs go to stderr (stdout carries the protocol). */
public final class Log {
    private Log() {}
    private static int p(String level, String tag, String msg) {
        System.err.println(level + "/" + tag + ": " + msg);
        return 0;
    }
    public static int v(String t, String m) { return p("V", t, m); }
    public static int d(String t, String m) { return p("D", t, m); }
    public static int i(String t, String m) { return p("I", t, m); }
    public static int w(String t, String m) { return p("W", t, m); }
    public static int w(String t, Throwable e) { return p("W", t, String.valueOf(e)); }
    public static int w(String t, String m, Throwable e) { return p("W", t, m + " " + e); }
    public static int e(String t, String m) { return p("E", t, m); }
    public static int e(String t, String m, Throwable e) { return p("E", t, m + " " + e); }
    public static String getStackTraceString(Throwable t) {
        java.io.StringWriter w = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(w));
        return w.toString();
    }
}
