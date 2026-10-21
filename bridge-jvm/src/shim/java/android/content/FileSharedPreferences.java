package android.content;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** JVM shim: SharedPreferences in one tab-separated file per name; each line is "type TAB key TAB value". */
final class FileSharedPreferences implements SharedPreferences {
    private static final String SET_SEPARATOR = "\u0001";
    private final File file;
    private final Map<String, Object> map = new LinkedHashMap<>();

    FileSharedPreferences(File file) {
        this.file = file;
        load();
    }

    private synchronized void load() {
        if (!file.isFile()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int a = line.indexOf('\t');
                int b = a < 0 ? -1 : line.indexOf('\t', a + 1);
                if (b < 0) continue;
                String type = line.substring(0, a);
                String key = unescape(line.substring(a + 1, b));
                String val = unescape(line.substring(b + 1));
                switch (type) {
                    case "s": map.put(key, val); break;
                    case "i": map.put(key, Integer.parseInt(val)); break;
                    case "l": map.put(key, Long.parseLong(val)); break;
                    case "f": map.put(key, Float.parseFloat(val)); break;
                    case "b": map.put(key, Boolean.parseBoolean(val)); break;
                    case "S": {
                        Set<String> s = new LinkedHashSet<>();
                        if (!val.isEmpty()) for (String p : val.split(SET_SEPARATOR, -1)) s.add(p);
                        map.put(key, s);
                        break;
                    }
                    default: break;
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t");
    }

    private static String unescape(String s) {
        StringBuilder o = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                o.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
            } else {
                o.append(c);
            }
        }
        return o.toString();
    }

    private synchronized void save() {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        File tmp = new File(file.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, Object> e : map.entrySet()) {
                Object v = e.getValue();
                String type;
                String text;
                if (v instanceof String) { type = "s"; text = (String) v; }
                else if (v instanceof Integer) { type = "i"; text = v.toString(); }
                else if (v instanceof Long) { type = "l"; text = v.toString(); }
                else if (v instanceof Float) { type = "f"; text = v.toString(); }
                else if (v instanceof Boolean) { type = "b"; text = v.toString(); }
                else {
                    @SuppressWarnings("unchecked") Set<String> set = (Set<String>) v;
                    type = "S";
                    text = String.join(SET_SEPARATOR, set);
                }
                w.write(type + "\t" + escape(e.getKey()) + "\t" + escape(text) + "\n");
            }
        } catch (IOException ignored) {
            return;
        }
        if (!tmp.renameTo(file)) {
            file.delete();
            tmp.renameTo(file);
        }
    }

    @Override public synchronized Map<String, ?> getAll() { return new LinkedHashMap<>(map); }
    @Override public synchronized String getString(String k, String d) { Object v = map.get(k); return v instanceof String ? (String) v : d; }
    @SuppressWarnings("unchecked")
    @Override public synchronized Set<String> getStringSet(String k, Set<String> d) { Object v = map.get(k); return v instanceof Set ? new LinkedHashSet<>((Set<String>) v) : d; }
    @Override public synchronized int getInt(String k, int d) { Object v = map.get(k); return v instanceof Integer ? (Integer) v : d; }
    @Override public synchronized long getLong(String k, long d) { Object v = map.get(k); return v instanceof Long ? (Long) v : d; }
    @Override public synchronized float getFloat(String k, float d) { Object v = map.get(k); return v instanceof Float ? (Float) v : d; }
    @Override public synchronized boolean getBoolean(String k, boolean d) { Object v = map.get(k); return v instanceof Boolean ? (Boolean) v : d; }
    @Override public synchronized boolean contains(String k) { return map.containsKey(k); }
    @Override public Editor edit() { return new Ed(); }
    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }

    private final class Ed implements Editor {
        private final Map<String, Object> pending = new LinkedHashMap<>();
        private final Set<String> removed = new HashSet<>();
        private boolean clear;

        private Editor put(String k, Object v) { pending.put(k, v); removed.remove(k); return this; }
        @Override public Editor putString(String k, String v) { return v == null ? remove(k) : put(k, v); }
        @Override public Editor putStringSet(String k, Set<String> v) { return v == null ? remove(k) : put(k, new LinkedHashSet<>(v)); }
        @Override public Editor putInt(String k, int v) { return put(k, v); }
        @Override public Editor putLong(String k, long v) { return put(k, v); }
        @Override public Editor putFloat(String k, float v) { return put(k, v); }
        @Override public Editor putBoolean(String k, boolean v) { return put(k, v); }
        @Override public Editor remove(String k) { pending.remove(k); removed.add(k); return this; }
        @Override public Editor clear() { clear = true; pending.clear(); return this; }
        @Override public boolean commit() {
            synchronized (FileSharedPreferences.this) {
                if (clear) map.clear();
                for (String r : removed) map.remove(r);
                map.putAll(pending);
                save();
            }
            return true;
        }
        @Override public void apply() { commit(); }
    }
}
