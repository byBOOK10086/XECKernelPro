"""Run the production gesture parser on a host, without Android system services."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root / 'userspace/ksud/src/stealth.rs').read_text(encoding='utf-8')
touch = source[source.index('const EV_KEY:'):source.index('fn run_watcher()')]
executable = source[source.index('fn is_watcher_executable('):source.index('fn state_lock(')]
tests = r'''
#[cfg(test)] mod tests {
    use super::*;
    fn track() -> TouchTrack { TouchTrack { cur_x: None, cur_y: None, down: None } }
    fn ms(value: u64) -> Duration { Duration::from_millis(value) }
    fn event(value: u64) -> [u8; INPUT_EVENT_SIZE] {
        let mut event = [0; INPUT_EVENT_SIZE];
        event[..8].copy_from_slice(&((value / 1000) as i64).to_ne_bytes());
        event[8..16].copy_from_slice(&(((value % 1000) * 1000) as i64).to_ne_bytes());
        event
    }
    #[test] fn buffered_events_keep_ten_distinct_taps() {
        let mut track = track();
        let taps: Vec<_> = (0..10).filter_map(|i| {
            track.on_btn(1, event_time(&event(i * 200)).unwrap());
            track.on_btn(0, event_time(&event(i * 200 + 50)).unwrap())
        }).collect();
        assert_eq!(taps.len(), 10);
        assert!(taps.windows(2).all(|w| w[1] - w[0] >= MIN_TAP_INTERVAL));
        assert!(taps[9] - taps[0] < ms(5000));
    }
    #[test] fn long_press_swipe_and_orphan_release_are_not_taps() {
        let mut track = track();
        assert!(track.on_btn(0, ms(10)).is_none());
        track.on_btn(1, ms(100));
        assert!(track.on_btn(0, ms(501)).is_none());
        track.on_btn(1, ms(600));
        track.on_pos(true, 20);
        track.on_pos(true, 200);
        assert!(track.on_btn(0, ms(700)).is_none());
    }
    #[test] fn stale_coordinates_and_duplicate_press_do_not_break_taps() {
        let mut track = track();
        track.on_pos(true, 1000);
        track.on_btn(1, ms(100));
        track.on_btn(1, ms(120));
        track.on_pos(true, 10);
        assert_eq!(track.on_btn(0, ms(160)), Some(ms(160)));
    }
    #[test] fn bad_timestamps_and_overflowing_coordinates_are_safe() {
        let mut event = event(100);
        event[..8].copy_from_slice(&(-1i64).to_ne_bytes());
        assert!(event_time(&event).is_none());
        let mut track = track();
        track.on_btn(1, ms(100));
        assert!(track.on_btn(0, ms(90)).is_none());
        track.on_btn(1, ms(200));
        track.on_pos(true, i32::MIN);
        track.on_pos(true, i32::MAX);
        assert!(track.on_btn(0, ms(250)).is_none());
    }
    #[test] fn bundled_executable_is_recognized_without_substring_matches() {
        assert!(is_watcher_executable("/data/app/pkg/lib/arm64/libxeccli.so"));
        assert!(is_watcher_executable("/data/adb/ksud"));
        assert!(is_watcher_executable("/data/adb/xudc"));
        assert!(!is_watcher_executable("/tmp/not-ksud"));
        assert!(!is_watcher_executable("/tmp/libxeccli.so.backup"));
    }
}
'''
with tempfile.TemporaryDirectory(prefix='xec-stealth-test-') as directory:
    path = Path(directory)
    (path / 'test.rs').write_text('#![allow(dead_code)]\nuse std::time::Duration;\n' + executable + touch + tests, encoding='utf-8')
    subprocess.run(['rustc', '--edition=2024', '--test', str(path / 'test.rs'), '-o', str(path / 'test')], check=True)
    subprocess.run([str(path / 'test')], check=True)
