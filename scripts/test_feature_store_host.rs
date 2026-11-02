// Run: rustc --edition 2024 scripts/test_feature_store_host.rs -o /tmp/test_feature_store && /tmp/test_feature_store
// Standalone Linux test shim for the same libc calls/constants used by production.
#[cfg(unix)]
extern crate self as libc;
#[cfg(unix)]
pub const O_NOFOLLOW: i32 = 0x20000;
#[cfg(unix)]
pub const O_CLOEXEC: i32 = 0x80000;
#[cfg(unix)]
pub const LOCK_EX: i32 = 2;
#[cfg(unix)]
unsafe extern "C" { pub fn flock(fd: i32, operation: i32) -> i32; }
#[path = "../userspace/ksud/src/feature_store.rs"]
mod feature_store;
use std::{path::PathBuf, process::Command};
fn main() {
    let args: Vec<_> = std::env::args().collect();
    if args.len() == 4 {
        let path = PathBuf::from(&args[1]);
        let id: u32 = args[2].parse().unwrap();
        for _ in 0..30 {
            feature_store::update(&path, |map| { *map.entry(id).or_default() += 1; Ok(()) }).unwrap();
        }
        return;
    }
    let dir = std::env::current_dir().unwrap().join(format!("xec-feature-test-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("config");
    feature_store::update(&path, |map| { map.insert(0, 1); Ok(()) }).unwrap();
    feature_store::update(&path, |map| { map.insert(1, 0); Ok(()) }).unwrap();
    assert_eq!(feature_store::load(&path).unwrap().get(&0), Some(&1));
    let exe = std::env::current_exe().unwrap();
    let mut children = Vec::new();
    for id in 2..10 {
        children.push(Command::new(&exe).arg(&path).arg(id.to_string()).arg("child").spawn().unwrap());
    }
    for mut child in children { assert!(child.wait().unwrap().success()); }
    let map = feature_store::load(&path).unwrap();
    for id in 2..10 { assert_eq!(map.get(&id), Some(&30)); }
    assert_eq!(map.get(&0), Some(&1));
    let corrupt = dir.join("corrupt");
    std::fs::write(&corrupt, b"broken").unwrap();
    assert!(feature_store::update(&corrupt, |map| { map.insert(0, 0); Ok(()) }).is_err());
    assert_eq!(std::fs::read(&corrupt).unwrap(), b"broken");
    let oversized = dir.join("oversized");
    std::fs::write(&oversized, vec![0; 65_537]).unwrap();
    assert!(feature_store::load(&oversized).is_err());
    let before = std::fs::read(&path).unwrap();
    assert!(feature_store::update(&path, |map| {
        map.insert(999, 1);
        Err(std::io::Error::other("injected read failure"))
    }).is_err());
    assert_eq!(std::fs::read(&path).unwrap(), before);
    assert!(feature_store::update(&path, |map| {
        for id in 0..6000 { map.insert(id, 1); }
        Ok(())
    }).is_err());
    assert_eq!(std::fs::read(&path).unwrap(), before);
    println!("PASS: single-key merge, 8-process concurrency (240 commits), corrupt preservation, bounded read/write, transaction rollback");
    std::fs::remove_dir_all(dir).unwrap();
}
