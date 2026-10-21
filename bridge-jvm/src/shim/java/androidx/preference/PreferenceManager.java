package androidx.preference;

import android.content.Context;
import android.content.SharedPreferences;
public class PreferenceManager {
    public static SharedPreferences getDefaultSharedPreferences(Context c) {
        return c.getSharedPreferences("default_preferences", Context.MODE_PRIVATE);
    }
}
