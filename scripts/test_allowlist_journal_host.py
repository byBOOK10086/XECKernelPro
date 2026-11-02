#!/usr/bin/env python3
"""Compile and exercise the exact header used by kernel allowlist persistence."""
import argparse
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

SOURCE = r'''
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "policy/allowlist_journal.h"
int main(void) {
    unsigned long n = ksu_j_length(3, 40), i;
    unsigned char *a = calloc(1, n + 1), *b = calloc(1, n + 1);
    struct ksu_j_meta m;
    unsigned long checks = 0;
    assert(a && b);
    assert(ksu_j_crc((const unsigned char *)"123456789", 9) == 0xcbf43926U); checks++;
    for (i = KSU_J_HEADER; i < n - KSU_J_FOOTER; i++) a[i] = (unsigned char)i;
    assert(!ksu_j_seal(a, 3, 40, 1));
    assert(!ksu_j_validate(a, n, 40, &m) && m.count == 3 && m.generation == 1); checks++;
    /* Every interruption before the commit footer completes is rejected. */
    for (i = 0; i < n; i++) { assert(ksu_j_validate(a, i, 40, &m)); checks++; }
    for (i = 0; i < n; i++) {
        memcpy(b, a, n); b[i] ^= 1;
        assert(ksu_j_validate(b, n, 40, &m)); checks++;
    }
    assert(ksu_j_validate(a, n + 1, 40, &m)); checks++;
    assert(ksu_j_validate(a, n, 41, &m)); checks++;
    assert(!ksu_j_length(65536, 40)); checks++;
    assert(!ksu_j_length(1, 0)); checks++;
    assert(!ksu_j_length(65535, 0xffffffffU)); checks++;
    assert(!ksu_j_seal(b, 3, 40, ~0ULL));
    assert(!ksu_j_validate(b, n, 40, &m) && m.generation == ~0ULL); checks++;
    assert(ksu_j_seal(b, 3, 40, 0)); checks++;
    /* An explicit committed empty list is valid, a header-only empty list is not. */
    assert(!ksu_j_seal(b, 0, 40, 2));
    assert(!ksu_j_validate(b, ksu_j_length(0,40), 40, &m) && !m.count && m.generation == 2); checks++;
    assert(ksu_j_validate(b, KSU_J_HEADER, 40, &m)); checks++;
    /* Interrupted replacement cannot alter the still-committed other slot. */
    for (i = 0; i < ksu_j_length(0,40); i++) {
        assert(ksu_j_validate(b, i, 40, &m));
        assert(!ksu_j_validate(a, n, 40, &m) && m.generation == 1); checks++;
    }
    /* Recovery boundary: corrupt generation 2 revocation falls back to valid
     * generation 1, which may still contain grants. This is not anti-rollback. */
    b[KSU_J_HEADER + 1] ^= 1;
    assert(ksu_j_validate(b, ksu_j_length(0,40), 40, &m)); checks++;
    assert(!ksu_j_validate(a, n, 40, &m) && m.generation == 1 && m.count == 3); checks++;
    free(a); free(b);
    printf("PASS: %lu shared journal codec checks\n", checks);
    return 0;
}
'''


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--cc', default=os.environ.get('CC', 'cc'))
    ap.add_argument('--sanitize', action='store_true')
    args = ap.parse_args()
    root = Path(__file__).resolve().parents[1]
    cc = [args.cc] if Path(args.cc).is_file() else shlex.split(args.cc)
    with tempfile.TemporaryDirectory(prefix='ksu-journal-') as temp:
        temp = Path(temp)
        src = temp / 'journal.c'
        exe = temp / ('journal.exe' if os.name == 'nt' else 'journal')
        src.write_text(SOURCE, encoding='utf-8')
        cmd = cc + ['-std=c11', '-Wall', '-Wextra', '-Werror', '-I' + str(root / 'kernel'), str(src), '-o', str(exe)]
        if args.sanitize:
            cmd += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer', '-g']
        subprocess.run(cmd, check=True)
        subprocess.run([str(exe)], check=True)


if __name__ == '__main__':
    main()
