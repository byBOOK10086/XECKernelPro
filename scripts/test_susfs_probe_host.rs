#[path = "../userspace/ksud/src/susfs_capabilities.rs"]
mod capabilities;

fn main() {
    assert!(capabilities::probe_result(0, 0).is_ok());
    assert!(capabilities::probe_result(-22, 0).is_err());
    assert!(capabilities::probe_result(0, 126).is_err());
}
