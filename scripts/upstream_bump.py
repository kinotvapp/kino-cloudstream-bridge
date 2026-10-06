#!/usr/bin/env python3
# GPL-3.0 (this fork). The pure parts of scripts/upstream-bump.sh and the upstream watcher (.github/workflows/
# upstream-watch.yml): reading and writing the pin, the oracle delta (symbols gained / lost against the allowlist),
# the primary constructors the compat commit delegates to, the upstream-edit check and the report.
# Tests: python3 scripts/test_upstream_bump.py
#
#   upstream_bump.py pin [bridge/build.gradle.kts]           print the pinned upstream commit
#   upstream_bump.py set-pin <bridge/build.gradle.kts> <sha> rewrite the pin constant in place
#   upstream_bump.py watched-commits --repo D --old A --new B one line per upstream commit in A..B touching the plugin API
#   upstream_bump.py report --repo D --base REF --old-pin A --new-pin B --branch NAME [--missing F] [--gates F]
#                           [--foreign a.yml,b.yml]   exit 1 when the replay itself is wrong (see report_ok)
import argparse
import os
import re
import subprocess
import sys

MAIN_API = 'library/src/commonMain/kotlin/com/lagradost/cloudstream3/MainAPI.kt'
EXTRACTOR_API = 'library/src/commonMain/kotlin/com/lagradost/cloudstream3/utils/ExtractorApi.kt'
# Classes whose old constructors `compat: restore removed plugin ABI` brings back (BRIDGE.md "Compat ABI"); each hidden
# constructor delegates to the class's CURRENT primary constructor, so a change there needs a person to re-check it.
COMPAT_TARGETS = {
    MAIN_API: ['Episode', 'MovieLoadResponse', 'LiveStreamLoadResponse', 'TvSeriesLoadResponse', 'AnimeSearchResponse'],
    EXTRACTOR_API: ['ExtractorLink'],
}
COMPAT_SUBJECT = 'compat: restore removed plugin ABI'
# Where this fork's own files live (BRIDGE.md): anything else that differs from upstream is an upstream edit.
OUR_PREFIXES = ('bridge/', 'bridge-contract/', 'scripts/', '.github/')
OUR_FILES = ('BRIDGE.md',)
# The upstream files the fork edits on purpose, each in its own commit (BRIDGE.md).
UPSTREAM_EDITS = ('settings.gradle.kts', 'README.md', MAIN_API, EXTRACTOR_API)
# Workflows of this fork; every other file in .github/workflows is upstream's and must not run here.
OUR_WORKFLOWS = ('kino-ci.yml', 'upstream-watch.yml')
PIN_RE = re.compile(r'^(val upstreamCommit = ")([0-9a-f]{40})(")', re.M)
SHA_RE = re.compile(r'^[0-9a-f]{40}$')


# ---- pin -----------------------------------------------------------------------------------------------------------
def read_pin(gradle_text):
    m = PIN_RE.search(gradle_text)
    if not m:
        raise ValueError('no `val upstreamCommit = "<40 hex>"` line')
    return m.group(2)


def set_pin(gradle_text, sha):
    if not SHA_RE.match(sha):
        raise ValueError(f'not a full lowercase commit sha: {sha}')
    read_pin(gradle_text)
    return PIN_RE.sub(lambda m: m.group(1) + sha + m.group(3), gradle_text, count=1)


# ---- oracle --------------------------------------------------------------------------------------------------------
def parse_missing(text):
    """MISSING rows of scripts/symbol-coverage.py's oracle check (run without --allowlist): {symbol: {kind, plugins, es}}."""
    out = {}
    for line in text.splitlines():
        f = line.split('\t')
        if len(f) >= 5 and f[0] == 'MISSING' and f[1] in ('T', 'M', 'F'):
            out[f[2]] = {'kind': f[1], 'plugins': int(f[3].split('=', 1)[1]), 'es': int(f[4].split('=', 1)[1])}
    return out


def parse_allowlist(text):
    """symbol-allowlist.txt rows, parsed as scripts/symbol-coverage.py does: {symbol: reason}."""
    out = {}
    for line in text.splitlines():
        s = line.strip()
        if not s or s.startswith('#'):
            continue
        parts = s.split(None, 1)
        out[parts[0]] = parts[1].strip() if len(parts) > 1 else ''
    return out


