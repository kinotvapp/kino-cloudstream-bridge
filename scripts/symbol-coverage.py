#!/usr/bin/env python3
# GPL-3.0 (this fork). Symbol-coverage oracle for the Kino CloudStream bridge.
"""Check that every symbol real CloudStream plugins (.cs3) link against resolves on a classpath.

The symbol list is the research inventory (cs3-symbols.tsv, measured across 583 real plugins), whose format is:
    T  <binary.class.Name>                          the type must exist
    M  <binary.class.Name>.<name>(<args>)<ret>      a method with that exact name + JVM descriptor
    F  <binary.class.Name>.<name>:<type>            a field with that exact name + JVM type
Nested classes keep `$` (com.x.Outer$Inner). A member counts as present when the owner class or any of its
supertypes (super_class chain and interfaces, as the runtime's resolution walks them) declares it.

Inputs (any mix, first occurrence of a class wins, like a classpath):
  - directories of .class files
  - .jar / .zip files of .class files
  - .dex files, and .apk / .zip files containing classes*.dex (for the release/minified check later)
  - --classpath-file FILE: one such path per line (written by the :bridge Gradle task)

A member that the walk finds first in a class where it is private counts as missing (it would not link).

Parity (release vs debug): --parity-reference REF_APK with --parity-prefix P (repeatable, internal-name prefixes such as
`kotlin/`; each must match at least one reference class, so a renamed or vanished package fails instead of passing
silently): every public class under those prefixes in REF_APK, and every public or protected method and field of it,
must exist in the checked APK (a member may also resolve through a supertype, on the positional classpath: give the
checked APK and the boot classpath there too). Compiler and R8 lambda artifacts are ignored (`$$` synthesized classes such as
`$$ExternalSyntheticLambda0`, and members named `lambda$...`, `...$lambda$N`, `$r8$lambda$...`): no plugin links
against those. --forbid-prefix P (repeatable): no class under P may be in the checked APK, and no symbol row may name it.

--reflective FILE (repeatable): symbols the bridge itself reaches by reflection (getDeclaredMethod / getDeclaredField /
getMethod on the class that declares it), so R8 must keep them: TSV `kind<TAB>symbol<TAB>reason`, same symbol syntax.
Such a member counts only when the owner class itself declares it, with any access (private is fine: the bridge calls
setAccessible). No allowlist applies to them; any missing row fails.
Exit 1 when a symbol is missing and not in the allowlist, or when an allowlist row is stale (it resolves now),
malformed (no reason), duplicated, or not in the symbol list. Classpath entries that do not exist are printed. Allowlist rows: `<symbol><TAB or spaces><one-line reason>`; `#` starts a comment line.
"""
import argparse
import os
import struct
import sys
import zipfile

ACC_PRIVATE = 0x0002


# ---------------------------------------------------------------------------------------------- class info

class ClassInfo:
    __slots__ = ('name', 'super', 'ifaces', 'methods', 'fields', 'access')

    def __init__(self, name, sup, ifaces, methods, fields, access=0x1):
        self.access = access      # class access flags
        self.name = name          # internal name: com/x/Outer$Inner
        self.super = sup          # internal name or None
        self.ifaces = ifaces      # list of internal names
        self.methods = methods    # {(name, descriptor): access flags}
        self.fields = fields      # {(name, type descriptor): access flags}


