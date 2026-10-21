package android.content;

import android.content.res.AssetManager;
import android.content.res.Resources;
import java.io.File;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JVM shim for the part of android.content.Context that CloudStream plugins and the bridge use. The "app" is one
 * folder ({@link #setRoot}); each SharedPreferences name is a file under it.
 */
public class Context {
    public static final int MODE_PRIVATE = 0;
    private static volatile File root = new File(System.getProperty("java.io.tmpdir"), "kino-bridge");
    private static final ConcurrentHashMap<String, SharedPreferences> prefs = new ConcurrentHashMap<>();
    private static final Resources resources = new Resources();

    public static void setRoot(File dir) {
        root = dir;
        prefs.clear();
    }

    public Context getApplicationContext() { return this; }

    public File getFilesDir() {
        File d = new File(root, "files");
        d.mkdirs();
        return d;
    }

    public File getCacheDir() {
        File d = new File(root, "cache");
        d.mkdirs();
        return d;
    }

    public File getDataDir() {
        root.mkdirs();
        return root;
    }

    public SharedPreferences getSharedPreferences(String name, int mode) {
        return prefs.computeIfAbsent(name, n ->
            new FileSharedPreferences(new File(new File(root, "prefs"), n.replaceAll("[^A-Za-z0-9._-]", "_") + ".txt")));
    }

    public Resources getResources() { return resources; }
    public AssetManager getAssets() { return resources.getAssets(); }
    public String getPackageName() { return "com.kino.csbridge"; }
    public String getString(int id) { return ""; }
    public Object getSystemService(String name) { return null; }
    public ClassLoader getClassLoader() { return Context.class.getClassLoader(); }
}