def parse_oracle(text):
    """(missing rows, ok): ok only when the oracle printed its `missing (not allowlisted): N` summary and N equals the
    MISSING rows parsed (so a crash that printed nothing, or half, is never read as 'nothing missing')."""
    missing = parse_missing(text)
    m = re.search(r'^missing \(not allowlisted\): (\d+)', text, re.M)
    return missing, bool(m) and int(m.group(1)) == len(missing)


def symbol_delta(allow_before, missing_after):
    """lost: oracle symbols missing now and not allowlisted before (most Spanish plugins first), as (symbol, row);
    gained: allowlist rows that resolve now (stale rows to delete)."""
    lost = sorted(((s, r) for s, r in missing_after.items() if s not in allow_before),
                  key=lambda x: (-x[1]['es'], -x[1]['plugins'], x[0]))
    gained = sorted(s for s in allow_before if s not in missing_after)
    return lost, gained


# ---- versions catalog ----------------------------------------------------------------------------------------------
def parse_versions(toml_text):
    out, section = {}, None
    for line in toml_text.splitlines():
        s = line.split('#', 1)[0].strip()
        if s.startswith('['):
            section = s.strip('[]').strip()
            continue
        m = re.match(r'^([A-Za-z0-9_.-]+)\s*=\s*"([^"]*)"$', s)
        if section == 'versions' and m:
            out[m.group(1)] = m.group(2)
    return out


def version_delta(old, new):
    return [(k, old.get(k), new.get(k)) for k in sorted(set(old) | set(new)) if old.get(k) != new.get(k)]


# ---- Kotlin primary constructors -----------------------------------------------------------------------------------
def _strip_comments(src):
    """Drops // and /* */ comments, keeping string and char literals (and the newlines) intact."""
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append(src[i:j]); i = j
        elif c in '"\'':
            j = i + 1
            while j < n and src[j] != c and src[j] != '\n':
                j += 2 if src[j] == '\\' else 1
            out.append(src[i:j + 1]); i = j + 1
        elif src.startswith('//', i):
            j = src.find('\n', i)
            i = n if j < 0 else j
        elif src.startswith('/*', i):
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
            out.append(' ')
        else:
            out.append(c); i += 1
    return ''.join(out)


def _balanced(src, i, open_c='(', close_c=')'):
    """Index just past the bracket matching the one at src[i], skipping string literals."""
    depth, n = 0, len(src)
    while i < n:
        c = src[i]
        if c in '"\'':
            j = i + 1
            while j < n and src[j] != c:
                j += 2 if src[j] == '\\' else 1
            i = j + 1
            continue
        if c == open_c:
            depth += 1
        elif c == close_c:
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    raise ValueError('unbalanced brackets')


def _split_top(s):
    parts, depth, cur, i, n = [], 0, [], 0, len(s)
    while i < n:
        c = s[i]
        if c in '"\'':
            j = i + 1
            while j < n and s[j] != c:
                j += 2 if s[j] == '\\' else 1
            cur.append(s[i:j + 1]); i = j + 1
            continue
        if s.startswith('->', i):
            cur.append('->'); i += 2
            continue
        if c in '([{<':
            depth += 1
        elif c in ')]}>':
            depth -= 1
        if c == ',' and depth == 0:
            parts.append(''.join(cur)); cur = []
        else:
            cur.append(c)
        i += 1
    parts.append(''.join(cur))
    return [p for p in (' '.join(x.split()) for x in parts) if p]


def primary_ctor_params(src, cls):
    """The primary constructor's parameters of `class <cls>` (normalised whitespace), [] when it has none, None when the
    class is not declared in src."""
    code = _strip_comments(src)
    m = re.search(r'\bclass\s+' + re.escape(cls) + r'\b(?![\w$])', code)
    if not m:
        return None
    i, n = m.end(), len(code)
    while True:
        while i < n and code[i].isspace():
            i += 1
        if i < n and code[i] == '<':  # type parameters
            i = _balanced(code, i, '<', '>')
            continue
        if i < n and code[i] == '@':
            a = re.match(r'@[\w.:]+', code[i:])
            i += a.end()
            if i < n and code[i] == '(':
                i = _balanced(code, i)
            continue
        w = re.match(r'(constructor|private|internal|protected|public)\b', code[i:])
        if w:
            i += w.end()
            continue
        break
    if i >= n or code[i] != '(':
        return []
    end = _balanced(code, i)
    return _split_top(code[i + 1:end - 1])


