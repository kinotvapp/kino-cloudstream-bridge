package android.webkit;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JVM shim: the cookie jar plugins read and write through android.webkit.CookieManager, in memory for the life of the
 * bridge process. Cookies a hidden page returned are put in with {@link #seed}; the jar is not sent anywhere by itself
 * (the desktop bridge has no network: Kino sends the requests).
 */
public class CookieManager {
    private static final CookieManager INSTANCE = new CookieManager();

    private static final class Cookie {
        String name, value, domain, path = "/";
        boolean hostOnly = true, secure;
        long expiresAtMs = Long.MAX_VALUE;
    }

    private final Map<String, Cookie> jar = new LinkedHashMap<>();
    private boolean accept = true;

    protected CookieManager() {}

    public static CookieManager getInstance() { return INSTANCE; }

    public synchronized void setAcceptCookie(boolean accept) { this.accept = accept; }
    public synchronized boolean acceptCookie() { return accept; }
    public void setAcceptThirdPartyCookies(WebView view, boolean accept) { }
    public boolean acceptThirdPartyCookies(WebView view) { return true; }
    public static boolean allowFileSchemeCookies() { return false; }
    public static void setAcceptFileSchemeCookies(boolean accept) { }
    public void flush() { }

    public synchronized boolean hasCookies() { purge(); return !jar.isEmpty(); }

    public synchronized void removeAllCookies(ValueCallback<Boolean> callback) {
        boolean any = !jar.isEmpty();
        jar.clear();
        if (callback != null) callback.onReceiveValue(any);
    }

    public synchronized void removeSessionCookies(ValueCallback<Boolean> callback) {
        boolean any = jar.values().removeIf(c -> c.expiresAtMs == Long.MAX_VALUE);
        if (callback != null) callback.onReceiveValue(any);
    }

    public void removeAllCookie() { removeAllCookies(null); }
    public void removeSessionCookie() { removeSessionCookies(null); }

    public void setCookie(String url, String cookie) { setCookie(url, cookie, null); }

    public synchronized void setCookie(String url, String cookie, ValueCallback<Boolean> callback) {
        boolean stored = accept && store(url, cookie);
        if (callback != null) callback.onReceiveValue(stored);
    }

    /** Cookies for [url] as "a=b; c=d", or null when there are none. */
    public synchronized String getCookie(String url) {
        purge();
        String host = hostOf(url);
        String path = pathOf(url);
        boolean https = url != null && url.toLowerCase(Locale.ROOT).startsWith("https:");
        if (host == null) return null;
        List<String> parts = new ArrayList<>();
        for (Cookie c : jar.values()) {
            boolean domainOk = c.hostOnly ? host.equals(c.domain) : (host.equals(c.domain) || host.endsWith("." + c.domain));
            if (domainOk && path.startsWith(c.path) && (!c.secure || https)) parts.add(c.name + "=" + c.value);
        }
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    /** Puts cookies a hidden page returned into the jar (name to value, for the url's host). */
    public synchronized void seed(String url, Map<String, String> cookies) {
        for (Map.Entry<String, String> e : cookies.entrySet()) store(url, e.getKey() + "=" + e.getValue());
    }

    private boolean store(String url, String header) {
        String host = hostOf(url);
        if (host == null || header == null) return false;
        String[] attrs = header.split(";");
        int eq = attrs[0].indexOf('=');
        if (eq <= 0) return false;
        Cookie c = new Cookie();
        c.name = attrs[0].substring(0, eq).trim();
        c.value = attrs[0].substring(eq + 1).trim();
        c.domain = host;
        for (int i = 1; i < attrs.length; i++) {
            String a = attrs[i].trim();
            int e2 = a.indexOf('=');
            String k = (e2 < 0 ? a : a.substring(0, e2)).toLowerCase(Locale.ROOT);
            String v = e2 < 0 ? "" : a.substring(e2 + 1).trim();
            switch (k) {
                case "domain": c.domain = v.startsWith(".") ? v.substring(1).toLowerCase(Locale.ROOT) : v.toLowerCase(Locale.ROOT); c.hostOnly = false; break;
                case "path": if (v.startsWith("/")) c.path = v; break;
                case "secure": c.secure = true; break;
                case "max-age":
                    try { c.expiresAtMs = System.currentTimeMillis() + Long.parseLong(v) * 1000L; } catch (NumberFormatException ignored) { }
                    break;
                case "expires":
                    try { c.expiresAtMs = java.time.ZonedDateTime.parse(v, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli(); }
                    catch (RuntimeException ignored) { }
                    break;
                default: break;
            }
        }
        String key = c.domain + "\u0000" + c.path + "\u0000" + c.name;
        if (c.expiresAtMs <= System.currentTimeMillis()) jar.remove(key); else jar.put(key, c);
        return true;
    }

    private void purge() {
        long now = System.currentTimeMillis();
        jar.values().removeIf(c -> c.expiresAtMs <= now);
    }

    private static String hostOf(String url) {
        if (url == null) return null;
        try {
            String u = url.contains("://") ? url : "http://" + url;
            String h = new URI(u).getHost();
            return h == null ? null : h.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    private static String pathOf(String url) {
        try {
            String u = url.contains("://") ? url : "http://" + url;
            String p = new URI(u).getPath();
            return p == null || p.isEmpty() ? "/" : p;
        } catch (Exception e) {
            return "/";
        }
    }
}
