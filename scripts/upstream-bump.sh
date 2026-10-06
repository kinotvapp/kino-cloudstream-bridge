#!/usr/bin/env bash
# GPL-3.0 (this fork). Moves this fork onto a newer upstream CloudStream commit, on a NEW local branch in its own
# worktree, then runs every gate and prints what changed. It never touches the base branch, never pushes, never signs
# and never publishes: a person reads the report and decides (BRIDGE.md "Keeping up with upstream").
#
#   scripts/upstream-bump.sh [options] <upstream-ref>
#
#   <upstream-ref>     a full commit sha already present, or a ref fetched from upstream (e.g. master)
#   --base REF         branch holding the fork (default kino-bridge); only read
#   --branch NAME      new branch (default upstream-bump/<short sha>); must not exist
#   --dir DIR          worktree for the new branch (default <repo>/../<repo name>-bump-<short sha>); must not exist
#   --skip-build       rebase and report only (no Gradle, no oracle)
#   --report FILE      also copy the report there
#
# Environment: GRADLE_CMD (default ./gradlew, run inside the worktree, word-split: e.g. "gradle-slot.sh /path/to/worktree"),
# UPSTREAM_URL (default https://github.com/recloudstream/cloudstream.git, used when no `upstream` remote exists).
#
# What it does:
#   1. fetches <upstream-ref> without blobs (git fetch --filter=blob:none upstream <ref>);
#   2. replays every commit of the fork since its pin onto it (git rebase --onto <new> <old pin>): the settings include,
#      `compat: restore removed plugin ABI`, the README section and everything under bridge/, bridge-contract/, scripts/,
#      .github/ and BRIDGE.md. Upstream editing a workflow file the fork deleted is resolved by keeping it deleted; any
#      other conflict aborts the rebase, removes the worktree and the branch, and lists the files;
#   3. moves the pin (bridge/build.gradle.kts upstreamCommit, BRIDGE.md) and removes workflows upstream added since;
#   4. gates: script tests, :bridge:testDebugUnitTest, CompatAbiTest, :bridge:symbolCoverage, :bridge:assembleRelease
#      (which runs verifyPluginAbiRelease), unsigned (KINO_* signing variables are dropped);
#   5. report: upstream commits touching the plugin API, oracle symbols lost/gained against the allowlist, the compat
#      targets' primary constructors old vs new, version catalog changes, upstream edits outside the named exceptions.
# Exit: 0 every gate passed; 1 a gate failed (branch kept for a person to fix); 2 usage or refused input; 3 conflict.
set -euo pipefail

die() { echo "upstream-bump: $2" >&2; exit "$1"; }
usage() { sed -n '2,/^set -euo/p' "$0" | sed '$d; s/^# \{0,1\}//' >&2; exit 2; }

BASE=kino-bridge; BRANCH=""; DIR=""; SKIP_BUILD=0; REPORT_COPY=""; REF=""
while [ $# -gt 0 ]; do
  case "$1" in
    --base) BASE="${2:?}"; shift 2 ;;
    --branch) BRANCH="${2:?}"; shift 2 ;;
    --dir) DIR="${2:?}"; shift 2 ;;
    --skip-build) SKIP_BUILD=1; shift ;;
    --report) REPORT_COPY="${2:?}"; shift 2 ;;
    -h|--help) usage ;;
    -*) die 2 "unknown option $1" ;;
    *) [ -z "$REF" ] || die 2 "one upstream ref only"; REF="$1"; shift ;;
  esac
done
[ -n "$REF" ] || usage

SCRIPTS="$(cd "$(dirname "$0")" && pwd)"
PY="python3 $SCRIPTS/upstream_bump.py"
REPO="$(git rev-parse --show-toplevel)"
UPSTREAM_URL="${UPSTREAM_URL:-https://github.com/recloudstream/cloudstream.git}"
g() { git -C "$REPO" "$@"; }

g rev-parse -q --verify "refs/heads/$BASE" >/dev/null || die 2 "no local branch $BASE"
OLD_PIN="$(g show "$BASE:bridge/build.gradle.kts" | $PY pin /dev/stdin)" || die 2 "no pin in $BASE:bridge/build.gradle.kts"
g cat-file -e "$OLD_PIN^{commit}" 2>/dev/null || die 2 "pinned commit $OLD_PIN is not in this repository"