def parse_class_file(buf):
    """Minimal JVM class-file parser: this/super/interfaces and declared field + method name/descriptor pairs."""
    if buf[:4] != b'\xca\xfe\xba\xbe':
        raise ValueError('not a class file')
    count = struct.unpack_from('>H', buf, 8)[0]
    utf8, classes = {}, {}
    i, off = 1, 10
    while i < count:
        tag = buf[off]
        if tag == 1:
            ln = struct.unpack_from('>H', buf, off + 1)[0]
            utf8[i] = buf[off + 3:off + 3 + ln].decode('utf-8', errors='replace')  # modified UTF-8; names are ASCII
            off += 3 + ln
        elif tag == 7:
            classes[i] = struct.unpack_from('>H', buf, off + 1)[0]
            off += 3
        elif tag in (8, 16, 19, 20):
            off += 3
        elif tag == 15:
            off += 4
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            off += 5
        elif tag in (5, 6):
            off += 9
            i += 1  # long/double take two slots
        else:
            raise ValueError(f'bad constant pool tag {tag}')
        i += 1

    def cname(idx):
        return utf8[classes[idx]] if idx else None

    acc, this_i, super_i, n_if = struct.unpack_from('>HHHH', buf, off)
    off += 8
    ifaces = [cname(struct.unpack_from('>H', buf, off + 2 * k)[0]) for k in range(n_if)]
    off += 2 * n_if

    def members(off):
        n = struct.unpack_from('>H', buf, off)[0]
        off += 2
        out = {}
        for _ in range(n):
            acc, ni, di, na = struct.unpack_from('>HHHH', buf, off)
            off += 8
            for _ in range(na):
                off += 6 + struct.unpack_from('>I', buf, off + 2)[0]
            out[(utf8[ni], utf8[di])] = acc
        return out, off

    fields, off = members(off)
    methods, off = members(off)
    return ClassInfo(cname(this_i), cname(super_i), ifaces, methods, fields, acc)


def _uleb(buf, off):
    res = shift = 0
    while True:
        b = buf[off]
        off += 1
        res |= (b & 0x7f) << shift
        if b < 0x80:
            return res, off
        shift += 7


def parse_dex(buf):
    """Classes defined in one dex file (same table walk as the research analyzer, analyze.py)."""
    (str_sz, str_off, typ_sz, typ_off, pro_sz, pro_off, fld_sz, fld_off, met_sz, met_off, cls_sz, cls_off) = \
        struct.unpack_from('<12I', buf, 0x38)
    strings = []
    for i in range(str_sz):
        so = struct.unpack_from('<I', buf, str_off + 4 * i)[0]
        _, p = _uleb(buf, so)
        strings.append(buf[p:buf.index(b'\x00', p)].decode('utf-8', errors='replace'))
    types = [strings[struct.unpack_from('<I', buf, typ_off + 4 * i)[0]] for i in range(typ_sz)]
    protos = []
    for i in range(pro_sz):
        _, ret, poff = struct.unpack_from('<3I', buf, pro_off + 12 * i)
        params = []
        if poff:
            n = struct.unpack_from('<I', buf, poff)[0]
            params = [types[struct.unpack_from('<H', buf, poff + 4 + 2 * k)[0]] for k in range(n)]
        protos.append('(' + ''.join(params) + ')' + types[ret])
    fields = [None] * fld_sz
    for i in range(fld_sz):
        _c, t, n = struct.unpack_from('<HHI', buf, fld_off + 8 * i)
        fields[i] = (strings[n], types[t])
    methods = [None] * met_sz
    for i in range(met_sz):
        _c, p, n = struct.unpack_from('<HHI', buf, met_off + 8 * i)
        methods[i] = (strings[n], protos[p])

    def internal(t):
        return t[1:-1] if t.startswith('L') and t.endswith(';') else t

    out = []
    for i in range(cls_sz):
        (ci, cacc, sup, ioff, _, _, cdoff, _) = struct.unpack_from('<8I', buf, cls_off + 32 * i)
        ifaces = []
        if ioff:
            n = struct.unpack_from('<I', buf, ioff)[0]
            ifaces = [internal(types[struct.unpack_from('<H', buf, ioff + 4 + 2 * k)[0]]) for k in range(n)]
        dm, df = {}, {}
        if cdoff:
            o = cdoff
            sf, o = _uleb(buf, o); inf, o = _uleb(buf, o); dms, o = _uleb(buf, o); vms, o = _uleb(buf, o)
            for cnt in (sf, inf):
                idx = 0
                for _ in range(cnt):
                    d, o = _uleb(buf, o); acc, o = _uleb(buf, o)
                    idx += d
                    df[fields[idx]] = acc
            for cnt in (dms, vms):
                idx = 0
                for _ in range(cnt):
                    d, o = _uleb(buf, o); acc, o = _uleb(buf, o); _, o = _uleb(buf, o)
                    idx += d
                    dm[methods[idx]] = acc
        out.append(ClassInfo(internal(types[ci]), internal(types[sup]) if sup != 0xFFFFFFFF else None,
                             ifaces, dm, df, cacc))
    return out


