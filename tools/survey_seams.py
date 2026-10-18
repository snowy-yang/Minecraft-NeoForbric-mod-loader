#!/usr/bin/env python3
"""Survey what NeoForge and Fabric actually want to hook, from the staged artifacts.

This is the measurement behind ``neoforbric-kernel/HOOKBUS.md``. Nothing here is
estimated: every number the design document cites is produced by running this.

Two sides, two shapes:

* **Fabric** declares mixins. Each nested module in the fabric-api jar carries one or
  more ``*.mixins.json`` files naming mixin classes; each mixin class carries an
  ``@Mixin`` annotation naming the class it edits. The unit is a *class*.
* **NeoForge** weaves hook calls into the patched base. The unit is a *(class, method)*
  seam: somewhere in ``MinecraftServer#runServer`` there is an ``INVOKESTATIC`` into
  ``CommonHooks``.

Fabric's annotations name intermediary types; the base is Mojmap. The chain is
intermediary -> obf (the tiny file, reversed) -> Mojmap (the proguard file), which is
why both a tiny and a proguard file are required inputs.

Usage::

    python3 tools/survey_seams.py                 # human-readable summary
    python3 tools/survey_seams.py --json out.json # the raw rows

Requires ``javap`` on ``PATH`` (JDK 21). Standard library only otherwise.
"""
import argparse
import collections
import glob
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

HOOK_CLASSES = [
    'net/neoforged/neoforge/common/CommonHooks',
    'net/neoforged/neoforge/event/EventHooks',
    'net/neoforged/neoforge/client/ClientHooks',
    'net/neoforged/neoforge/common/DataMapHooks',
    'net/neoforged/neoforge/common/MonsterRoomHooks',
    'net/neoforged/neoforge/server/ServerLifecycleHooks',
    'net/neoforged/neoforge/registries/DataPackRegistriesHooks',
    'net/neoforged/neoforge/items/VanillaInventoryCodeHooks',
]

# `javap` with several inputs drops the "Classfile " header; it only prints
# 'Compiled from "<source>"'. Splitting on that is the only reliable separator.
COMPILED = 'Compiled from "'
CLASS_DECL = re.compile(r'^(?:public |final |abstract |protected |static )*class ([\w.$]+)')
METHOD_DECL = re.compile(r'([\w$<>]+)\(')


def need_javap():
    if shutil.which('javap') is None:
        sys.exit('javap not found on PATH; set JAVA_HOME to a JDK 21 and export PATH=$JAVA_HOME/bin:$PATH')


def read_tiny(path):
    """obf -> intermediary, from a v1 tiny file."""
    out = {}
    with open(path, encoding='utf-8') as fh:
        for line in fh:
            p = line.rstrip('\n').split('\t')
            if len(p) >= 3 and p[0] == 'CLASS':
                out[p[1]] = p[2]
    return out


def read_proguard(path):
    """obf -> Mojmap, reversed from the `mojmap -> obf:` lines."""
    out = {}
    with open(path, encoding='utf-8') as fh:
        for line in fh:
            m = re.match(r'^(\S+) -> (\S+):$', line)
            if m:
                out[m.group(2)] = m.group(1)
    return out


def build_intermediary_to_mojmap(tiny_path, proguard_path):
    obf2int = read_tiny(tiny_path)
    obf2moj = read_proguard(proguard_path)
    return {i: obf2moj[o] for o, i in obf2int.items() if o in obf2moj}


def to_mojmap(name, chain):
    slashed = name.replace('.', '/')
    if slashed in chain:
        return chain[slashed]
    if '$' in slashed:
        outer, inner = slashed.split('$', 1)
        if outer in chain:
            return chain[outer] + '$' + inner
    return name.replace('/', '.')


MIXIN_ANN = re.compile(r'org\.spongepowered\.asm\.mixin\.Mixin\((.*?)\n\s*\)', re.S)


def mixin_targets(classfile):
    out = subprocess.run(['javap', '-v', '-p', classfile], capture_output=True, text=True).stdout
    m = MIXIN_ANN.search(out)
    if not m:
        return []
    body = m.group(1)
    found = re.findall(r'class L([^;\s]+);', body)
    found += re.findall(r'targets=\["([^"]+)"\]', body)
    return found


def survey_fabric(fabric_jar, workdir, chain):
    """-> (rows, per-class module sets). A row is (module, env, target class)."""
    nested = os.path.join(workdir, 'fabric-api')
    with zipfile.ZipFile(fabric_jar) as z:
        z.extractall(nested)
    modules_root = os.path.join(nested, 'META-INF', 'jars')
    if not os.path.isdir(modules_root):
        sys.exit(f'no nested modules in {fabric_jar}')

    rows, configs, modules = [], 0, 0
    for jar in sorted(glob.glob(os.path.join(modules_root, '*.jar'))):
        modules += 1
        name = os.path.basename(jar)[:-4]
        dest = os.path.join(workdir, 'mods', name)
        with zipfile.ZipFile(jar) as z:
            z.extractall(dest)
        for cfg in glob.glob(os.path.join(dest, '**', '*.mixins.json'), recursive=True):
            configs += 1
            try:
                with open(cfg, encoding='utf-8') as fh:
                    data = json.load(fh)
            except (OSError, json.JSONDecodeError):
                continue
            pkg = data.get('package', '').replace('.', '/')
            for env in ('mixins', 'client', 'server'):
                for cls in data.get(env, []):
                    path = os.path.join(dest, pkg, cls + '.class')
                    if not os.path.exists(path):
                        continue
                    for target in mixin_targets(path):
                        rows.append((name, env, to_mojmap(target, chain)))
    return rows, configs, modules


