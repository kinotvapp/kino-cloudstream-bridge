package androidx.fragment.app;

import java.util.Collections;
import java.util.List;
public class FragmentManager {
    public List<Fragment> getFragments() { return Collections.emptyList(); }
    public FragmentTransaction beginTransaction() { return new FragmentTransaction(); }
}