# ---------------------------------------------------------------------------------------------- classpath

class Classpath:
    """Lazy index over class dirs, jars and dex/apk files. First definition of a class wins."""

    def __init__(self):
        self._where = {}   # internal name -> loader callable
        self._cache = {}

    def add(self, path):
        if os.path.isdir(path):
            for root, _dirs, files in os.walk(path):
                for f in files:
                    if f.endswith('.class'):
                        full = os.path.join(root, f)
                        rel = os.path.relpath(full, path)[:-6].replace(os.sep, '/')
                        self._where.setdefault(rel, lambda full=full: parse_class_file(open(full, 'rb').read()))
        elif path.endswith('.dex'):
            self._add_dex(open(path, 'rb').read())
        elif os.path.isfile(path) and zipfile.is_zipfile(path):
            z = zipfile.ZipFile(path)
            names = z.namelist()
            dexes = sorted(n for n in names if n.startswith('classes') and n.endswith('.dex') and '/' not in n)
            for d in dexes:
                self._add_dex(z.read(d))
            for n in names:
                if n.endswith('.class') and not n.startswith('META-INF/') and n != 'module-info.class':
                    self._where.setdefault(n[:-6], lambda z=z, n=n: parse_class_file(z.read(n)))
        elif not os.path.exists(path):
            raise FileNotFoundError(path)

    def _add_dex(self, buf):
        for ci in parse_dex(buf):
            self._where.setdefault(ci.name, lambda ci=ci: ci)

    def get(self, name):
        if name not in self._cache:
            loader = self._where.get(name)
            self._cache[name] = loader() if loader else None
        return self._cache[name]

    def resolve(self, owner, kind, key):
        """Walk owner, then supertypes (super_class chain and interfaces). Returns None when found and linkable,
        else why not: 'no owner class', 'not declared', or 'private in <class>' (the runtime resolves the first
        declaration it meets, and a private one fails the access check, so that counts as missing)."""
        if self.get(owner) is None:
            return 'no owner class'
        seen, queue = set(), [owner]
        while queue:
            c = queue.pop(0)
            if c is None or c in seen:
                continue
            seen.add(c)
            info = self.get(c)
            if info is None:
                continue
            members = info.methods if kind == 'M' else info.fields
            if key in members:
                return f'private in {c}' if members[key] & ACC_PRIVATE else None
            queue.append(info.super)
            queue.extend(info.ifaces)
        return 'not declared'

    def resolve_declared(self, owner, kind, key):
        """Reflection's view: declared on [owner] itself, any access. None when found, else why not."""
        info = self.get(owner)
        if info is None:
            return 'no owner class'
        return None if key in (info.methods if kind == 'M' else info.fields) else 'not declared on the class itself'



# ---------------------------------------------------------------------------------------------- symbols

def parse_symbol(kind, sym):
    """TSV symbol -> (owner internal name, member key or None)."""
    if kind == 'T':
        return sym.replace('.', '/'), None
    if kind == 'M':
        paren = sym.index('(')
        head, desc = sym[:paren], sym[paren:]
        owner, name = head.rsplit('.', 1)
        return owner.replace('.', '/'), (name, desc)
    if kind == 'F':
        head, desc = sym.split(':', 1)
        owner, name = head.rsplit('.', 1)
        return owner.replace('.', '/'), (name, desc)
    raise ValueError(f'unknown kind {kind!r}')


