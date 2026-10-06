#!/usr/bin/env python3
# GPL-3.0 (this fork). Unit tests for scripts/upstream_bump.py (the pure parts of scripts/upstream-bump.sh):
#   python3 scripts/test_upstream_bump.py
import importlib.util
import os
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location('upstream_bump', os.path.join(HERE, 'upstream_bump.py'))
ub = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ub)

PIN = '4e2516d56ecd42c22c52e461980625fe3f3d559a'

GRADLE = f'''
android {{ }}
// comment mentioning val upstreamCommit = "0000000000000000000000000000000000000000" is not the pin
val upstreamCommit = "{PIN}"
'''

ORACLE_OUT = '''symbol-coverage: 900 symbols (100 T, 700 M, 100 F), 80 classpath entries
SKIPPED\tclasspath entry does not exist: /x/y.jar
MISSING\tT\tcom.lagradost.cloudstream3.extractors.Chillx\tplugins=5\tes=0\tclass not on the classpath
MISSING\tM\tcom.lagradost.cloudstream3.APIHolder.getApis()Ljava/util/List;\tplugins=1\tes=0\tno such method
MISSING\tM\tcom.lagradost.cloudstream3.NewThing.go()V\tplugins=40\tes=9\tno such method
MISSING\treflective\tM\tcom.kino.X.y()V\twhy\t(reason)
MISSING\tparity\tLfoo/Bar;
missing (not allowlisted): 3
'''

ALLOWLIST = '''# header comment
com.lagradost.cloudstream3.extractors.Chillx\textractor class absent [5/0]

com.lagradost.cloudstream3.APIHolder.getApis()Ljava/util/List;\treturns AtomicList [1/0]
com.lagradost.cloudstream3.extractors.GMPlayer\textractor class absent [1/0]
'''

TOML = '''[versions]
agp = "9.1.1"
kotlinGradlePlugin = "2.4.0" # trailing comment
jsoup = "1.18.1"

[libraries]
agp = { module = "x:y", version = "nope" }
'''

MAIN_OLD = '''
package com.lagradost.cloudstream3

/** data class Episode( in a comment must not match */
data class Episode
@Deprecated("Use newEpisode method", level = DeprecationLevel.ERROR)
constructor(
    var data: String,
    var name: String? = null, // trailing comment (with parens)
    var score: Score? = null,
    var runTime: Int? = null,
) {
    fun x() = Unit
}

data class EpisodeExtra(val a: Int)

open class ExtractorLink
@Deprecated("Use newExtractorLink", level = DeprecationLevel.WARNING)
constructor(
    @SerialName("source") open val source: String,
    open val headers: Map<String, String> = mapOf("a" to "b(c"),
    open val quality: Int = Qualities.Unknown.value,
    /* block, comment */ open val type: ExtractorLinkType = INFER_TYPE,
) : IDownloadableMinimum
'''

MAIN_OLD += ''.join(f'\ndata class {c}(val name: String)\n' for c in
                    ('MovieLoadResponse', 'LiveStreamLoadResponse', 'TvSeriesLoadResponse', 'AnimeSearchResponse'))

MAIN_NEW = MAIN_OLD.replace('    var runTime: Int? = null,\n', '    var runTime: Long? = null,\n    var airDate: Long? = null,\n') \
    .replace('    var name: String? = null, // trailing comment (with parens)\n', '')


class PinTest(unittest.TestCase):
    def test_reads_the_constant_not_a_comment(self):
        self.assertEqual(ub.read_pin(GRADLE), PIN)

    def test_missing_pin_raises(self):
        with self.assertRaises(ValueError):
            ub.read_pin('val other = "x"')

    def test_set_pin_replaces_only_the_constant(self):
        new = 'a' * 40
        out = ub.set_pin(GRADLE, new)
        self.assertEqual(ub.read_pin(out), new)
        self.assertIn('0000000000000000000000000000000000000000', out)


