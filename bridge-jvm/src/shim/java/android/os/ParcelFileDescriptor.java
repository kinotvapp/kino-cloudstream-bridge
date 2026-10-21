package android.os;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;

/**
 * JVM shim for the two ways the bridge uses ParcelFileDescriptor: a duplicable byte source (an install payload) and
 * an in-process "reliable pipe" (HTTP bodies). The stdio host plays Kino's side of each pipe in this same process.
 */
public class ParcelFileDescriptor implements Closeable {
    private final byte[] bytes;
    private final Pipe pipe;
    private final boolean writeEnd;
    private volatile boolean closed;

    /** A payload source. */
    public ParcelFileDescriptor(byte[] bytes) {
        this.bytes = bytes;
        this.pipe = null;
        this.writeEnd = false;
    }

    private ParcelFileDescriptor(Pipe pipe, boolean writeEnd) {
        this.bytes = null;
        this.pipe = pipe;
        this.writeEnd = writeEnd;
    }

    /** [0] the read end, [1] the write end; the writer ends it with close() (OK) or closeWithError. */
    public static ParcelFileDescriptor[] createReliablePipe() {
        Pipe p = new Pipe();
        return new ParcelFileDescriptor[]{new ParcelFileDescriptor(p, false), new ParcelFileDescriptor(p, true)};
    }

    public ParcelFileDescriptor dup() throws IOException {
        if (closed || bytes == null) throw new IOException("cannot dup");
        return new ParcelFileDescriptor(bytes);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (pipe == null) return;
        if (writeEnd) pipe.closeWriter(null); else pipe.closeReader();
    }

    public void closeWithError(String msg) {
        if (closed) return;
        closed = true;
        if (pipe == null) return;
        if (writeEnd) pipe.closeWriter(msg == null ? "error" : msg); else pipe.closeReader();
    }

    /** Throws when the writer ended the pipe with an error (read end), after its data was read. */
    public void checkError() throws IOException {
        if (pipe != null && !writeEnd) pipe.checkError();
    }

    /** Hands the descriptor over without closing it: in one process there is nothing to transfer. */
    public void writeToParcel(Parcel dest, int flags) { }

    public static class AutoCloseInputStream extends InputStream {
        private final ParcelFileDescriptor pfd;
        private final InputStream in;

        public AutoCloseInputStream(ParcelFileDescriptor pfd) {
            this.pfd = pfd;
            this.in = pfd.pipe != null ? pfd.pipe.reader() : new ByteArrayInputStream(pfd.bytes);
        }

        @Override public int read() throws IOException { return in.read(); }
        @Override public int read(byte[] b, int off, int len) throws IOException { return in.read(b, off, len); }
        @Override public void close() { pfd.close(); }
    }

    public static class AutoCloseOutputStream extends OutputStream {
        private final ParcelFileDescriptor pfd;

        public AutoCloseOutputStream(ParcelFileDescriptor pfd) { this.pfd = pfd; }

        @Override public void write(int b) throws IOException { pfd.pipe.write(new byte[]{(byte) b}, 0, 1); }
        @Override public void write(byte[] b, int off, int len) throws IOException { pfd.pipe.write(b, off, len); }
        @Override public void close() { pfd.close(); }
    }

    private static final class Pipe {
        private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
        private int offset;
        private boolean writerClosed;
        private boolean readerClosed;
        private String error;

        synchronized void write(byte[] b, int off, int len) throws IOException {
            if (readerClosed) throw new IOException("pipe closed by reader");
            if (writerClosed) throw new IOException("pipe closed");
            if (len <= 0) return;
            byte[] copy = new byte[len];
            System.arraycopy(b, off, copy, 0, len);
            chunks.add(copy);
            notifyAll();
        }

        synchronized void closeWriter(String err) {
            if (writerClosed) return;
            writerClosed = true;
            error = err;
            notifyAll();
        }

        synchronized void closeReader() {
            readerClosed = true;
            chunks.clear();
            notifyAll();
        }

        synchronized void checkError() throws IOException {
            if (error != null) throw new IOException(error);
        }

        InputStream reader() {
            return new InputStream() {
                @Override public int read() throws IOException {
                    byte[] one = new byte[1];
                    int n = read(one, 0, 1);
                    return n < 0 ? -1 : one[0] & 0xff;
                }

                @Override public int read(byte[] b, int off, int len) throws IOException {
                    synchronized (Pipe.this) {
                        while (chunks.isEmpty()) {
                            if (writerClosed) return -1;
                            if (readerClosed) throw new IOException("closed");
                            try { Pipe.this.wait(200); } catch (InterruptedException e) { throw new IOException("interrupted"); }
                        }
                        byte[] head = chunks.peek();
                        int n = Math.min(len, head.length - offset);
                        System.arraycopy(head, offset, b, off, n);
                        offset += n;
                        if (offset == head.length) { chunks.poll(); offset = 0; }
                        return n;
                    }
                }
            };
        }
    }
}