def read_tsv(path):
    rows = []
    with open(path, encoding='utf-8') as f:
        header = f.readline().rstrip('\n').split('\t')
        for line in f:
            if not line.strip():
                continue
            cols = line.rstrip('\n').split('\t')
            rows.append(dict(zip(['kind', 'symbol', 'plugins_all', 'pct_all', 'plugins_es', 'pct_es', 'rank'], cols)))
    if header[:2] != ['kind', 'symbol']:
        raise ValueError(f'unexpected TSV header {header}')
    return rows


def read_allowlist(path):
    allow, bad = {}, []
    if not path or not os.path.exists(path):
        return allow, bad
    with open(path, encoding='utf-8') as f:
        for n, line in enumerate(f, 1):
            s = line.strip()
            if not s or s.startswith('#'):
                continue
            parts = s.split(None, 1)
            if len(parts) < 2 or not parts[1].strip():
                bad.append(f'{path}:{n}: no reason given: {s}')
                continue
            if parts[0] in allow:
                bad.append(f'{path}:{n}: duplicate row: {parts[0]}')
                continue
            allow[parts[0]] = parts[1].strip()
    return allow, bad


def read_reflective(path):
    """Rows of a --reflective file: (kind, symbol, reason); `#` lines and the `kind` header are skipped."""
    rows = []
    with open(path, encoding='utf-8') as f:
        for n, line in enumerate(f, 1):
            s = line.rstrip('\n')
            if not s.strip() or s.startswith('#') or s.startswith('kind\t'):
                continue
            cols = s.split('\t')
            if len(cols) < 3 or cols[0] not in ('T', 'M', 'F') or not cols[2].strip():
                raise ValueError(f'{path}:{n}: expected kind<TAB>symbol<TAB>reason: {s}')
            rows.append((cols[0], cols[1], cols[2]))
    return rows


def _is_lambda_artifact(name):
    return name.startswith('lambda$') or '$lambda$' in name or name.startswith('$r8$lambda$')


def parity_missing(ref, checked, prefixes, resolver=None):
    """What [ref] (internal name -> ClassInfo) has under [prefixes] that [checked] lacks, as printable rows. A member the
    checked class no longer declares still counts when [resolver] (a Classpath over the checked dex + the boot
    classpath) resolves it through a supertype, as the runtime would (R8 drops redundant abstract redeclarations)."""
    out = [f'prefix {p} matches no class of the reference APK (a dead prefix checks nothing)'
           for p in prefixes if not any(n.startswith(p) for n in ref)]
    for name in sorted(ref):
        if not any(name.startswith(p) for p in prefixes) or '$$' in name:
            continue
        info = ref[name]
        if not info.access & 0x1:
            continue  # package-private: no plugin (another package) can link against it
        mine = checked.get(name)
        if mine is None:
            out.append(f'class {name}')
            continue
        for kind, members, have in (('M', info.methods, mine.methods), ('F', info.fields, mine.fields)):
            for key, acc in members.items():
                if acc & 0x5 and not _is_lambda_artifact(key[0]) and key not in have and \
                        (resolver is None or resolver.resolve(name, kind, key) is not None):
                    out.append(f'{kind} {name}.{key[0]}{key[1] if kind == "M" else ":" + key[1]}')
    return out


