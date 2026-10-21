package android.util;

/** JVM shim: Android's flags over java.util.Base64. */
public final class Base64 {
    public static final int DEFAULT = 0;
    public static final int NO_PADDING = 1;
    public static final int NO_WRAP = 2;
    public static final int CRLF = 4;
    public static final int URL_SAFE = 8;
    public static final int NO_CLOSE = 16;

    private Base64() {}

    public static byte[] decode(String str, int flags) { return decode(str.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), flags); }

    public static byte[] decode(byte[] input, int flags) { return decode(input, 0, input.length, flags); }

    public static byte[] decode(byte[] input, int offset, int len, int flags) {
        StringBuilder clean = new StringBuilder(len);
        for (int i = offset; i < offset + len; i++) {
            char c = (char) (input[i] & 0xff);
            if (c != '\n' && c != '\r' && c != ' ' && c != '\t') clean.append(c);
        }
        try {
            java.util.Base64.Decoder d = (flags & URL_SAFE) != 0 ? java.util.Base64.getUrlDecoder() : java.util.Base64.getDecoder();
            return d.decode(clean.toString());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("bad base-64", e);
        }
    }

    public static String encodeToString(byte[] input, int flags) {
        return new String(encode(input, flags), java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    public static String encodeToString(byte[] input, int offset, int len, int flags) {
        return new String(encode(input, offset, len, flags), java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    public static byte[] encode(byte[] input, int flags) { return encode(input, 0, input.length, flags); }

    public static byte[] encode(byte[] input, int offset, int len, int flags) {
        byte[] slice = java.util.Arrays.copyOfRange(input, offset, offset + len);
        java.util.Base64.Encoder e = (flags & URL_SAFE) != 0 ? java.util.Base64.getUrlEncoder() : java.util.Base64.getEncoder();
        if ((flags & NO_PADDING) != 0) e = e.withoutPadding();
        byte[] out = e.encode(slice);
        if ((flags & NO_WRAP) != 0) return out;
        // Android wraps at 76 columns, with "\n" (or "\r\n" with CRLF), and ends the last line.
        String sep = (flags & CRLF) != 0 ? "\r\n" : "\n";
        StringBuilder sb = new StringBuilder();
        String s = new String(out, java.nio.charset.StandardCharsets.ISO_8859_1);
        for (int i = 0; i < s.length(); i += 76) sb.append(s, i, Math.min(s.length(), i + 76)).append(sep);
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    }
}