def param_name(param):
    s = param
    while True:
        a = re.match(r'\s*@[\w.:]+', s)
        if not a:
            break
        s = s[a.end():]
        if s.startswith('('):
            s = s[_balanced(s, 0):]
    head = s.split(':', 1)[0].split()
    return head[-1].strip('`') if head else ''


def ctor_delta(old, new):
    oldn = {param_name(p): p for p in old}
    newn = {param_name(p): p for p in new}
    common_old = [param_name(p) for p in old if param_name(p) in newn]
    common_new = [param_name(p) for p in new if param_name(p) in oldn]
    return {
        'same': list(old) == list(new),
        'removed': [k for k in oldn if k not in newn],
        'added': [p for k, p in newn.items() if k not in oldn],
        'changed': [(oldn[k], newn[k]) for k in oldn if k in newn and oldn[k] != newn[k]],
        'reordered': common_old != common_new,
    }


# ---- upstream edits ------------------------------------------------------------------------------------------------
def parse_name_status(text):
    """`git diff --name-status -M` lines as (status letter, path); a rename is its delete plus its add."""
    out = []
    for line in text.splitlines():
        f = line.split('\t')
        if len(f) < 2 or not f[0]:
            continue
        if f[0][0] in 'RC' and len(f) >= 3:
            if f[0][0] == 'R':
                out.append(('D', f[1]))
            out.append(('A', f[2]))
        else:
            out.append((f[0][0], f[1]))
    return out


def _ours(path):
    return path.startswith(OUR_PREFIXES) or path in OUR_FILES


def unexpected_edits(changes):
    """Changes against upstream that are neither this fork's files nor one of its named upstream edits."""
    return [f'{st} {p}' for st, p in changes if not _ours(p) and p not in UPSTREAM_EDITS]


def settings_edit_ok(diff_text):
    """settings.gradle.kts differs from upstream by exactly the `include(":bridge")` line (and blank lines)."""
    plus, minus = [], []
    for line in diff_text.splitlines():
        if line.startswith(('+++', '---')):
            continue
        if line.startswith('+'):
            plus.append(line[1:].strip())
        elif line.startswith('-'):
            minus.append(line[1:].strip())
    return not minus and [p for p in plus if p] == ['include(":bridge")']


def foreign_workflows(names):
    return [n for n in names if n.endswith(('.yml', '.yaml')) and n not in OUR_WORKFLOWS]


# Upstream paths that matter besides the plugin API: the toolchain and dependency versions (a Kotlin bump can change
# plugin binary compatibility), the root build, and the upstream files the fork edits (a change there makes the next
# replay conflict). The compat files are under library/ already.
WATCHED_EXTRA = ['gradle/', 'settings.gradle.kts', 'build.gradle.kts', 'README.md', '.github/ISSUE_TEMPLATE/']


def watched_paths(app_sources_text):
    """What the watcher follows upstream: all of :library (extractors included), the :app files :bridge compiles, and
    WATCHED_EXTRA."""
    files = [s.strip() for s in app_sources_text.splitlines()]
    return ['library/'] + ['app/src/main/java/' + s for s in files if s and not s.startswith('#')] + WATCHED_EXTRA


def kotlin_version(versions):
    return versions.get('kotlinGradlePlugin') or versions.get('kotlin')


def commit_line(c):
    """`<sha> <subject>` as a markdown list item that cannot mention anyone or link an issue of this repository."""
    sha, _, subject = c.partition(' ')
    return f"- {sha} `{subject.replace('`', chr(39))}`"