# ---- 1. the new upstream commit ----
if printf '%s' "$REF" | grep -Eq '^[0-9a-f]{40}$' && g cat-file -e "$REF^{commit}" 2>/dev/null; then
  NEW="$REF"
else
  g remote get-url upstream >/dev/null 2>&1 || g remote add upstream "$UPSTREAM_URL"
  g fetch -q --no-tags --filter=blob:none upstream "$REF" || die 2 "could not fetch $REF from upstream"
  NEW="$(g rev-parse FETCH_HEAD)"
fi
NEW="$(g rev-parse "$NEW^{commit}")"
g merge-base --is-ancestor "$OLD_PIN" "$NEW" || die 2 "$NEW does not descend from the pin $OLD_PIN (a downgrade or another line)"
SHORT="$(g rev-parse --short=9 "$NEW")"
BRANCH="${BRANCH:-upstream-bump/$SHORT}"
[ "$BRANCH" != "$BASE" ] || die 2 "the new branch cannot be the base branch"
g rev-parse -q --verify "refs/heads/$BRANCH" >/dev/null && die 2 "branch $BRANCH exists; delete it or pass --branch"
DIR="${DIR:-$(dirname "$REPO")/$(basename "$REPO")-bump-$SHORT}"
[ ! -e "$DIR" ] || die 2 "$DIR exists; remove it or pass --dir"

echo "upstream-bump: $BASE (pin ${OLD_PIN:0:9}) -> upstream $SHORT on $BRANCH in $DIR" >&2
g worktree add -q -b "$BRANCH" "$DIR" "$BASE"
[ -f "$REPO/local.properties" ] && cp "$REPO/local.properties" "$DIR/local.properties"
w() { git -C "$DIR" "$@"; }
cleanup_branch() { git -C "$REPO" worktree remove --force "$DIR" || true; git -C "$REPO" branch -q -D "$BRANCH" || true; }