def apk_classes(path):
    cp = Classpath()
    cp.add(path)
    return {n: cp.get(n) for n in list(cp._where)}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--symbols', required=True, help='cs3-symbols.tsv')
    ap.add_argument('--allowlist', help='symbol-allowlist.txt')
    ap.add_argument('--classpath-file', action='append', default=[], help='file listing one class dir/jar/dex per line')
    ap.add_argument('--reflective', action='append', default=[], help='symbols reached by reflection (see above)')
    ap.add_argument('--parity-reference', help='APK whose public API under --parity-prefix the checked APK must hold')
    ap.add_argument('--parity-apk', help='the checked APK for --parity-reference / --forbid-prefix')
    ap.add_argument('--parity-prefix', action='append', default=[], help='internal-name prefix for the parity check')
    ap.add_argument('--forbid-prefix', action='append', default=[], help='internal-name prefix no class may have')
    ap.add_argument('paths', nargs='*', help='class dirs, jars, dex or apk files')
    a = ap.parse_args()

    entries = list(a.paths)
    for cf in a.classpath_file:
        with open(cf, encoding='utf-8') as f:
            entries += [ln.strip() for ln in f if ln.strip()]
    cp = Classpath()
    skipped = []
    for e in entries:
        if not os.path.exists(e):
            skipped.append(e)  # e.g. a javac output dir that is empty and never created
            continue
        cp.add(e)

    rows = read_tsv(a.symbols)
    allow, problems = read_allowlist(a.allowlist)
    missing, allowed, stale = [], [], []
    for r in rows:
        owner, key = parse_symbol(r['kind'], r['symbol'])
        why = ('no class' if cp.get(owner) is None else None) if key is None else cp.resolve(owner, r['kind'], key)
        r['why'] = why
        ok = why is None
        if ok:
            if r['symbol'] in allow:
                stale.append(r['symbol'])
        elif r['symbol'] in allow:
            allowed.append(r)
        else:
            missing.append(r)
    unknown = sorted(set(allow) - {r['symbol'] for r in rows})

    by_kind = {k: sum(1 for r in rows if r['kind'] == k) for k in 'TMF'}
    print(f'symbol-coverage: {len(rows)} symbols ({by_kind["T"]} T, {by_kind["M"]} M, {by_kind["F"]} F), '
          f'{len(entries) - len(skipped)} classpath entries')
    for e in skipped:
        print(f'SKIPPED\tclasspath entry does not exist: {e}')
    for r in missing:
        print(f'MISSING\t{r["kind"]}\t{r["symbol"]}\tplugins={r["plugins_all"]}\tes={r["plugins_es"]}\t{r["why"]}')
    if allowed:
        ak = {k: sum(1 for r in allowed if r['kind'] == k) for k in 'TMF'}
        es = sorted(float(r['pct_es']) for r in allowed)
        print(f'allowlisted and missing: {len(allowed)} ({ak["T"]} T, {ak["M"]} M, {ak["F"]} F); '
              f'max pct_es {es[-1]:.1f}%, max plugins_es {max(int(r["plugins_es"]) for r in allowed)}')
    for s in stale:
        problems.append(f'stale allowlist row (resolves now, remove it): {s}')
    for s in unknown:
        problems.append(f'allowlist row not in the symbol list: {s}')
    reflective_missing = 0
    for path in a.reflective:
        for kind, sym, reason in read_reflective(path):
            owner, key = parse_symbol(kind, sym)
            why = ('no class' if cp.get(owner) is None else None) if key is None else cp.resolve_declared(owner, kind, key)
            if why is not None:
                reflective_missing += 1
                print(f'MISSING\treflective\t{kind}\t{sym}\t{why}\t({reason})')
        print(f'reflective symbols checked: {path}')
    parity_bad = 0
    if a.parity_reference or a.forbid_prefix:
        checked = apk_classes(a.parity_apk)
        if a.parity_reference:
            gaps = parity_missing(apk_classes(a.parity_reference), checked, a.parity_prefix, resolver=cp)
            for g in gaps:
                print(f'MISSING\tparity\t{g}')
            parity_bad += len(gaps)
            print(f'parity with {os.path.basename(a.parity_reference)}: {len(gaps)} missing under {len(a.parity_prefix)} prefixes')
        for fp in a.forbid_prefix:
            dotted = fp.replace('/', '.')
            hits = sorted(n for n in checked if n.startswith(fp)) + [r['symbol'] for r in rows if dotted in r['symbol'] or fp in r['symbol']]
            for h in hits[:20]:
                print(f'FORBIDDEN\t{fp}\t{h}')
            parity_bad += len(hits)
            print(f'forbidden {fp}: {len(hits)}')
    for p in problems:
        print('ERROR\t' + p)
    print(f'missing (not allowlisted): {len(missing)}' + (f'; reflective missing: {reflective_missing}' if a.reflective else ''))
    return 1 if missing or problems or reflective_missing or parity_bad else 0


if __name__ == '__main__':
    sys.exit(main())
