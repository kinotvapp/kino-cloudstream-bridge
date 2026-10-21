package androidx.preference;

public abstract class PreferenceFragmentCompat extends androidx.fragment.app.Fragment {
    public abstract void onCreatePreferences(android.os.Bundle savedInstanceState, String rootKey);
}