class OracleTest(unittest.TestCase):
    def test_parse_missing_keeps_only_oracle_rows(self):
        m = ub.parse_missing(ORACLE_OUT)
        self.assertEqual(set(m), {
            'com.lagradost.cloudstream3.extractors.Chillx',
            'com.lagradost.cloudstream3.APIHolder.getApis()Ljava/util/List;',
            'com.lagradost.cloudstream3.NewThing.go()V',
        })
        self.assertEqual(m['com.lagradost.cloudstream3.NewThing.go()V'], {'kind': 'M', 'plugins': 40, 'es': 9})

    def test_parse_allowlist(self):
        a = ub.parse_allowlist(ALLOWLIST)
        self.assertEqual(len(a), 3)
        self.assertEqual(a['com.lagradost.cloudstream3.extractors.GMPlayer'], 'extractor class absent [1/0]')

    def test_parse_oracle_needs_a_consistent_summary(self):
        m, ok = ub.parse_oracle(ORACLE_OUT)
        self.assertTrue(ok)
        self.assertEqual(len(m), 3)
        self.assertEqual(ub.parse_oracle('Traceback (most recent call last):\n  boom\n'), ({}, False))
        cut = ORACLE_OUT.replace('missing (not allowlisted): 3', 'missing (not allowlisted): 4')
        self.assertFalse(ub.parse_oracle(cut)[1])
        self.assertFalse(ub.parse_oracle(ORACLE_OUT.split('missing (not')[0])[1])

    def test_delta_lost_and_gained(self):
        lost, gained = ub.symbol_delta(ub.parse_allowlist(ALLOWLIST), ub.parse_missing(ORACLE_OUT))
        self.assertEqual([s for s, _ in lost], ['com.lagradost.cloudstream3.NewThing.go()V'])
        self.assertEqual(gained, ['com.lagradost.cloudstream3.extractors.GMPlayer'])

    def test_delta_is_empty_when_missing_equals_allowlist(self):
        allow = ub.parse_allowlist(ALLOWLIST)
        missing = {s: {'kind': 'T', 'plugins': 1, 'es': 0} for s in allow}
        self.assertEqual(ub.symbol_delta(allow, missing), ([], []))

    def test_lost_sorted_by_spanish_then_all_plugins(self):
        missing = {'a': {'kind': 'T', 'plugins': 50, 'es': 0}, 'b': {'kind': 'T', 'plugins': 2, 'es': 2},
                   'c': {'kind': 'T', 'plugins': 9, 'es': 2}}
        lost, _ = ub.symbol_delta({}, missing)
        self.assertEqual([s for s, _ in lost], ['c', 'b', 'a'])


class VersionsTest(unittest.TestCase):
    def test_parse_only_the_versions_table(self):
        v = ub.parse_versions(TOML)
        self.assertEqual(v, {'agp': '9.1.1', 'kotlinGradlePlugin': '2.4.0', 'jsoup': '1.18.1'})

    def test_delta(self):
        new = TOML.replace('"2.4.0"', '"2.5.0"').replace('jsoup = "1.18.1"\n', 'ktor = "3.0"\n')
        self.assertEqual(ub.version_delta(ub.parse_versions(TOML), ub.parse_versions(new)), [
            ('jsoup', '1.18.1', None), ('kotlinGradlePlugin', '2.4.0', '2.5.0'), ('ktor', None, '3.0')])