# ---- report --------------------------------------------------------------------------------------------------------
def render_report(*, old_pin, new_pin, branch, commits, lost, gained, versions, compat, unexpected, settings_ok,
                  compat_commits, foreign, gates, oracle_ran, oracle_ok=True, kotlin=(None, None)):
    compat_changed = [c for c, d in compat.items() if d is None or not d['same']]
    changes = (old_pin != new_pin) + len(lost) + len(gained) + len(versions) + len(compat_changed) + len(unexpected) \
        + (not settings_ok) + (compat_commits != 1) + len(foreign)
    L = [f'# Upstream bump {old_pin[:9]} -> {new_pin[:9]}', '',
         f'Branch: `{branch}` (local; nothing pushed, `kino-bridge` untouched).', '',
         f'Changes: {changes}', '',
         f'Kotlin: {kotlin[0]} -> {kotlin[1]}' if kotlin[0] != kotlin[1] else f'Kotlin: {kotlin[1]} (unchanged)', '']
    L += ['## Gates', '', '| gate | result |', '|---|---|']
    L += [f'| {g} | {r} |' for g, r in gates] or ['| (none run) | - |']
    L += ['', f'## Upstream commits touching the plugin API, the build or a fork-edited file ({len(commits)})', '']
    L += [commit_line(c) for c in commits[:200]] or ['- none']
    if len(commits) > 200:
        L.append(f'- ... and {len(commits) - 200} more')
    L += ['', '## Symbol oracle', '']
    if not oracle_ran:
        L.append('Oracle not run (build skipped or failed before the classpath was written).')
    elif not oracle_ok:
        L.append('Oracle FAILED: no summary line, or its count disagrees with the MISSING rows (see oracle-missing.txt '
                 'and oracle-missing.err); lost/gained unknown.')
    else:
        L.append(f'Lost (missing now, not allowlisted; restore through compat or add allowlist rows): {len(lost)}')
        L += [f'- `{s}` [{r["kind"]}] plugins={r["plugins"]} es={r["es"]}' for s, r in lost]
        L.append(f'Gained (allowlist rows that resolve now; delete them): {len(gained)}')
        L += [f'- `{s}`' for s in gained]
    L += ['', '## Compat targets (primary constructors the hidden compat constructors delegate to)', '']
    for c, d in compat.items():
        if d is None:
            L.append(f'- {c}: NOT FOUND at the new pin (compat commit needs rework)')
        elif d['same']:
            L.append(f'- {c}: unchanged')
        else:
            L.append(f'- {c}: CHANGED' + (' (parameters reordered)' if d.get('reordered') else ''))
            L += [f'  - removed `{x}`' for x in d['removed']]
            L += [f'  - added `{x}`' for x in d['added']]
            L += [f'  - `{a}` -> `{b}`' for a, b in d['changed']]
    L.append(f'- `{COMPAT_SUBJECT}` commits on the branch: {compat_commits}' + ('' if compat_commits == 1 else ' (expected 1)'))
    L += ['', '## Version catalog changes', '']
    L += [f'- {k}: {a} -> {b}' for k, a, b in versions] or ['- none']
    L += ['', '## Upstream edits', '']
    L.append('- settings.gradle.kts: ' + ('only `include(":bridge")`' if settings_ok else 'UNEXPECTED DIFF'))
    L += [f'- unexpected upstream edit: {u}' for u in unexpected]
    L += [f'- upstream workflow present (removed on the branch, check it): {w}' for w in foreign]
    return '\n'.join(L) + '\n'


# ---- git glue (not unit-tested; exercised by upstream-bump.sh) ------------------------------------------------------
def _git(repo, *args, check=True):
    r = subprocess.run(['git', '-C', repo, *args], capture_output=True, text=True)
    if check and r.returncode != 0:
        raise RuntimeError(f'git {" ".join(args)}: {r.stderr.strip()}')
    return r.stdout if r.returncode == 0 else None


def _show(repo, ref, path):
    return _git(repo, 'show', f'{ref}:{path}', check=False)


def watched_commits(repo, old, new, ref_for_sources='HEAD'):
    """Newest first (git log order): the watcher uses the first line as its dedup key."""
    paths = watched_paths(_show(repo, ref_for_sources, 'bridge/app-sources.txt') or '')
    out = _git(repo, 'log', '--no-merges', '--format=%h %s', f'{old}..{new}', '--', *paths)
    return [x for x in out.splitlines() if x]


