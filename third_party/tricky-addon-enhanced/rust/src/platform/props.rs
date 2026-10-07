use std::process::Command;
use resetprop::PropSystem;

pub fn getprop(sys: &PropSystem, name: &str) -> Option<String> {
    sys.get(name)
}

pub fn set(sys: &PropSystem, name: &str, value: &str) -> anyhow::Result<()> {
    sys.set(name, value).map_err(|e| anyhow::anyhow!("{e}"))
}

pub fn delete(sys: &PropSystem, name: &str) -> anyhow::Result<()> {
    sys.delete(name)?;
    Ok(())
}

// One-off ops without an existing PropSystem — for callers outside the prop pipeline
pub fn getprop_once(name: &str) -> Option<String> {
    PropSystem::open().ok()?.get(name)
}

pub fn set_once(name: &str, value: &str) -> anyhow::Result<()> {
    let sys = PropSystem::open().map_err(|e| anyhow::anyhow!("{e}"))?;
    sys.set(name, value).map_err(|e| anyhow::anyhow!("{e}"))
}

// No library equivalent — blocks until property reaches value
pub fn resetprop_wait(name: &str, value: &str) -> anyhow::Result<()> {
    let status = Command::new("resetprop")
        .args(["-w", name, value])
        .status()?;
    if status.success() {
        Ok(())
    } else {
        anyhow::bail!("resetprop -w {name} {value} timed out")
    }
}