class CtorTest(unittest.TestCase):
    def test_primary_ctor_after_annotation_and_constructor_keyword(self):
        p = ub.primary_ctor_params(MAIN_OLD, 'Episode')
        self.assertEqual([ub.param_name(x) for x in p], ['data', 'name', 'score', 'runTime'])
        self.assertEqual(p[3], 'var runTime: Int? = null')

    def test_nested_parens_strings_and_comments(self):
        p = ub.primary_ctor_params(MAIN_OLD, 'ExtractorLink')
        self.assertEqual([ub.param_name(x) for x in p], ['source', 'headers', 'quality', 'type'])
        self.assertEqual(p[1], 'open val headers: Map<String, String> = mapOf("a" to "b(c")')

    def test_class_name_is_a_whole_word(self):
        self.assertEqual([ub.param_name(x) for x in ub.primary_ctor_params(MAIN_OLD, 'EpisodeExtra')], ['a'])

    def test_absent_class(self):
        self.assertIsNone(ub.primary_ctor_params(MAIN_OLD, 'Nope'))

    def test_ctor_delta(self):
        d = ub.ctor_delta(ub.primary_ctor_params(MAIN_OLD, 'Episode'), ub.primary_ctor_params(MAIN_NEW, 'Episode'))
        self.assertEqual(d['removed'], ['name'])
        self.assertEqual(d['added'], ['var airDate: Long? = null'])
        self.assertEqual(d['changed'], [('var runTime: Int? = null', 'var runTime: Long? = null')])
        self.assertFalse(d['same'])

    def test_ctor_delta_same(self):
        p = ub.primary_ctor_params(MAIN_OLD, 'ExtractorLink')
        self.assertTrue(ub.ctor_delta(p, list(p))['same'])

    def test_reorder_counts_as_a_change(self):
        p = ub.primary_ctor_params(MAIN_OLD, 'Episode')
        d = ub.ctor_delta(p, [p[1], p[0]] + p[2:])
        self.assertFalse(d['same'])
        self.assertTrue(d['reordered'])


class EditsTest(unittest.TestCase):
    def test_upstream_edits_outside_the_exceptions_are_flagged(self):
        changes = [('M', 'settings.gradle.kts'), ('A', 'bridge/x.kt'), ('M', ub.MAIN_API), ('M', 'README.md'),
                   ('D', '.github/workflows/prerelease.yml'), ('A', 'scripts/a.sh'), ('M', 'BRIDGE.md'),
                   ('M', 'library/src/commonMain/kotlin/com/lagradost/cloudstream3/Other.kt'), ('A', 'app/new.kt')]
        self.assertEqual(ub.unexpected_edits(changes), [
            'M library/src/commonMain/kotlin/com/lagradost/cloudstream3/Other.kt', 'A app/new.kt'])

    def test_name_status_parsing_with_renames(self):
        self.assertEqual(ub.parse_name_status('M\tREADME.md\nR100\tapp/a.kt\tbridge/a.kt\n\n'),
                         [('M', 'README.md'), ('D', 'app/a.kt'), ('A', 'bridge/a.kt')])

    def test_settings_diff_only_the_include_line(self):
        ok = 'diff --git a/settings.gradle.kts b/settings.gradle.kts\n--- a/settings.gradle.kts\n+++ b/settings.gradle.kts\n' \
             '@@ -19,3 +19,5 @@\n include(":app")\n+\n+include(":bridge")\n'
        self.assertTrue(ub.settings_edit_ok(ok))
        self.assertFalse(ub.settings_edit_ok(ok + '-include(":docs")\n'))
        self.assertFalse(ub.settings_edit_ok(ok.replace('+include(":bridge")', '+include(":bridge", ":x")')))
        self.assertFalse(ub.settings_edit_ok(''))

    def test_upstream_workflows_left_are_the_ones_not_ours(self):
        self.assertEqual(ub.foreign_workflows(['kino-ci.yml', 'upstream-watch.yml', 'new_upstream.yml', 'README.md']),
                         ['new_upstream.yml'])


class WatchTest(unittest.TestCase):
    def test_watched_paths(self):
        src = '# comment\n\ncom/a/B.kt\n  com/c/D.kt  \n'
        self.assertEqual(ub.watched_paths(src), ['library/', 'app/src/main/java/com/a/B.kt', 'app/src/main/java/com/c/D.kt',
                                                 'gradle/', 'settings.gradle.kts', 'build.gradle.kts', 'README.md',
                                                 '.github/ISSUE_TEMPLATE/'])

    def test_commit_line_cannot_mention_or_link(self):
        self.assertEqual(ub.commit_line('abc1234 Fix @someone\'s `thing` (#3204)'),
                         "- abc1234 `Fix @someone's 'thing' (#3204)`")

    def test_kotlin_version(self):
        self.assertEqual(ub.kotlin_version(ub.parse_versions(TOML)), '2.4.0')
        self.assertEqual(ub.kotlin_version({'kotlin': '2.1.0'}), '2.1.0')


