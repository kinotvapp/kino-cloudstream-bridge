# Kino CloudStream bridge

This repository is a fork of [recloudstream/cloudstream](https://github.com/recloudstream/cloudstream),
pinned at upstream commit `4e2516d56ecd42c22c52e461980625fe3f3d559a` (branch `kino-bridge`).

## What the fork adds

A headless Android application, `:bridge` (`com.kino.csbridge`), that hosts the CloudStream plugin
runtime for Kino. It has no UI and holds **no network permission**: every request a plugin makes
goes through Kino over IPC. The IPC contract lives in `bridge-contract/` (copied verbatim from Kino,
never edited here).

Everything we add lives only in `bridge/`, `bridge-contract/`, `scripts/`, `.github/` and `BRIDGE.md`.
**Never edit upstream files.** The single exception is the one line `include(":bridge")` in
`settings.gradle.kts`, kept in its own commit so it rebases trivially.

## License

GPL-3.0, like upstream (see `LICENSE`). Source for any APK: this repository at the tag matching
the APK's `versionName`.

## Build

```
./gradlew :bridge:assembleDebug
./gradlew :bridge:assembleRelease   # signs with the KINO_* environment variables below
```

Release signing reads `KINO_KEYSTORE_PATH`, `KINO_KEYSTORE_PASSWORD`, `KINO_KEY_ALIAS`, `KINO_KEY_PASSWORD`.
Version: `-PbridgeVersionCode=N -PbridgeVersionName=X.Y.Z`.
