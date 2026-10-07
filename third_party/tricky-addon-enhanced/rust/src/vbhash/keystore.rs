use std::process::Command;

use anyhow::{Context, Result};
use tracing::debug;

pub fn extract_from_apk() -> Result<String> {
    let output = Command::new("app_process")
        .args([
            "/system/bin",
            "android.security.keystore.KeyStoreException",
        ])
        .output()
        .context("app_process invocation failed")?;

    let stdout = String::from_utf8_lossy(&output.stdout);

    for line in stdout.lines() {
        let trimmed = line.trim();
        if trimmed.len() == 64 && trimmed.chars().all(|c| c.is_ascii_hexdigit()) {
            debug!("extracted vbhash via app_process");
            return Ok(trimmed.to_ascii_lowercase());
        }
    }

    anyhow::bail!("no valid vbmeta hash found in app_process output")
}

