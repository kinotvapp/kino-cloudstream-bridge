# Kino CloudStream bridge

This repository is a fork of [recloudstream/cloudstream](https://github.com/recloudstream/cloudstream),
pinned at upstream commit `4e2516d56ecd42c22c52e461980625fe3f3d559a` (branch `kino-bridge`).

## What the fork adds

A headless Android application, `:bridge` (`com.kino.csbridge`), that hosts the CloudStream plugin
runtime for Kino. It has no UI and holds **no network permission**: every request a plugin makes
goes through Kino over IPC. The IPC contract lives in `bridge-contract/` (copied verbatim from Kino,
never edited here).

Everything we add lives only in `bridge/`, `bridge-contract/`, `scripts/`, `.github/` and `BRIDGE.md`.
**Never edit upstream files.** Two exceptions, each in its own commit so it rebases trivially:
the one line `include(":bridge")` in `settings.gradle.kts`, and the compat ABI below.

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
upstream bump, re-apply the commit and re-run `symbolCoverage` and `CompatAbiTest`.

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
