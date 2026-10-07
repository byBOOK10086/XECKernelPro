use anyhow::{bail, Result};

const CHECKS: &[(&str, &str)] = &[
    ("<AndroidAttestation", "missing <AndroidAttestation> opening tag"),
    ("</AndroidAttestation>", "missing </AndroidAttestation> closing tag"),
    ("<Keybox", "missing <Keybox element"),
    ("<Key algorithm=", "missing <Key algorithm= element"),
    ("<PrivateKey", "missing <PrivateKey element"),
    ("BEGIN CERTIFICATE", "missing certificate block"),
];

pub fn validate(data: &[u8]) -> Result<()> {
    if data.is_empty() {
        bail!("keybox data is empty");
    }
    let text = std::str::from_utf8(data).unwrap_or("");
    if text.is_empty() {
        bail!("keybox data is not valid UTF-8");
    }
    let mut errors = Vec::new();
    for (needle, msg) in CHECKS {
        if !text.contains(needle) {
            errors.push(*msg);
        }
    }
    if errors.is_empty() {
        Ok(())
    } else {
        bail!("keybox validation failed: {}", errors.join("; "))
    }
}

pub fn validate_file(path: &std::path::Path) -> Result<()> {
    if !path.exists() {
        bail!("keybox file not found: {}", path.display());
    }
    let data = std::fs::read(path)?;
    validate(&data)
}