def report_ok(report):
    """False when the replay itself is wrong: an upstream edit outside the exceptions, settings.gradle.kts differing by
    more than the include, the compat commit missing / duplicated, a compat target gone or CHANGED upstream (its hidden
    constructors may now delegate wrongly even if everything compiles: a person re-checks them), or an oracle run that
    did not finish (gates are judged by upstream-bump.sh)."""
    return ('unexpected upstream edit:' not in report and 'UNEXPECTED DIFF' not in report
            and '(expected 1)' not in report and 'NOT FOUND at the new pin' not in report
            and ': CHANGED' not in report and 'Oracle FAILED' not in report)


def build_report(a):
    repo = a.repo
    allow = parse_allowlist(_show(repo, a.base, 'bridge/symbol-allowlist.txt') or '')
    oracle_ran = bool(a.missing and os.path.exists(a.missing))
    lost, gained, oracle_ok = [], [], True
    if oracle_ran:
        with open(a.missing, encoding='utf-8') as f:
            missing, oracle_ok = parse_oracle(f.read())
        if oracle_ok:
            lost, gained = symbol_delta(allow, missing)
    toml = 'gradle/libs.versions.toml'
    old_v, new_v = parse_versions(_show(repo, a.old_pin, toml) or ''), parse_versions(_show(repo, a.new_pin, toml) or '')
    versions = version_delta(old_v, new_v)
    compat = {}
    for path, classes in COMPAT_TARGETS.items():
        old_src, new_src = _show(repo, a.old_pin, path) or '', _show(repo, a.new_pin, path) or ''
        for c in classes:
            o, n = primary_ctor_params(old_src, c), primary_ctor_params(new_src, c)
            compat[c] = None if o is None or n is None else ctor_delta(o, n)
    changes = parse_name_status(_git(repo, 'diff', '--name-status', '-M', a.new_pin, 'HEAD'))
    settings_ok = settings_edit_ok(_git(repo, 'diff', a.new_pin, 'HEAD', '--', 'settings.gradle.kts'))
    subjects = _git(repo, 'log', '--format=%s', f'{a.new_pin}..HEAD').splitlines()
    wf = _git(repo, 'ls-tree', '--name-only', 'HEAD', '.github/workflows/').splitlines()
    removed = [w for w in (a.foreign or '').split(',') if w]
    gates = []
    if a.gates and os.path.exists(a.gates):
        with open(a.gates, encoding='utf-8') as f:
            gates = [tuple(line.rstrip('\n').split('\t', 1)) for line in f if '\t' in line]
    return render_report(
        old_pin=a.old_pin, new_pin=a.new_pin, branch=a.branch, commits=watched_commits(repo, a.old_pin, a.new_pin),
        lost=lost, gained=gained, versions=versions, compat=compat, unexpected=unexpected_edits(changes),
        settings_ok=settings_ok, compat_commits=subjects.count(COMPAT_SUBJECT),
        foreign=removed + [w for w in foreign_workflows([os.path.basename(w) for w in wf]) if w not in removed],
        gates=gates, oracle_ran=oracle_ran, oracle_ok=oracle_ok, kotlin=(kotlin_version(old_v), kotlin_version(new_v)))


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest='cmd', required=True)
    p = sub.add_parser('pin'); p.add_argument('file', nargs='?', default='bridge/build.gradle.kts')
    p = sub.add_parser('set-pin'); p.add_argument('file'); p.add_argument('sha')
    p = sub.add_parser('watched-commits')
    p.add_argument('--repo', default='.'); p.add_argument('--old', required=True); p.add_argument('--new', required=True)
    p = sub.add_parser('report')
    for k in ('--repo', '--base', '--old-pin', '--new-pin', '--branch'):
        p.add_argument(k, required=True)
    p.add_argument('--missing'); p.add_argument('--gates')
    p.add_argument('--foreign', help='comma-separated upstream workflows the bump removed')
    a = ap.parse_args(argv)
    if a.cmd == 'pin':
        with open(a.file, encoding='utf-8') as f:
            print(read_pin(f.read()))
    elif a.cmd == 'set-pin':
        with open(a.file, encoding='utf-8') as f:
            text = set_pin(f.read(), a.sha)
        with open(a.file, 'w', encoding='utf-8') as f:
            f.write(text)
    elif a.cmd == 'watched-commits':
        print('\n'.join(watched_commits(a.repo, a.old, a.new)))
    else:
        report = build_report(a)
        sys.stdout.write(report)
        return 0 if report_ok(report) else 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
