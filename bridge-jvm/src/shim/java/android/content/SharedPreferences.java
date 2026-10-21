package android.content;

import java.util.Map;
import java.util.Set;

public interface SharedPreferences {
    Map<String, ?> getAll();
    String getString(String key, String def);
    Set<String> getStringSet(String key, Set<String> def);
    int getInt(String key, int def);
    long getLong(String key, long def);
    float getFloat(String key, float def);
    boolean getBoolean(String key, boolean def);
    boolean contains(String key);
    Editor edit();
    void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l);
    void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l);

    interface OnSharedPreferenceChangeListener {
        void onSharedPreferenceChanged(SharedPreferences prefs, String key);
    }

    interface Editor {
        Editor putString(String key, String v);
        Editor putStringSet(String key, Set<String> v);
        Editor putInt(String key, int v);
        Editor putLong(String key, long v);
        Editor putFloat(String key, float v);
        Editor putBoolean(String key, boolean v);
        Editor remove(String key);
        Editor clear();
        boolean commit();
        void apply();
    }
}
