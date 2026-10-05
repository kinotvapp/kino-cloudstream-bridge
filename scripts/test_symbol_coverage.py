#!/usr/bin/env python3
# GPL-3.0 (this fork). Unit tests for scripts/symbol-coverage.py: python3 scripts/test_symbol_coverage.py
import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location('symbol_coverage', os.path.join(HERE, 'symbol-coverage.py'))
sc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(sc)

FIXTURE = {
    'fx/Base.java': 'package fx; public interface Base { int K = 1; default void inherited() {} }',
    'fx/Impl.java': 'package fx; public class Impl implements Base { private void hidden() {} public long own(String s) { return 0; } }',
    'fx/Sub.java': 'package fx; public class Sub extends Impl {}',
}

HEADER = 'kind\tsymbol\tplugins_all(n=583)\tpct_all\tplugins_es(n=66)\tpct_es\tcumulative_rank\n'


class OracleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        src = os.path.join(cls.tmp.name, 'src')
        cls.classes = os.path.join(cls.tmp.name, 'classes')
        files = []
        for rel, text in FIXTURE.items():
            path = os.path.join(src, rel)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, 'w') as f:
                f.write(text)
            files.append(path)
        subprocess.run(['javac', '-d', cls.classes] + files, check=True)
        cls.cp = sc.Classpath()
        cls.cp.add(cls.classes)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def resolve(self, kind, sym):
        owner, key = sc.parse_symbol(kind, sym)
        return self.cp.resolve(owner, kind, key)

    def test_parse_symbol_formats(self):
        self.assertEqual(sc.parse_symbol('T', 'a.b.C$D'), ('a/b/C$D', None))
        self.assertEqual(sc.parse_symbol('M', 'a.b.C$D.get$default(La/X;I)V'), ('a/b/C$D', ('get$default', '(La/X;I)V')))
        self.assertEqual(sc.parse_symbol('F', 'a.b.C.INSTANCE:La/b/C;'), ('a/b/C', ('INSTANCE', 'La/b/C;')))

    def test_member_inherited_through_interface_of_superclass(self):
        self.assertIsNone(self.resolve('M', 'fx.Sub.inherited()V'))
        self.assertIsNone(self.resolve('F', 'fx.Sub.K:I'))
        self.assertIsNone(self.resolve('M', 'fx.Sub.own(Ljava/lang/String;)J'))

    def test_wrong_descriptor_or_absent_member_is_missing(self):
        self.assertEqual(self.resolve('M', 'fx.Sub.own(Ljava/lang/String;)I'), 'not declared')
        self.assertEqual(self.resolve('M', 'fx.Nope.x()V'), 'no owner class')

    def test_private_match_counts_as_missing(self):
        self.assertEqual(self.resolve('M', 'fx.Sub.hidden()V'), 'private in fx/Impl')

    def test_allowlist_rejects_malformed_and_duplicate_rows(self):
        with tempfile.NamedTemporaryFile('w', suffix='.txt', delete=False) as f:
            f.write('# comment\nfx.Sub.x()V\tsome reason\nfx.Sub.y()V\nfx.Sub.x()V\tagain\n')
        try:
            allow, bad = sc.read_allowlist(f.name)
        finally:
            os.unlink(f.name)
        self.assertEqual(list(allow), ['fx.Sub.x()V'])
        self.assertEqual(len(bad), 2)
        self.assertIn('no reason', bad[0])
        self.assertIn('duplicate', bad[1])

    def run_main(self, rows, allow_text):
        with tempfile.TemporaryDirectory() as d:
            tsv, allow = os.path.join(d, 's.tsv'), os.path.join(d, 'a.txt')
            with open(tsv, 'w') as f:
                f.write(HEADER + ''.join(f'{k}\t{s}\t1\t0.2\t0\t0.0\t1\n' for k, s in rows))
            with open(allow, 'w') as f:
                f.write(allow_text)
            argv = sys.argv
            sys.argv = ['symbol-coverage.py', '--symbols', tsv, '--allowlist', allow, self.classes]
            try:
                with open(os.devnull, 'w') as null:
                    stdout, sys.stdout = sys.stdout, null
                    try:
                        return sc.main()
                    finally:
                        sys.stdout = stdout
            finally:
                sys.argv = argv

    def test_exit_codes(self):
        self.assertEqual(self.run_main([('T', 'fx.Sub'), ('M', 'fx.Sub.inherited()V')], ''), 0)
        self.assertEqual(self.run_main([('M', 'fx.Sub.hidden()V')], ''), 1)
        self.assertEqual(self.run_main([('M', 'fx.Sub.hidden()V')], 'fx.Sub.hidden()V\tprivate on purpose\n'), 0)
        self.assertEqual(self.run_main([('T', 'fx.Sub')], 'fx.Sub\tstale row\n'), 1)


if __name__ == '__main__':
    unittest.main()
