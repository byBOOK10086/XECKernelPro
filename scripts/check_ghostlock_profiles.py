#!/usr/bin/env python3
"""GhostLock kernel-profile assets must agree with their index.

Selection of a device profile goes through
`manager/app/src/main/assets/kernel_profiles/index.conf`: the bundled catalog
reads the index, and a `release` that is listed but whose `.conf` is missing
fails at *runtime* on exactly the device that needed it. The reverse mistake is
quieter: a `.conf` nobody lists can never be selected, so it is dead weight in
the APK.

Two kinds of `.conf` are legitimately NOT in the index, and both are checked
here instead of being waved through:

  * HOCON fragments, pulled in by `include "x.conf"` from a profile;
  * resolver presets (`execution-<route>.conf`, `execution-tuning.conf`), read
    straight from assets by `AndroidProfileConfigController`.

So every asset must be either indexed, included by some profile, or a preset.
An asset in none of those three buckets is a bug (typo'd name, leftover file).
"""
import os
import re
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_DIR = os.path.join(
    REPO_ROOT, 'manager', 'app', 'src', 'main', 'assets', 'kernel_profiles')

INCLUDE_RE = re.compile(r'^\s*include\s+"([^"]+)"', re.M)
INDEX_FILE_RE = re.compile(r'file\s*=\s*"([^"]+)"')
PRESET_RE = re.compile(r'^execution-(tuning|[a-z0-9-]+)\.conf$')


def main(directory):
    if not os.path.isdir(directory):
        print('FAIL: kernel_profiles directory not found: %s' % directory)
        return 1
    index_path = os.path.join(directory, 'index.conf')
    with open(index_path, encoding='utf-8') as fh:
        index_text = fh.read()

    indexed = INDEX_FILE_RE.findall(index_text)
    assets = {
        name for name in os.listdir(directory)
        if name.endswith('.conf') and name != 'index.conf'
    }

    included = set()
    for name in assets:
        with open(os.path.join(directory, name), encoding='utf-8') as fh:
            for target in INCLUDE_RE.findall(fh.read()):
                included.add(os.path.basename(target))

    presets = {name for name in assets if PRESET_RE.match(name)}
    missing = sorted(set(indexed) - assets)
    duplicates = sorted({name for name in indexed if indexed.count(name) > 1})
    unlisted = sorted(assets - set(indexed) - included - presets)

    problems = []
    if missing:
        problems.append('indexed but missing on disk: %s' % ', '.join(missing))
    if duplicates:
        problems.append('listed more than once: %s' % ', '.join(duplicates))
    if unlisted:
        problems.append(
            'not indexed, not included by any profile, not a preset: %s'
            % ', '.join(unlisted))

    print('indexed=%d assets=%d fragments=%d presets=%d'
          % (len(indexed), len(assets), len(included - set(indexed)),
             len(presets)))
    for problem in problems:
        print('FAIL: %s' % problem)
    print('RESULT: %s' % ('PROBLEM' if problems else 'OK'))
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else DEFAULT_DIR))