class ReportTest(unittest.TestCase):
    def test_zero_change_report(self):
        r = ub.render_report(old_pin=PIN, new_pin=PIN, branch='upstream-bump/4e2516d', commits=[], lost=[], gained=[],
                             versions=[], compat={'Episode': {'same': True}}, unexpected=[], settings_ok=True,
                             compat_commits=1, foreign=[], gates=[('unit tests', 'PASS')], oracle_ran=True)
        self.assertIn('Changes: 0', r)
        self.assertIn('Kotlin: None (unchanged)', r)
        self.assertIn('| unit tests | PASS |', r)
        self.assertNotIn('FAIL', r)

    def test_changes_counted(self):
        r = ub.render_report(old_pin=PIN, new_pin='b' * 40, branch='x', commits=['abc1234 lib: change'],
                             lost=[('com.X', {'kind': 'T', 'plugins': 3, 'es': 1})], gained=['com.Y'],
                             versions=[('kotlinGradlePlugin', '2.4.0', '2.5.0')],
                             compat={'Episode': {'same': False, 'added': ['var a: Int'], 'removed': [], 'changed': [],
                                                 'reordered': False}, 'ExtractorLink': None},
                             unexpected=['M app/x.kt'], settings_ok=False, compat_commits=0, foreign=['n.yml'],
                             gates=[('assembleRelease', 'FAIL')], oracle_ran=True)
        for s in ('com.X', 'com.Y', '2.5.0', 'var a: Int', 'ExtractorLink', 'M app/x.kt', 'n.yml', 'FAIL'):
            self.assertIn(s, r)
        self.assertNotIn('Changes: 0', r)

    def test_oracle_not_run_is_said(self):
        r = ub.render_report(old_pin=PIN, new_pin=PIN, branch='x', commits=[], lost=[], gained=[], versions=[],
                             compat={}, unexpected=[], settings_ok=True, compat_commits=1, foreign=[], gates=[],
                             oracle_ran=False)
        self.assertIn('not run', r)

    def test_kotlin_always_printed(self):
        kw = dict(old_pin=PIN, new_pin=PIN, branch='x', commits=[], lost=[], gained=[], versions=[], compat={},
                  unexpected=[], settings_ok=True, compat_commits=1, foreign=[], gates=[], oracle_ran=True)
        self.assertIn('Kotlin: 2.4.0 (unchanged)', ub.render_report(**kw, kotlin=('2.4.0', '2.4.0')))
        self.assertIn('Kotlin: 2.4.0 -> 2.5.0', ub.render_report(**kw, kotlin=('2.4.0', '2.5.0')))

    def test_oracle_failed_is_said_and_fails_the_report(self):
        r = ub.render_report(old_pin=PIN, new_pin=PIN, branch='x', commits=[], lost=[], gained=[], versions=[],
                             compat={}, unexpected=[], settings_ok=True, compat_commits=1, foreign=[], gates=[],
                             oracle_ran=True, oracle_ok=False)
        self.assertIn('Oracle FAILED', r)
        self.assertNotIn('Gained', r)
        self.assertFalse(ub.report_ok(r))

    def test_report_ok(self):
        self.assertTrue(ub.report_ok('- settings.gradle.kts: only `include(":bridge")`\n- commits on the branch: 1\n'))
        self.assertFalse(ub.report_ok('- unexpected upstream edit: M app/x.kt\n'))
        self.assertFalse(ub.report_ok('- settings.gradle.kts: UNEXPECTED DIFF\n'))
        self.assertFalse(ub.report_ok('commits on the branch: 0 (expected 1)\n'))
        self.assertFalse(ub.report_ok('- Episode: NOT FOUND at the new pin (compat commit needs rework)\n'))
        self.assertFalse(ub.report_ok('- Episode: CHANGED (parameters reordered)\n'))


