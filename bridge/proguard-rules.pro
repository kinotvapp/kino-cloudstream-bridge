# GPL-3.0 (this fork). Bridge-specific R8 rules.

# RedirectFlag / IpcInterceptor read the calling client through this public method of OkHttp's internal call class
# (allowRedirects=false and the plugin interceptors the shim runs depend on it). Task 9 probes it on a release build.
-keepclassmembers class okhttp3.internal.connection.RealCall {
    public okhttp3.OkHttpClient getClient();
}
