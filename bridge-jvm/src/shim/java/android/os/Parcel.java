package android.os;

/** JVM shim: only obtain/recycle, so the bridge's silent-release code path runs unchanged. */
public final class Parcel {
    private Parcel() {}
    public static Parcel obtain() { return new Parcel(); }
    public void recycle() { }
}
