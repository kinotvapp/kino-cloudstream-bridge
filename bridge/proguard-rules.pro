# GPL-3.0 (this fork). Bridge-specific R8 rules.

# RedirectFlag / IpcInterceptor read the calling client through this public method of OkHttp's internal call class
# (allowRedirects=false and the plugin interceptors the shim runs depend on it). Task 9 probes it on a release build.
-keepclassmembers class okhttp3.internal.connection.RealCall {
    public okhttp3.OkHttpClient getClient();
}

# BridgeApp sets CloudStreamApp.context reflectively (CloudStreamContext.kt: initForBridge): the companion's private
# setter, and the backing field written when the setter is missing. Without them getKey/setKey silently do nothing.
-keepclassmembers class com.lagradost.cloudstream3.CloudStreamApp$Companion {
    private void setContext(android.content.Context);
}
-keepclassmembers class com.lagradost.cloudstream3.CloudStreamApp {
    private static java.lang.ref.WeakReference _context;
}
