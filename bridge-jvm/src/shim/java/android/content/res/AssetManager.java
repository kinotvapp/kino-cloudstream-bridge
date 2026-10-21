package android.content.res;

/** JVM shim: a plugin's resources are its settings screens' layouts, which the desktop never inflates. */
public class AssetManager {
    public AssetManager() {}
    /** Accepted and ignored, so a plugin that declares resources still installs. */
    public int addAssetPath(String path) { return 1; }
}
