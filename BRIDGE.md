# Kino CloudStream bridge

This repository is a fork of [recloudstream/cloudstream](https://github.com/recloudstream/cloudstream),
pinned at upstream commit `4e2516d56ecd42c22c52e461980625fe3f3d559a` (branch `kino-bridge`).

## What the fork adds

A headless Android application, `:bridge` (`com.kino.csbridge`), that hosts the CloudStream plugin
runtime for Kino. It has no UI and holds **no network permission**: every request a plugin makes
goes through Kino over IPC. The IPC contract lives in `bridge-contract/` (copied verbatim from Kino,
never edited here).

Everything we add lives only in `bridge/`, `bridge-contract/`, `scripts/`, `.github/` and `BRIDGE.md`.
**Never edit upstream files.** The exceptions, each in its own commit so it rebases trivially (and
`scripts/upstream-bump.sh` fails on any other upstream file that differs):

- the one line `include(":bridge")` in `settings.gradle.kts`;
- the compat ABI below (`MainAPI.kt`, `ExtractorApi.kt`);
- the "Kino bridge fork" section at the top of `README.md` (upstream's text kept below it);
- upstream's GitHub workflows, deleted (`build_to_archive`, `prerelease`, `update_locales`, `generate_dokka`,
  `pull_request`, `instrumented-tests`): they use upstream's secrets, apps and repositories. Only
  `.github/workflows/kino-ci.yml` and `upstream-watch.yml` run here.

## Compat ABI (the one upstream edit)

Many published extensions were compiled against older CloudStream and call constructors upstream
has since removed, so stock CloudStream at our pin refuses them too. The commit
`compat: restore removed plugin ABI` adds them back as `@Deprecated(level = HIDDEN)` secondary
constructors (binary-only: invisible to source, so new code still uses the current API), each one
delegating to the current primary:

| Class | Old shape | Mapping |
|---|---|---|
| `Episode` | 8 params, `rating: Int?` (0–100), no `runTime` | `Score.from(rating, 100)`, `runTime = null` |
| `MovieLoadResponse`, `LiveStreamLoadResponse` | 18 params, `rating: Int?` (0–10000) | `Score.fromOld(rating)`; `logoUrl`, `contentRating` null; `uniqueUrl = url` |
| `TvSeriesLoadResponse` | 21 params, `rating: Int?` (0–10000) | same as above |
| `AnimeSearchResponse` | 12 params, `dubStatus: EnumSet?`, no `score` | EnumSet passed as the set; `score = null`; plus hidden `setDubStatus(EnumSet)` |
| `ExtractorLink` | primary before `audioTracks` | `audioTracks = emptyList()` |

The old signatures come from upstream history (`8b6d0ec8c`, `91652da9`, `029b72c1`).
`bridge/src/test/.../compat/CompatAbiTest.kt` calls each one by JVM descriptor, as an extension would.
`:library`'s `abiValidation` would report these as additions; `:bridge:check` does not run it. On an
upstream bump, re-apply the commit and re-run `symbolCoverage` and `CompatAbiTest` (`scripts/upstream-bump.sh` does
both, and reports any change to the primary constructors the hidden ones delegate to).

Not restored, on purpose: extractors upstream deleted because their sites are dead (Chillx and its
subclasses, GMPlayer, Cinestart/Tomatomatela) and `APIHolder.getApis()` (now an `AtomicList`). They
stay in `bridge/symbol-allowlist.txt`; an extension that needs them is refused, as on stock CloudStream.

## License

GPL-3.0, like upstream (see `LICENSE`). Source for any APK: this repository at the tag matching
the APK's `versionName`.

Exception: `bridge-contract/` (the AIDL and wire protocol shared with Kino) is Apache-2.0, full text in
`bridge-contract/LICENSE`.

## Build

```
./gradlew :bridge:assembleDebug
./gradlew :bridge:assembleRelease   # signs with the KINO_* environment variables below
```

Release signing reads `KINO_KEYSTORE_PATH`, `KINO_KEYSTORE_PASSWORD`, `KINO_KEY_ALIAS`, `KINO_KEY_PASSWORD`.
Version: `-PbridgeVersionCode=N -PbridgeVersionName=X.Y.Z`.

Release is minified, but plugins link against this APK by name at runtime, so `bridge/proguard-rules.pro` keeps the
whole plugin-facing API (CloudStream, NiceHttp and the libraries plugins use) unrenamed and unoptimised; R8 only drops
what nothing can reach. The desugared JDK (`j$`) ships whole, as upstream ships it. `verifyPluginAbiRelease` runs before
`assembleRelease`, `bundleRelease` and `installRelease` (and after `packageRelease`) and checks the release dex: the
symbol oracle (the `com.lagradost.*` symbols measured in real plugins), `bridge/release-symbols.tsv` (the bridge's own
reflection targets and the compat constructors with their `$default` forms), and parity with the debug APK for every
public class and public/protected member of the packages plugins link against, `j$` included. It builds the debug APK
as its reference and needs `python3` on the build machine. The APK carries ARM natives only (`arm64-v8a`,
`armeabi-v7a`) and no Conscrypt: the bridge has no network, Kino does every TLS session.
`src/main/baseline-prof.txt` is a hand-written baseline profile of the cold bind/hello/first-load path; `BridgeApp`
installs it from the `:plugins` process (androidx.startup's installer runs in a process the bridge never starts).

## CI

`.github/workflows/kino-ci.yml` (pushes to `kino-bridge`, pull requests, by hand): script tests, `:bridge:testDebugUnitTest`,
`:bridge:symbolCoverage`, `:bridge:assembleDebug`, and an **unsigned** `:bridge:assembleRelease` with
`verifyPluginAbiRelease`. It reads no secret and uploads no APK (test reports only, on failure). Releases are signed and
published by a person on the Mac with `:bridge:assembleRelease`, which runs `verifyPluginAbiRelease` first; take the APK
only from a successful `assembleRelease` run, never from a bare `packageRelease` (it writes the APK before the check
that is attached to it as a finalizer).

## Keeping up with upstream

`scripts/upstream-bump.sh <upstream-ref>` fetches the ref without blobs, replays every commit of this fork since the pin
onto it on a **new local branch** in its own worktree (default `upstream-bump/<short sha>`; `kino-bridge` and every
remote are left alone), moves the pin (`upstreamCommit` in `bridge/build.gradle.kts`, the line at the top of this
file), deletes workflows upstream added since, then runs the script tests, `:bridge:testDebugUnitTest`, `CompatAbiTest`,
`:bridge:symbolCoverage` and an unsigned `:bridge:assembleRelease` (with `verifyPluginAbiRelease`). Its report lists:
upstream commits touching the plugin API, oracle symbols lost (missing now and not allowlisted: restore through compat
or add allowlist rows) and gained (allowlist rows to delete), each compat target's primary constructor old vs new, version
catalog changes, and any upstream file that differs outside the exceptions above. A conflict aborts and leaves nothing
(an upstream edit to a workflow we deleted stays deleted). Exit 0 all gates passed, 1 a gate failed, 2 refused input, 3
conflict. `GRADLE_CMD` replaces `./gradlew` (e.g. a wrapper that limits concurrent builds). Run against the current pin
it reproduces `kino-bridge`'s tree exactly and reports `Changes: 0`.

`.github/workflows/upstream-watch.yml` runs it daily (and by hand) in GitHub Actions when upstream has commits touching
`library/` or a file in `bridge/app-sources.txt`, and opens (or updates) one `[upstream-watch]` issue with the report.
It uses only the default `GITHUB_TOKEN` (issues: write) and never pushes. A person decides to bump: review the branch,
fix what the report asks, merge into `kino-bridge`, bump `bridgeVersionCode`, sign on the Mac and publish through Kino.

Tests of the script's parsing, report and git replay (on throwaway repositories): `python3 scripts/test_upstream_bump.py`.
