# Kino CloudStream bridge

This repository is a fork of [recloudstream/cloudstream](https://github.com/recloudstream/cloudstream),
pinned at upstream commit `4e2516d56ecd42c22c52e461980625fe3f3d559a` (branch `kino-bridge`).

## What the fork adds

A headless Android application, `:bridge` (`com.kino.csbridge`), that hosts the CloudStream plugin
runtime for Kino. Its only UI is a transparent host for a plugin's own settings dialog, which only Kino can start
(`bridge-contract/PROTOCOL.md`, "Plugin settings"), and it holds **no network permission**: every request a plugin makes
goes through Kino over IPC. The IPC contract lives in `bridge-contract/`, kept identical to Kino's copy: a change
made on either side is copied verbatim to the other in the same release (the plugin settings section was written here
first).

Everything we add lives only in `bridge/`, `bridge-contract/`, `scripts/`, `.github/` and `BRIDGE.md`.
**Never edit upstream files.** The exceptions, each in its own commit so it rebases trivially (and
`scripts/upstream-bump.sh` fails on any other upstream file that differs):

- the one line `include(":bridge")` in `settings.gradle.kts`;
- the compat ABI below (`MainAPI.kt`, `ExtractorApi.kt`);
- the "Kino bridge fork" section at the top of `README.md` (upstream's text kept below it);
- upstream's GitHub workflows, deleted (`build_to_archive`, `prerelease`, `update_locales`, `generate_dokka`,
  `pull_request`, `instrumented-tests`): they use upstream's secrets, apps and repositories. Only
  `.github/workflows/kino-ci.yml` and `upstream-watch.yml` run here;
- upstream's issue forms (`.github/ISSUE_TEMPLATE/application-bug.yml`, `feature-request.yml`), deleted, and its
  `config.yml` replaced: this repository takes Kino bridge bugs only (`bridge-bug.yml`), and `config.yml` points
  CloudStream app bugs to recloudstream/cloudstream and extension bugs to their own repositories.

## Compat ABI (upstream edit)

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

GPL-3.0, like upstream (see `LICENSE`). Source for any APK: this repository at the tag `bridge-v<versionName>`
of that APK (the same name as the Kino release that carries it), pushed before the APK is published.

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

`.github/workflows/kino-ci.yml` (pushes to `kino-bridge`, pull requests, by hand): script tests, `:bridge:check` (unit tests,
`symbolCoverage`, `lintDebug`), `:bridge:assembleDebug`, and an **unsigned** `:bridge:assembleRelease` with
`verifyPluginAbiRelease`. It reads no secret and uploads no APK (test reports only, on failure). Releases are signed and
published by a person on the Mac with `:bridge:assembleRelease`, which runs `verifyPluginAbiRelease` first; take the APK
only from a successful `assembleRelease` run, never from a bare `packageRelease` (it writes the APK before the check
that is attached to it as a finalizer).

`:bridge:check` is `testDebugUnitTest`, `symbolCoverage` (with its self-test) and `lintDebug`: about 1.5 minutes warm on
the Mac. There is no `testReleaseUnitTest` in it.

## Releasing

1. On `kino-bridge`, with every gate green: `./gradlew :bridge:assembleRelease -PbridgeVersionCode=N
   -PbridgeVersionName=X.Y.Z` with the `KINO_*` signing variables set (on the Mac only).
   When the plugin settings screen or `SettingsGuard` changed since the last release, also run the manual on-device check
   in `bridge-contract/PROTOCOL.md` ("Plugin settings", "Release gate") on one physical phone; it is a release gate (the
   unit tests cannot run the real main-loop guard). It includes: no plugin window remains over Kino and Kino takes
   touches immediately.
2. Tag that commit `bridge-vX.Y.Z` and push the tag (`git push origin bridge-vX.Y.Z`) **before** publishing the APK:
   the tag is the GPL source offer, and Kino's release for the APK uses the same name.
3. Publish through Kino (`scripts/publish-release-github.sh --bridge <apk>` in the Kino repository).

## Publishing this repo

- Create it as a standalone repository on the kinotvapp account, **not** with GitHub's Fork button: on forks, Issues
  and scheduled workflows are off by default, so the watcher's issue and its schedule would silently do nothing. If a
  fork is unavoidable, enable both by hand.
- Push only `kino-bridge` and the `bridge-v*` tags. Never push `master`, `upstream/*` or the `pre-release` tag: upstream's
  workflows still exist on those commits, and a push to `master` (or a PR against it) would run them.
- Set `kino-bridge` as the default branch: scheduled workflows only run from the default branch, and the watcher takes
  it as the base of the replay.
- GitHub pauses a public repository's scheduled workflows after 60 days without activity: check now and then that
  "Upstream watch" is still enabled (Actions tab), and re-enable it if not.

## Keeping up with upstream

`scripts/upstream-bump.sh <upstream-ref>` fetches the ref without blobs and replays every commit of this fork since the
pin onto it, on a **new local branch** in its own worktree (default `upstream-bump/<short sha>`). It never pushes and
never changes an existing branch or remote: it only adds an `upstream` fetch remote when there is none (fetching
updates `refs/remotes/upstream/*`), and it forces `rebase.updateRefs` and `rebase.autoStash` off so `kino-bridge` cannot
move. It refuses a ref unless the merge base of `kino-bridge` and the ref is exactly the pin. It moves the pin
(`upstreamCommit` in `bridge/build.gradle.kts`, the line at the top of this file) and deletes workflows upstream added
since, in commits of its own that skip hooks and signing. Then it runs the gates: the script tests, `:bridge:check`,
`CompatAbiTest` (read from the unit-test results) and an unsigned `:bridge:assembleRelease` (with
`verifyPluginAbiRelease`).

Its report lists the Kotlin version (always), upstream commits touching watched paths (as code: no mentions, no links),
oracle symbols lost (missing now and not allowlisted: restore through compat or add allowlist rows) and gained
(allowlist rows to delete), each compat target's primary constructor old vs new, version catalog changes, and any
upstream file that differs outside the exceptions above. An oracle run without a consistent summary is reported as
FAILED, never as "nothing missing". A conflict aborts and leaves nothing; upstream edits to a workflow or issue form we
deleted stay deleted, and our `config.yml` wins.

Exit codes:

- 0: every gate passed and nothing needs a person;
- 1: a gate failed, or the report needs a person (a CHANGED compat constructor, whose hidden constructors may now
  delegate wrongly even if everything compiles; an unexpected upstream edit; a failed oracle). The branch is kept;
- 2: refused input;
- 3: conflict;
- 4: internal error before the gates (branch and worktree removed).

`GRADLE_CMD` replaces `./gradlew` (e.g. a wrapper that limits concurrent builds). Run against the current pin it
reproduces `kino-bridge`'s tree exactly and reports `Changes: 0`.

The plugin sample is not re-measured here. `bridge/src/test/resources/cs3-symbols.tsv` is refreshed by hand from Kino's
private research folder, and only aggregated counts are committed. An upstream bump changes what the bridge exports,
which the oracle checks against the new classpath. Re-measuring plugins tracks the plugin ecosystem, has nothing to do
with upstream, and would download third-party plugins and publish plugin-repository URLs in a public repository.

`.github/workflows/upstream-watch.yml` runs it **weekly** (and by hand) in GitHub Actions. It watches upstream commits
touching `library/`, a file in `bridge/app-sources.txt`, `gradle/`, the root `build.gradle.kts` / `settings.gradle.kts`,
`README.md` or `.github/ISSUE_TEMPLATE/`. It reports each newest watched commit once (open or closed issue), in one
`[upstream-watch]` issue that later reports comment on while it is open. It uses only the default `GITHUB_TOKEN`
(issues: write) and never pushes.

A person decides to bump. The bump branch is a rebased copy of the whole fork, so it **replaces** `kino-bridge`; never
merge it in, because that would double the history and the next bump would count two compat commits.

1. Review the branch and fix what the report asks, as new commits on it.
2. Make sure the current release is tagged (`bridge-v<versionName>`, see "Releasing") so its sources stay reachable.
3. Remove the bump worktree (`git worktree remove <path>`), then, in the worktree that has `kino-bridge` checked out,
   `git reset --hard upstream-bump/<sha>` (`git branch -f` refuses a checked-out branch).
4. `git push --force-with-lease origin kino-bridge`.
5. Bump `bridgeVersionCode`, then release as above.

The public `kino-bridge` is rewritten on every bump; the `bridge-v*` tags are what stays.

Tests of the script's parsing, report and git replay (on throwaway repositories): `python3 scripts/test_upstream_bump.py`.
