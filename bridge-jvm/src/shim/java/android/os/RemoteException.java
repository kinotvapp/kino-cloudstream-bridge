package android.os;

/** JVM shim: a failed call to Kino over the stdio link. */
public class RemoteException extends Exception {
    public RemoteException() { super(); }
    public RemoteException(String m) { super(m); }
}
