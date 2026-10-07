use std::path::Path;
use crate::platform::fs::atomic_write;

const TARGET_FILE: &str = "/data/adb/tricky_store/target.txt";

pub fn read_target() -> anyhow::Result<Vec<String>> {
    let path = Path::new(TARGET_FILE);
    if !path.exists() {
        return Ok(Vec::new());
    }
    let content = std::fs::read_to_string(path)?;
    Ok(content
        .lines()
        .map(|l| l.trim())
        .filter(|l| !l.is_empty() && !l.starts_with('#'))
        .map(|l| strip_suffix(l).to_string())
        .collect())
}

pub fn read_target_raw() -> anyhow::Result<Vec<String>> {
    let path = Path::new(TARGET_FILE);
    if !path.exists() {
        return Ok(Vec::new());
    }
    let content = std::fs::read_to_string(path)?;
    Ok(content
        .lines()
        .map(|l| l.trim().to_string())
        .filter(|l| !l.is_empty())
        .collect())
}

pub fn write_target(entries: &[String]) -> anyhow::Result<()> {
    let mut content = entries.join("\n");
    if !content.is_empty() {
        content.push('\n');
    }
    atomic_write(Path::new(TARGET_FILE), content.as_bytes())
}

pub fn add_package(pkg: &str, exclude_list: &[String]) -> anyhow::Result<bool> {
    if is_excluded(pkg, exclude_list) {
        return Ok(false);
    }

    let lines = read_target_raw()?;
    let bare = strip_suffix(pkg);

    for line in &lines {
        if strip_suffix(line) == bare {
            return Ok(false);
        }
    }

    let mut lines = lines;
    lines.push(pkg.to_string());
    write_target(&lines)?;
    Ok(true)
}

pub fn remove_package(pkg: &str) -> anyhow::Result<bool> {
    let lines = read_target_raw()?;
    let original_len = lines.len();
    let bare = strip_suffix(pkg);
    let filtered: Vec<String> = lines
        .into_iter()
        .filter(|l| strip_suffix(l) != bare)
        .collect();

    let changed = filtered.len() != original_len;
    if changed {
        write_target(&filtered)?;
    }
    Ok(changed)
}

fn is_excluded(pkg: &str, exclude_list: &[String]) -> bool {
    exclude_list.iter().any(|pattern| {
        if pattern.ends_with('*') {
            let prefix = &pattern[..pattern.len() - 1];
            pkg.starts_with(prefix)
        } else {
            pkg == pattern
        }
    })
}

fn strip_suffix(s: &str) -> &str {
    s.trim_end_matches('!').trim_end_matches('?')
}