def survey_neoforge(base_jar, workdir):
    """-> (call sites, base class count). A site is (class, method, hook class, member)."""
    base = os.path.join(workdir, 'base')
    with zipfile.ZipFile(base_jar) as z:
        z.extractall(base)

    all_classes = glob.glob(os.path.join(base, '**', '*.class'), recursive=True)
    files = set()
    for hook in HOOK_CLASSES:
        needle = hook.encode()
        for path in all_classes:
            with open(path, 'rb') as fh:
                if needle in fh.read():
                    files.add(path)

    sites = []
    paths = sorted(files)
    for i in range(0, len(paths), 40):
        out = subprocess.run(['javap', '-c', '-p'] + paths[i:i + 40], capture_output=True, text=True).stdout
        for chunk in out.split(COMPILED)[1:]:
            body = chunk.split('\n', 1)[1] if '\n' in chunk else ''
            cls, method = '?', '?'
            for line in body.splitlines():
                decl = CLASS_DECL.match(line)
                if decl:
                    cls = decl.group(1)
                if line.startswith('  ') and not line.startswith('   ') and '(' in line:
                    m = METHOD_DECL.search(line)
                    if m and 'Code:' not in line:
                        method = m.group(1)
                for hook in HOOK_CLASSES:
                    if hook in line:
                        called = re.search(re.escape(hook) + r'\.([\w$]+)', line)
                        sites.append((cls, method, hook.split('/')[-1], called.group(1) if called else '?'))
    return sites, len(all_classes)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--kernel', default=os.path.join(os.path.dirname(os.path.abspath(__file__)), '..',
                                                     'neoforbric-kernel'),
                    help='the kernel project directory holding .dev/')
    ap.add_argument('--json', help='write the raw rows here')
    ap.add_argument('--keep', action='store_true', help='leave the extraction directory in place')
    args = ap.parse_args()

    need_javap()
    kernel = os.path.abspath(args.kernel)
    dev = os.path.join(kernel, '.dev')
    paths = {
        'fabric-api': os.path.join(dev, 'api', 'fabric-api-0.116.17+1.21.1.jar'),
        'intermediary': os.path.join(dev, 'api', 'intermediary-1.21.1.jar'),
        'proguard': os.path.join(dev, 'mappings', 'mojmap-1.21.1.proguard'),
        'base': os.path.join(dev, 'staged', 'run', 'neoforge-base', 'patched-mc-neoforge-1.21.1.jar'),
    }
    missing = [k for k, v in paths.items() if not os.path.exists(v)]
    if missing:
        sys.exit('missing staged inputs: ' + ', '.join(f'{k} ({paths[k]})' for k in missing))

    workdir = tempfile.mkdtemp(prefix='nf-seams-')
    try:
        # The tiny file lives inside the intermediary jar.
        with zipfile.ZipFile(paths['intermediary']) as z:
            z.extractall(os.path.join(workdir, 'map'))
        tiny = os.path.join(workdir, 'map', 'mappings', 'mappings.tiny')
        chain = build_intermediary_to_mojmap(tiny, paths['proguard'])

        fabric_rows, configs, modules = survey_fabric(paths['fabric-api'], workdir, chain)
        neo_sites, base_classes = survey_neoforge(paths['base'], workdir)

        fabric_classes = collections.defaultdict(set)
        for module, _env, target in fabric_rows:
            fabric_classes[target].add(module)
        neo_classes = {c for c, _m, _h, _mem in neo_sites}
        neo_seams = {(c, m) for c, m, _h, _mem in neo_sites}
        both = set(fabric_classes) & neo_classes

        print(f'映射链: intermediary->Mojmap {len(chain)} 个类')
        print()
        print(f'fabric-api : {modules} 个模块 / {configs} 份配置 / {len(fabric_rows)} 条 mixin 条目')
        print(f'             {len(fabric_classes)} 个唯一目标类')
        print(f'neoforge   : {base_classes} 个基底类, 其中 {len(neo_classes)} 个含钩子调用')
        print(f'             {len(neo_sites)} 处调用点 / {len(neo_seams)} 个 (类,方法) 接缝')
        print(f'交集       : {len(both)} 个类两边都要')
        print(f'并集       : {len(set(fabric_classes) | neo_classes)} 个类')
        print()
        print('neoforge 钩子类分布:')
        for hook, n in collections.Counter(h for _c, _m, h, _mem in neo_sites).most_common():
            print(f'  {hook:28s} {n:3d}')
        print()
        print('最密的接缝:')
        for (cls, meth), n in collections.Counter((c, m) for c, m, _h, _mem in neo_sites).most_common(5):
            print(f'  {n:3d}  {cls}#{meth}')

        if args.json:
            with open(args.json, 'w', encoding='utf-8') as fh:
                json.dump({'fabric': fabric_rows, 'neoforge': neo_sites,
                           'fabric_classes': {k: sorted(v) for k, v in fabric_classes.items()},
                           'both': sorted(both)}, fh, indent=1)
            print(f'\nraw rows -> {args.json}')
    finally:
        if not args.keep:
            shutil.rmtree(workdir, ignore_errors=True)
        else:
            print(f'extraction kept at {workdir}')


if __name__ == '__main__':
    main()
