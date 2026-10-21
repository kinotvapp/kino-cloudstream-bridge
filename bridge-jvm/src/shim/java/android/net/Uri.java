package android.net;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** JVM shim: an immutable URI split the RFC 3986 way, with the accessors and the builder CloudStream plugins use. */
public class Uri {
    private static final Pattern RFC3986 = Pattern.compile("^(([^:/?#]+):)?(//([^/?#]*))?([^?#]*)(\\?([^#]*))?(#(.*))?");
    public static final Uri EMPTY = new Uri("");

    private final String text;
    private final String scheme;
    private final String authority;
    private final String path;
    private final String query;
    private final String fragment;

    private Uri(String s) {
        text = s;
        Matcher m = RFC3986.matcher(s);
        if (m.matches()) {
            scheme = m.group(2);
            authority = m.group(4);
            path = m.group(5) == null ? "" : m.group(5);
            query = m.group(7);
            fragment = m.group(9);
        } else {
            scheme = null;
            authority = null;
            path = s;
            query = null;
            fragment = null;
        }
    }

    public static Uri parse(String s) { return new Uri(s == null ? "" : s); }

    public static String encode(String s) {
        if (s == null) return null;
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String decode(String s) {
        if (s == null) return null;
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return s;
        }
    }

    public String getScheme() { return scheme; }
    public String getAuthority() { return authority; }
    public String getEncodedAuthority() { return authority; }
    public String getPath() { return path.isEmpty() && authority == null && scheme != null ? null : decode(path); }
    public String getEncodedPath() { return path; }
    public String getQuery() { return decode(query); }
    public String getEncodedQuery() { return query; }
    public String getFragment() { return decode(fragment); }
    public boolean isAbsolute() { return scheme != null; }
    public boolean isRelative() { return scheme == null; }
    public boolean isHierarchical() { return scheme == null || authority != null || path.startsWith("/"); }

    private String hostPort() {
        if (authority == null) return null;
        String a = authority;
        int at = a.lastIndexOf('@');
        if (at >= 0) a = a.substring(at + 1);
        return a;
    }

    public String getUserInfo() {
        if (authority == null) return null;
        int at = authority.lastIndexOf('@');
        return at < 0 ? null : decode(authority.substring(0, at));
    }

    public String getHost() {
        String a = hostPort();
        if (a == null) return null;
        if (a.startsWith("[")) {
            int close = a.indexOf(']');
            return close < 0 ? a : a.substring(0, close + 1);
        }
        int colon = a.lastIndexOf(':');
        return colon < 0 ? a : a.substring(0, colon);
    }

    public int getPort() {
        String a = hostPort();
        if (a == null) return -1;
        int colon = a.lastIndexOf(':');
        if (colon < 0 || a.endsWith("]")) return -1;
        try {
            return Integer.parseInt(a.substring(colon + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public List<String> getPathSegments() {
        List<String> out = new ArrayList<>();
        for (String s : path.split("/")) if (!s.isEmpty()) out.add(decode(s));
        return Collections.unmodifiableList(out);
    }

    public String getLastPathSegment() {
        List<String> s = getPathSegments();
        return s.isEmpty() ? null : s.get(s.size() - 1);
    }

    public Set<String> getQueryParameterNames() {
        Set<String> names = new LinkedHashSet<>();
        if (query == null) return names;
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            names.add(decode(eq < 0 ? pair : pair.substring(0, eq)));
        }
        return names;
    }

    public String getQueryParameter(String key) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String k = decode(eq < 0 ? pair : pair.substring(0, eq));
            if (k.equals(key)) return eq < 0 ? "" : decode(pair.substring(eq + 1));
        }
        return null;
    }

    public List<String> getQueryParameters(String key) {
        List<String> out = new ArrayList<>();
        if (query == null) return out;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String k = decode(eq < 0 ? pair : pair.substring(0, eq));
            if (k.equals(key)) out.add(eq < 0 ? "" : decode(pair.substring(eq + 1)));
        }
        return out;
    }

    public Builder buildUpon() {
        Builder b = new Builder();
        b.scheme = scheme;
        b.authority = authority;
        b.path = path;
        b.query = query;
        b.fragment = fragment;
        return b;
    }

    @Override public String toString() { return text; }
    @Override public boolean equals(Object o) { return o instanceof Uri && ((Uri) o).text.equals(text); }
    @Override public int hashCode() { return text.hashCode(); }

    public static final class Builder {
        private String scheme;
        private String authority;
        private String path = "";
        private String query;
        private String fragment;

        public Builder() {}
        public Builder scheme(String s) { scheme = s; return this; }
        public Builder authority(String a) { authority = a; return this; }
        public Builder encodedAuthority(String a) { authority = a; return this; }
        public Builder path(String p) { path = p == null ? "" : p; return this; }
        public Builder encodedPath(String p) { path = p == null ? "" : p; return this; }
        public Builder appendPath(String segment) {
            path = (path.endsWith("/") ? path : path + "/") + encode(segment);
            return this;
        }
        public Builder appendEncodedPath(String segment) {
            path = (path.endsWith("/") ? path : path + "/") + segment;
            return this;
        }
        public Builder query(String q) { query = q; return this; }
        public Builder encodedQuery(String q) { query = q; return this; }
        public Builder clearQuery() { query = null; return this; }
        public Builder fragment(String f) { fragment = f; return this; }
        public Builder appendQueryParameter(String key, String value) {
            String pair = encode(key) + "=" + encode(value);
            query = query == null || query.isEmpty() ? pair : query + "&" + pair;
            return this;
        }

        public Uri build() {
            StringBuilder sb = new StringBuilder();
            if (scheme != null) sb.append(scheme).append(':');
            if (authority != null) sb.append("//").append(authority);
            sb.append(path);
            if (query != null) sb.append('?').append(query);
            if (fragment != null) sb.append('#').append(fragment);
            return new Uri(sb.toString());
        }

        @Override public String toString() { return build().toString(); }
    }
}