class BumpScriptTest(unittest.TestCase):
    """scripts/upstream-bump.sh --skip-build on a throwaway upstream + fork pair (the git part; Gradle is not run)."""
    SCRIPT = os.path.join(HERE, 'upstream-bump.sh')

    def git(self, repo, *args):
        return subprocess.run(['git', '-C', repo, *args], check=True, capture_output=True, text=True).stdout.strip()

    def write(self, repo, path, text):
        full = os.path.join(repo, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, 'w', encoding='utf-8') as f:
            f.write(text)

    def commit(self, repo, msg, files=None, delete=()):
        for p, t in (files or {}).items():
            self.write(repo, p, t)
        for p in delete:
            self.git(repo, 'rm', '-q', p)
        self.git(repo, 'add', '-A')
        self.git(repo, 'commit', '-q', '-m', msg)
        return self.git(repo, 'rev-parse', 'HEAD')

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        t = self.tmp.name
        self.up, self.fork = os.path.join(t, 'up'), os.path.join(t, 'fork')
        self.git(t, 'init', '-q', '-b', 'master', self.up)
        for r in (self.up,):
            self.git(r, 'config', 'user.name', 'fixture'); self.git(r, 'config', 'user.email', 'fixture@example.invalid')
            self.git(r, 'config', 'uploadpack.allowFilter', 'true')
        self.pin = self.commit(self.up, 'upstream base', {
            'settings.gradle.kts': 'rootProject.name = "X"\ninclude(":app", ":library")\n',
            ub.MAIN_API: MAIN_OLD, ub.EXTRACTOR_API: 'open class ExtractorLink(val a: Int)\n',
            'gradle/libs.versions.toml': TOML, 'README.md': '# Upstream\n',
            '.github/workflows/prerelease.yml': 'name: pre\n', 'app/src/main/java/com/a/B.kt': 'class B\n',
            '.github/ISSUE_TEMPLATE/application-bug.yml': 'name: app bug\n',
            '.github/ISSUE_TEMPLATE/config.yml': 'blank_issues_enabled: false\n',
            'library/src/commonMain/kotlin/com/lagradost/cloudstream3/Other.kt': 'class Other\n'})
        subprocess.run(['git', 'clone', '-q', '-o', 'upstream', self.up, self.fork], check=True)
        self.git(self.fork, 'config', 'user.name', 'fixture'); self.git(self.fork, 'config', 'user.email', 'fixture@example.invalid')
        self.git(self.fork, 'checkout', '-q', '-b', 'kino-bridge', self.pin)
        self.commit(self.fork, 'Bridge: module', {
            'settings.gradle.kts': 'rootProject.name = "X"\ninclude(":app", ":library")\n\ninclude(":bridge")\n',
            'bridge/build.gradle.kts': f'val upstreamCommit = "{self.pin}"\n',
            'bridge/app-sources.txt': '# files\ncom/a/B.kt\n', 'bridge/symbol-allowlist.txt': ALLOWLIST,
            'BRIDGE.md': f'pinned at upstream commit `{self.pin}`\n'})
        self.commit(self.fork, ub.COMPAT_SUBJECT, {ub.MAIN_API: MAIN_OLD + '\n// compat: hidden constructors\n'})
        self.commit(self.fork, 'readme: Kino bridge fork', {'README.md': '# Kino bridge fork\n\n# Upstream\n'})
        self.commit(self.fork, 'Bridge: CI', {'.github/workflows/kino-ci.yml': 'name: ci\n'},
                    delete=['.github/workflows/prerelease.yml'])
        self.base = self.commit(self.fork, 'Bridge: issue templates', {
            '.github/ISSUE_TEMPLATE/config.yml': 'kino: true\n', '.github/ISSUE_TEMPLATE/bridge-bug.yml': 'name: bridge\n'},
            delete=['.github/ISSUE_TEMPLATE/application-bug.yml'])

    def tearDown(self):
        self.tmp.cleanup()

    def run_bump(self, ref, *extra):
        d = os.path.join(self.tmp.name, 'wt-' + str(len(os.listdir(self.tmp.name))))
        env = dict(os.environ, KINO_KEYSTORE_PATH='/nonexistent')
        r = subprocess.run(['bash', self.SCRIPT, '--skip-build', '--dir', d, *extra, ref], cwd=self.fork,
                           capture_output=True, text=True, env=env)
        return r, d

    def test_same_pin_reproduces_the_base_with_zero_changes(self):
        r, d = self.run_bump(self.pin)
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        self.assertIn('Changes: 0', r.stdout)
        self.assertEqual(self.git(d, 'rev-parse', 'HEAD'), self.base)
        self.assertEqual(self.git(self.fork, 'rev-parse', 'kino-bridge'), self.base)

    def test_bump_replays_moves_the_pin_and_reports(self):
        upstream_wf_edit = self.commit(self.up, 'ci: tweak prerelease', {'.github/workflows/prerelease.yml': 'name: pre2\n'})
        new = self.commit(self.up, 'lib: Episode gains airDate', {
            ub.MAIN_API: MAIN_NEW, 'gradle/libs.versions.toml': TOML.replace('"2.4.0"', '"2.5.0"'),
            '.github/workflows/new_upstream.yml': 'name: new\n'})
        self.assertTrue(upstream_wf_edit)
        r, d = self.run_bump('master')
        # A changed compat constructor needs a person even though the replay is clean: exit 1, branch kept.
        self.assertEqual(r.returncode, 1, r.stderr + r.stdout)
        self.assertEqual(self.git(self.fork, 'rev-parse', 'kino-bridge'), self.base)
        self.assertEqual(self.git(d, 'rev-parse', 'HEAD~7'), new)  # 5 fork commits + pin + workflow drop
        with open(os.path.join(d, 'bridge/build.gradle.kts')) as f:
            self.assertEqual(ub.read_pin(f.read()), new)
        with open(os.path.join(d, ub.MAIN_API)) as f:
            main = f.read()
        self.assertIn('// compat: hidden constructors', main)
        self.assertIn('airDate', main)
        self.assertEqual(sorted(os.listdir(os.path.join(d, '.github/workflows'))), ['kino-ci.yml'])
        for s in ('Episode: CHANGED', 'added `var airDate: Long? = null`', 'kotlinGradlePlugin: 2.4.0 -> 2.5.0',
                  'Kotlin: 2.4.0 -> 2.5.0',
                  'lib: Episode gains airDate', 'new_upstream.yml', 'Oracle not run'):
            self.assertIn(s, r.stdout)
        self.assertNotIn('ci: tweak prerelease', r.stdout)  # not the plugin API
        self.assertNotIn('unexpected upstream edit', r.stdout)

    def upstream_change(self):
        return self.commit(self.up, 'lib: Other grows', {
            'library/src/commonMain/kotlin/com/lagradost/cloudstream3/Other.kt': 'class Other { fun y() = 2 }\n'})

    def test_base_never_moves_with_update_refs_and_hooks_never_block(self):
        new = self.upstream_change()
        self.git(self.fork, 'checkout', '-q', '-b', 'elsewhere', self.pin)  # kino-bridge not checked out anywhere
        self.git(self.fork, 'config', 'rebase.updateRefs', 'true')
        self.git(self.fork, 'config', 'rebase.autoStash', 'true')
        hooks = os.path.join(self.tmp.name, 'hooks')
        self.write(hooks, 'pre-commit', '#!/bin/sh\nexit 1\n')
        os.chmod(os.path.join(hooks, 'pre-commit'), 0o755)
        self.git(self.fork, 'config', 'core.hooksPath', hooks)
        r, d = self.run_bump('master')
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        self.assertEqual(self.git(self.fork, 'rev-parse', 'kino-bridge'), self.base)
        self.assertEqual(self.git(d, 'rev-parse', 'HEAD~6'), new)  # 5 fork commits + pin
        self.assertIn('lib: Other grows', r.stdout)

    def test_issue_templates_resolve_in_the_forks_favour(self):
        self.commit(self.up, 'templates: tweak', {'.github/ISSUE_TEMPLATE/application-bug.yml': 'name: app bug 2\n',
                                                  '.github/ISSUE_TEMPLATE/config.yml': 'blank_issues_enabled: true\n'})
        r, d = self.run_bump('master')
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        self.assertEqual(sorted(os.listdir(os.path.join(d, '.github/ISSUE_TEMPLATE'))), ['bridge-bug.yml', 'config.yml'])
        with open(os.path.join(d, '.github/ISSUE_TEMPLATE/config.yml')) as f:
            self.assertEqual(f.read(), 'kino: true\n')
        self.assertIn('templates: tweak', r.stdout)  # watched: a fork-edited upstream file

    def test_internal_failure_before_the_gates_cleans_up_with_exit_4(self):
        self.base = self.commit(self.fork, 'Bridge: no BRIDGE.md', delete=['BRIDGE.md'])  # the pin's sed then fails
        self.upstream_change()
        r, d = self.run_bump('master', '--branch', 'broken')
        self.assertEqual(r.returncode, 4, r.stderr + r.stdout)
        self.assertIn('internal error', r.stderr)
        self.assertFalse(os.path.exists(d))
        self.assertEqual(self.git(self.fork, 'branch', '--list', 'broken'), '')
        self.assertEqual(self.git(self.fork, 'rev-parse', 'kino-bridge'), self.base)

    def test_refuses_a_base_holding_upstream_commits_past_its_pin(self):
        self.upstream_change()
        self.git(self.fork, 'fetch', '-q', 'upstream')
        self.git(self.fork, 'merge', '-q', '--no-edit', 'upstream/master')
        self.commit(self.up, 'lib: more', {'library/x.kt': 'x\n'})
        r, _ = self.run_bump('master')
        self.assertEqual(r.returncode, 2, r.stderr + r.stdout)
        self.assertIn('past its pin', r.stderr)

    def test_conflict_aborts_and_leaves_nothing(self):
        self.commit(self.up, 'settings: new module', {
            'settings.gradle.kts': 'rootProject.name = "X"\ninclude(":app", ":library")\n\ninclude(":other")\n'})
        r, d = self.run_bump('master', '--branch', 'try')
        self.assertEqual(r.returncode, 3, r.stderr + r.stdout)
        self.assertIn('settings.gradle.kts', r.stderr)
        self.assertFalse(os.path.exists(d))
        self.assertEqual(self.git(self.fork, 'branch', '--list', 'try'), '')
        self.assertEqual(self.git(self.fork, 'rev-parse', 'kino-bridge'), self.base)

    def test_refuses_an_existing_branch_and_a_downgrade(self):
        self.git(self.fork, 'branch', 'taken')
        r, _ = self.run_bump(self.pin, '--branch', 'taken')
        self.assertEqual(r.returncode, 2)
        self.assertIn('exists', r.stderr)
        other = self.commit(self.up, 'x', {'x.txt': 'x'})
        self.git(self.up, 'checkout', '-q', '--orphan', 'unrelated')
        orphan = self.commit(self.up, 'orphan', {'y.txt': 'y'})
        self.assertTrue(other)
        r, _ = self.run_bump('unrelated')
        self.assertEqual(r.returncode, 2, r.stderr)
        self.assertIn('does not descend', r.stderr)
        self.assertTrue(orphan)

    def test_an_upstream_edit_outside_the_exceptions_fails_the_report(self):
        self.commit(self.fork, 'oops', {'app/src/main/java/com/a/B.kt': 'class B { fun x() = 1 }\n'})
        r, _ = self.run_bump(self.pin, '--branch', 'oops')
        self.assertEqual(r.returncode, 1, r.stderr + r.stdout)
        self.assertIn('unexpected upstream edit: M app/src/main/java/com/a/B.kt', r.stdout)


if __name__ == '__main__':
    unittest.main(verbosity=2)
