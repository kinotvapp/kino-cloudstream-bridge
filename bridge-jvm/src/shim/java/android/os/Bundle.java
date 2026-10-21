package android.os;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** JVM shim: a typed map. Plugins use it for fragment arguments and intent extras. */
public class Bundle {
    private final Map<String, Object> map = new LinkedHashMap<>();

    public Bundle() {}
    public Bundle(Bundle other) { if (other != null) map.putAll(other.map); }

    public int size() { return map.size(); }
    public boolean isEmpty() { return map.isEmpty(); }
    public boolean containsKey(String k) { return map.containsKey(k); }
    public Set<String> keySet() { return map.keySet(); }
    public Object get(String k) { return map.get(k); }
    public void remove(String k) { map.remove(k); }
    public void clear() { map.clear(); }
    public void putAll(Bundle b) { if (b != null) map.putAll(b.map); }

    public void putString(String k, String v) { map.put(k, v); }
    public void putInt(String k, int v) { map.put(k, v); }
    public void putLong(String k, long v) { map.put(k, v); }
    public void putBoolean(String k, boolean v) { map.put(k, v); }
    public void putFloat(String k, float v) { map.put(k, v); }
    public void putDouble(String k, double v) { map.put(k, v); }
    public void putStringArray(String k, String[] v) { map.put(k, v); }
    public void putStringArrayList(String k, java.util.ArrayList<String> v) { map.put(k, v); }
    public void putBundle(String k, Bundle v) { map.put(k, v); }
    public void putCharSequence(String k, CharSequence v) { map.put(k, v); }
    public void putSerializable(String k, java.io.Serializable v) { map.put(k, v); }

    public String getString(String k) { Object v = map.get(k); return v instanceof String ? (String) v : null; }
    public String getString(String k, String d) { String v = getString(k); return v == null ? d : v; }
    public int getInt(String k) { return getInt(k, 0); }
    public int getInt(String k, int d) { Object v = map.get(k); return v instanceof Integer ? (Integer) v : d; }
    public long getLong(String k) { return getLong(k, 0L); }
    public long getLong(String k, long d) { Object v = map.get(k); return v instanceof Long ? (Long) v : d; }
    public boolean getBoolean(String k) { return getBoolean(k, false); }
    public boolean getBoolean(String k, boolean d) { Object v = map.get(k); return v instanceof Boolean ? (Boolean) v : d; }
    public float getFloat(String k, float d) { Object v = map.get(k); return v instanceof Float ? (Float) v : d; }
    public double getDouble(String k, double d) { Object v = map.get(k); return v instanceof Double ? (Double) v : d; }
    public String[] getStringArray(String k) { Object v = map.get(k); return v instanceof String[] ? (String[]) v : null; }
    public Bundle getBundle(String k) { Object v = map.get(k); return v instanceof Bundle ? (Bundle) v : null; }
    public CharSequence getCharSequence(String k) { Object v = map.get(k); return v instanceof CharSequence ? (CharSequence) v : null; }
    public java.io.Serializable getSerializable(String k) { Object v = map.get(k); return v instanceof java.io.Serializable ? (java.io.Serializable) v : null; }
}
