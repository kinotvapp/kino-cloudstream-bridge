package android.content.res;

/** JVM shim: a plugin that needs resources (requiresResources) is not supported on the desktop. */
public class Resources {
    public Resources() {}
    public Resources(AssetManager assets, android.util.DisplayMetrics metrics, Configuration config) {}
    public android.util.DisplayMetrics getDisplayMetrics() { return new android.util.DisplayMetrics(); }
    public Configuration getConfiguration() { return new Configuration(); }
    public AssetManager getAssets() { return new AssetManager(); }
    public String getString(int id) { return ""; }
    public int getIdentifier(String name, String type, String pkg) { return 0; }
}
