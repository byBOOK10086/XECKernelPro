"""Carrier regressions using synthetic files, no network or hardware keys."""
from pathlib import Path
import json
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
CARRIER = ROOT / 'userspace/ksud/builtin/tricky_store'
BASH = shutil.which('bash') or 'C:/Program Files/Git/bin/bash.exe'


class CarrierTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='tee-test-')
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        for name in ['runtime', 'module', 'proc', 'mirror']:
            (self.root / name).mkdir()
        for name in ['keybox-feed.sh', 'injection-status.sh']:
            self.write(name, (CARRIER / name).read_text(encoding='utf-8'))
        self.write('runtime/keybox.xml', '<AndroidAttestation>old</AndroidAttestation>')
        self.write('module/keybox.xml', '<AndroidAttestation>old</AndroidAttestation>')
        self.write('candidate', '<AndroidAttestation>new</AndroidAttestation>')
        self.report = {'ok': True, 'leaf_days_left': 12, 'keys': [{'keybox_index': 1, 'root_type': 'google_rkp', 'key_match': 'matched', 'ok': True}]}
        self.write('valid.json', json.dumps(self.report, indent=2))
        self.write('validator', '#!/bin/sh\ncat "$FIXTURE/valid.json"\nexit "$VALIDATE_RC"\n')
        (self.root / 'validator').chmod(0o755)

    def write(self, name, value):
        (self.root / name).write_text(value, encoding='utf-8', newline='\n')

    def run_shell(self, code):
        self.write('run.sh', code)
        return subprocess.run([BASH, (self.root / 'run.sh').as_posix()], env=dict(os.environ, FIXTURE=self.root.as_posix(), VALIDATE_RC='0'), capture_output=True, text=True, timeout=30)

    def feed(self, setup='', action='refresh_keybox_if_stale'):
        return self.run_shell(r'''
RUNTIME="$FIXTURE/runtime"
MODPATH="$FIXTURE/module"
export RUNTIME MODPATH
. "$FIXTURE/keybox-feed.sh"
keybox_lock() { return 0; }
log_line() { :; }
_ta_enhanced_bin() { printf '%s\n' "$FIXTURE/validator"; }
_keybox_health_line() { echo '1 2 google_rkp'; }
_mirror_keybox_unlocked() { cp "$RUNTIME/keybox.xml" "$FIXTURE/mirror/keybox.xml"; }
feed_download() { echo "$1" >> "$FIXTURE/requests"; cp "$FIXTURE/candidate" "$2"; }
XEC_KEYBOX_FEEDS='https://first.invalid/feed https://second.invalid/feed'
''' + setup + '\n' + action + '\n')

    def status(self):
        return (self.root / 'runtime/.keybox-feed.status').read_text().strip().split(' ', 1)[1]

    def test_http_blocked(self):
        self.assertNotEqual(self.feed("XEC_KEYBOX_FEEDS='http://first.invalid/feed'").returncode, 0)
        self.assertEqual(self.status(), 'blocked-no-https-source')
        self.assertFalse((self.root / 'requests').exists())

    def test_custom_survives_failed_health(self):
        self.write('runtime/keybox.xml', 'user-file')
        self.assertEqual(self.feed('_keybox_health_line() { return 1; }').returncode, 0)
        self.assertEqual(self.status(), 'custom-preserved')
        self.assertEqual((self.root / 'runtime/keybox.xml').read_text(), 'user-file')

    def test_missing_validator(self):
        self.assertNotEqual(self.feed('_ta_enhanced_bin() { return 1; }').returncode, 0)
        self.assertEqual(self.status(), 'validator-unavailable')

    def test_validation_failure_cannot_be_overridden_by_json(self):
        self.assertNotEqual(self.feed('export VALIDATE_RC=1').returncode, 0)
        self.assertEqual(self.status(), 'no-usable-candidate')
        self.assertIn('old', (self.root / 'runtime/keybox.xml').read_text())

    def test_invalid_or_missing_private_key_rejected(self):
        for field, value in [('ok', False), ('key_match', 'skipped'), ('root_type', 'unknown'), ('keybox_index', 2)]:
            report = json.loads(json.dumps(self.report))
            report['keys'][0][field] = value
            self.write('valid.json', json.dumps(report, indent=2))
            (self.root / 'runtime/.keybox_feed.stamp').unlink(missing_ok=True)
            self.assertNotEqual(self.feed().returncode, 0, field)
            self.assertIn('old', (self.root / 'runtime/keybox.xml').read_text())

    def test_identical_primary_tries_backup(self):
        result = self.feed(r'''
feed_download() {
    echo "$1" >> "$FIXTURE/requests"
    case "$1" in *first*) cp "$RUNTIME/keybox.xml" "$2" ;; *) cp "$FIXTURE/candidate" "$2" ;; esac
}
''')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len((self.root / 'requests').read_text().splitlines()), 2)
        self.assertEqual(self.status(), 'installed-runtime-unconfirmed')
        self.assertIn('new', (self.root / 'mirror/keybox.xml').read_text())

    def test_invalid_primary_tries_backup(self):
        self.write('validator', '#!/bin/sh\ncase "$(cat "$3")" in *invalid*) exit 1;; esac\ncat "$FIXTURE/valid.json"\n')
        result = self.feed(r'''
feed_download() {
    case "$1" in *first*) echo '<AndroidAttestation>invalid</AndroidAttestation>' > "$2" ;; *) cp "$FIXTURE/candidate" "$2" ;; esac
}
''')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.status(), 'installed-runtime-unconfirmed')

    def test_mirror_failure_restores_old_file(self):
        result = self.feed(r'''
_mirror_keybox_unlocked() {
    grep -q new "$RUNTIME/keybox.xml" && return 1
    cp "$RUNTIME/keybox.xml" "$FIXTURE/mirror/keybox.xml"
}
''')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.status(), 'files-restored-runtime-unconfirmed')
        self.assertIn('old', (self.root / 'runtime/keybox.xml').read_text())
        self.assertIn('old', (self.root / 'mirror/keybox.xml').read_text())

    def test_interrupted_transaction_recovered(self):
        result = self.feed(r'''
cp "$RUNTIME/keybox.xml" "$RUNTIME/.keybox-feed.backup"
cp "$FIXTURE/candidate" "$RUNTIME/keybox.xml"
feed_sha "$RUNTIME/keybox.xml" > "$RUNTIME/.keybox-feed.pending"
''', 'mirror_keybox')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('old', (self.root / 'runtime/keybox.xml').read_text())

    def test_concurrent_edit_preserved(self):
        result = self.feed('feed_download() { echo user-file > "$RUNTIME/keybox.xml"; cp "$FIXTURE/candidate" "$2"; }')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.status(), 'concurrent-edit-preserved')

    def test_lock_denial_does_not_write(self):
        self.assertNotEqual(self.feed('keybox_lock() { return 1; }').returncode, 0)
        self.assertFalse((self.root / 'requests').exists())

    def test_real_flock_exclusion(self):
        if self.run_shell('command -v flock').returncode: self.skipTest('native flock unavailable')
        result = self.run_shell(r'''
RUNTIME="$FIXTURE/runtime"
. "$FIXTURE/keybox-feed.sh"
( keybox_lock || exit 1; ( keybox_lock && exit 1; exit 0 ) )
''')
        self.assertEqual(result.returncode, 0, result.stderr)

    def probe(self, maps=None, state='42 payload.so', pids='42'):
        (self.root / 'proc/42').mkdir(exist_ok=True)
        if maps is not None: self.write('proc/42/maps', maps)
        self.write('state', state)
        return self.run_shell(r'''
export STATE="$FIXTURE/state" PROC_ROOT="$FIXTURE/proc"
pidof() { echo 'PIDS'; }
export -f pidof
bash "$FIXTURE/injection-status.sh"
'''.replace('PIDS', pids)).returncode

    def test_unreadable_maps_unknown(self):
        self.assertEqual(self.probe(), 2)

    def test_payload_scoped_to_current_pid(self):
        self.assertEqual(self.probe('mapping payload.so'), 0)
        self.assertEqual(self.probe('mapping payload.so', state='43 payload.so'), 1)

    def test_empty_maps_unknown(self):
        self.assertEqual(self.probe(''), 2)

    def test_absent_payload_or_keystore(self):
        self.assertEqual(self.probe('normal maps'), 1)
        self.assertEqual(self.probe('normal maps', pids=''), 1)


if __name__ == '__main__':
    unittest.main(verbosity=2)
