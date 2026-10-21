# bridge-jvm: the CloudStream bridge for a plain JVM

GPL-3.0, like the rest of this fork (`bridge-contract/` stays Apache-2.0).

`:bridge` is an Android app. `:bridge-jvm` runs the same CloudStream plugin runtime on a desktop JVM (Windows first), as a
**child process of Kino**: Kino starts it, talks to it over the process's stdin/stdout, and it has no network of its own.
Every request a plugin makes, and every value it stores, goes back to Kino through the host (`IHost`), exactly as on Android.

```
./gradlew :bridge-jvm:installDist        # build/install/bridge-jvm/{bin,lib}
java -XX:+UnlockDiagnosticVMOptions -XX:-BytecodeVerificationRemote -cp "lib/*" \
     com.kino.csbridge.desktop.MainKt --data-dir=<folder>
```

## How it relates to `:bridge`

Nothing is forked. The build copies, unchanged, the Android bridge's platform-free code (`ops/`, `plugins/`, `net/`, `web/`,
`prefs/`, `stubs/`) and the upstream app files listed in `bridge/app-sources.txt`, and compiles them against:

* **Android shims** (`src/shim/java`, `src/main/kotlin/androidx`): `Context` with a data folder, file-backed `SharedPreferences`,
  in-process `ParcelFileDescriptor` pipes, `Log`, empty UI shells. The desktop never draws a plugin's settings screen.
* **`dalvik.system.PathClassLoader`** (`src/main/kotlin/dalvik/system`): a `.cs3` holds Dalvik bytecode, so its `classes.dex` is
  translated once to a jar with dex2jar (Apache-2.0) and cached by the file's sha256. D8's renaming of Kotlin inline-class
  members (`constructor_impl`) is undone, and a missing Android supertype of a plugin class is defined empty on demand.
* **`StdioService` / `StdioHost`** (`src/main/kotlin/com/kino/csbridge/desktop`): the Binder service and the `IHost` proxy as
  frames (`bridge-contract/desktop/StdioFrames.kt`, `PROTOCOL.md` "Desktop transport").

Three small, additive edits live in `:bridge` (defaults leave Android unchanged): `SymbolChecker` and `PluginRegistry` take an
optional `platformOwner` (Android classes are not checked on the desktop), and the registry's index write retries after
deleting the old file (Windows will not rename over an existing one).

## Bytecode verification stays on

Translated plugin code names Android classes that do not exist on a JVM. The class loader defines each such class empty, with
its real superclass and interfaces taken from `src/main/resources/platform-hierarchy.tsv` (every public class of `android.jar`
and of the AndroidX and Material libraries plugins extend; regenerate with `tools/gen_platform_hierarchy.py <android.jar>`), so the
verifier can check assignments between them. A class the table lacks is defined with no supertypes.

The non-UI classes plugins really call are implemented for real in `src/shim/java`: `Uri`, `Base64`, `Handler`/`Looper`/`Message`,
`SystemClock`, `Bundle`, `SharedPreferences`, `webkit.CookieManager`.

## What is measured

`FakeKino` (tests) is a stand-in for Kino's desktop client; `Census` installs a folder of `.cs3` through the real process.
On 2026-10-20, with 5 official and 30 community plugins (no NSFW): **35 of 35 install**; search and load were checked by hand on
the official Internet Archive plugin and on Bollyflix, Animesalt and Coflix (all the HTTP went through the fake host).

## Not done

* `IHost.webView` answers `browser_unavailable`: no hidden page on the desktop yet, so Cloudflare-solving plugins fail the way
  they do on a TV with no WebView.
* Plugin settings screens (`openSettings`) are not shown.
* Runs with Kino's Java (17+); the distribution carries no JRE.
