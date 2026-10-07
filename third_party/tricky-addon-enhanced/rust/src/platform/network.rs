use std::io::Read;

pub fn is_online() -> bool {
    std::process::Command::new("ping")
        .args(["-c", "1", "-w", "5", "api.github.com"])
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

pub fn wait_for_network(max_attempts: u32) -> bool {
    for i in 0..max_attempts {
        if is_online() {
            return true;
        }
        std::thread::sleep(std::time::Duration::from_secs(1 << i.min(4)));
    }
    false
}

pub fn download(url: &str) -> anyhow::Result<Vec<u8>> {
    let resp = ureq::get(url)
        .timeout(std::time::Duration::from_secs(30))
        .call()?;
    let mut body = Vec::new();
    resp.into_reader().take(10 * 1024 * 1024).read_to_end(&mut body)?;
    Ok(body)
}

pub fn download_text(url: &str) -> anyhow::Result<String> {
    let bytes = download(url)?;
    Ok(String::from_utf8(bytes)?)
}
