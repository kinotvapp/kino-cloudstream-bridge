package androidx.fragment.app;

import android.content.Context;
public class Fragment {
    public Context getContext() { return null; }
    public Context requireContext() { throw new IllegalStateException("no UI on the desktop"); }
}
