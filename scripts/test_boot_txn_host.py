"""Execute transaction state/parser tests without Android devices or partitions."""
import os, pathlib, tempfile, subprocess
root=pathlib.Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix="xec-txn-tests-") as d:
    p=pathlib.Path(d)
    (p/"src").mkdir()
    (p/"Cargo.toml").write_text('[package]\nname="xec-txn-policy-tests"\nversion="0.1.0"\nedition="2024"\n[dependencies]\nanyhow="1"\nsha256="1"\n',encoding="utf-8")
    txn=(root/"userspace/ksud/src/boot_txn.rs").as_posix()
    harness=r'''
mod defs {
    pub const KSU_BACKUP_DIR: &str = "/nonexistent-xec-test-backup";
    pub const BOOT_TXN_STATE: &str = "boot_txn.state";
    pub const BOOT_TXN_STATE_TMP: &str = ".boot_txn.tmp";
    pub const BOOT_TXN_BACKUP_PREFIX: &str = "boot_txn_backup_";
}
mod boot_patch {
    pub fn flash_partition(_: &str, _: &[u8]) -> anyhow::Result<()> {
        panic!("host policy tests must never flash partitions")
    }
}
mod utils {
    pub fn getprop(_: &str) -> Option<String> {
        panic!("pure tests must supply their own slot")
    }
}
mod assets { pub const BOOTCTL_PATH: &str = "/nonexistent-xec-test-bootctl"; }
#[path = "TXN_SOURCE"]
mod boot_txn;
'''
    (p/"src/lib.rs").write_text(harness.replace("TXN_SOURCE",txn),encoding="utf-8")
    toolchain = os.environ.get("BOOT_TXN_TEST_TOOLCHAIN")
    command = ["cargo"] + (["+" + toolchain] if toolchain else [])
    subprocess.run(command + ["test", "--manifest-path", str(p/"Cargo.toml")], check=True)