# ---- 2. replay the fork's commits ----
export GIT_EDITOR=true
if ! w rebase -q --onto "$NEW" "$OLD_PIN" >/dev/null 2>&1; then
  while :; do
    bad=""; resolved=0
    for p in $(w diff --name-only --diff-filter=U); do
      st="$(w status --porcelain -- "$p" | cut -c1-2)"
      # UD: the fork's commit deletes a file upstream modified. Only upstream workflows are deleted on purpose.
      case "$p" in
        .github/workflows/*) if [ "$st" = "UD" ]; then w rm -q -- "$p"; resolved=1; continue; fi ;;
      esac
      bad="$bad $p($st)"
    done
    if [ -n "$bad" ] || [ "$resolved" = 0 ]; then
      stopped="$(w log -1 --format='%h %s' REBASE_HEAD 2>/dev/null || true)"
      w rebase --abort || true
      cleanup_branch
      die 3 "conflict replaying ${stopped:-a commit} onto $SHORT:${bad:- (no unmerged path; see git status)}. Nothing was changed."
    fi
    w rebase --continue >/dev/null 2>&1 && break
  done
fi

# ---- 3. the pin, and upstream's new workflows ----
if [ "$NEW" != "$OLD_PIN" ]; then
  $PY set-pin "$DIR/bridge/build.gradle.kts" "$NEW"
  sed -i.bak "s/$OLD_PIN/$NEW/g" "$DIR/BRIDGE.md" && rm -f "$DIR/BRIDGE.md.bak"
  w add bridge/build.gradle.kts BRIDGE.md
  w commit -q -m "Bridge: pin upstream $SHORT" -m "Rebased by scripts/upstream-bump.sh from ${OLD_PIN:0:9}."
fi
FOREIGN=""
for f in $(w ls-tree --name-only HEAD .github/workflows/); do
  case "$(basename "$f")" in kino-ci.yml|upstream-watch.yml) ;; *.yml|*.yaml) FOREIGN="$FOREIGN,$(basename "$f")"; w rm -q -- "$f" ;; esac
done
FOREIGN="${FOREIGN#,}"
if [ -n "$FOREIGN" ]; then
  w commit -q -m "Bridge: drop upstream workflows added since ${OLD_PIN:0:9} ($FOREIGN)" \
    -m "They run with upstream's secrets and repositories; this fork runs only kino-ci.yml and upstream-watch.yml."
fi

# ---- 4. gates ----
OUT="$DIR/build/upstream-bump"; mkdir -p "$OUT"
GATES="$OUT/gates.tsv"; : > "$GATES"
gate() { # gate <name> <command...>: runs it in the worktree, logs to $OUT, records PASS/FAIL
  local name="$1"; shift
  local log="$OUT/$(printf '%s' "$name" | tr -c 'A-Za-z0-9' '_').log"
  echo "upstream-bump: gate: $name" >&2
  if (cd "$DIR" && "$@") >"$log" 2>&1; then printf '%s\tPASS\n' "$name" >>"$GATES"; else printf '%s\tFAIL (%s)\n' "$name" "${log#$DIR/}" >>"$GATES"; fi
}
compat_ok() { # CompatAbiTest ran and passed in the unit-test run
  local x="$DIR/bridge/build/test-results/testDebugUnitTest/TEST-com.kino.csbridge.compat.CompatAbiTest.xml"
  [ -f "$x" ] && grep -Eq 'tests="[1-9][0-9]*"' "$x" && grep -q 'failures="0"' "$x" && grep -q 'errors="0"' "$x"
}
MISSING=""
if [ "$SKIP_BUILD" = 0 ]; then
  read -r -a G <<< "${GRADLE_CMD:-./gradlew}"
  unsigned() { env -u KINO_KEYSTORE_PATH -u KINO_KEYSTORE_PASSWORD -u KINO_KEY_ALIAS -u KINO_KEY_PASSWORD "$@"; }
  gate "script tests" sh -c 'python3 scripts/test_symbol_coverage.py && python3 scripts/test_upstream_bump.py'
  gate ":bridge:testDebugUnitTest" unsigned "${G[@]}" :bridge:testDebugUnitTest
  gate "CompatAbiTest" compat_ok
  gate ":bridge:symbolCoverage" unsigned "${G[@]}" :bridge:symbolCoverage
  gate ":bridge:assembleRelease (verifyPluginAbiRelease)" sh -c \
    'env -u KINO_KEYSTORE_PATH -u KINO_KEYSTORE_PASSWORD -u KINO_KEY_ALIAS -u KINO_KEY_PASSWORD "$@" :bridge:assembleRelease && test -s bridge/build/verifyPluginAbi/release.txt' \
    _ "${G[@]}"
  CP="$DIR/bridge/build/symbol-coverage/debug-classpath.txt"
  if [ -s "$CP" ]; then
    MISSING="$OUT/oracle-missing.txt"
    (cd "$DIR" && python3 scripts/symbol-coverage.py --symbols bridge/src/test/resources/cs3-symbols.tsv --classpath-file "$CP") >"$MISSING" 2>&1 || true
  fi
fi

# ---- 5. report ----
REPORT="$OUT/report.md"
rc=0
$PY report --repo "$DIR" --base "$BASE" --old-pin "$OLD_PIN" --new-pin "$NEW" --branch "$BRANCH" \
  ${MISSING:+--missing "$MISSING"} --gates "$GATES" ${FOREIGN:+--foreign "$FOREIGN"} >"$REPORT" || rc=1
cat "$REPORT"
[ -z "$REPORT_COPY" ] || cp "$REPORT" "$REPORT_COPY"
grep -q $'\tFAIL' "$GATES" && rc=1
if [ "$NEW" = "$OLD_PIN" ]; then
  [ "$(w rev-parse HEAD^{tree})" = "$(g rev-parse "$BASE^{tree}")" ] \
    && echo "upstream-bump: same pin: $BRANCH has exactly $BASE's tree" >&2 \
    || { echo "upstream-bump: same pin but $BRANCH's tree differs from $BASE" >&2; rc=1; }
fi
echo "upstream-bump: report $REPORT; branch $BRANCH (worktree $DIR). Drop it with: git worktree remove $DIR && git branch -D $BRANCH" >&2
exit "$rc"
