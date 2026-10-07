pub mod watcher;
pub mod target;

use serde::Serialize;
use crate::config::Config;
use crate::cli::AutomationAction;

#[derive(Debug, Serialize)]
pub struct DaemonStatus {
    pub running: bool,
    pub pid: Option<u32>,
    pub target_count: u32,
    pub last_activity: Option<String>,
}

pub fn handle_automation(action: AutomationAction, cfg: &Config) -> anyhow::Result<()> {
    if !cfg.automation.enabled {
        println!("automation disabled");
        return Ok(());
    }

    match action {
        AutomationAction::Status => {
            let status = watcher::show_status();
            println!("{}", serde_json::to_string_pretty(&status)?);
            Ok(())
        }
        AutomationAction::Check => {
            let added = watcher::check_new_packages(&cfg.automation.exclude_list, None)?;
            println!("added {added} new packages to target");
            Ok(())
        }
        AutomationAction::Cleanup => {
            let removed = watcher::cleanup_dead_apps()?;
            println!("removed {removed} stale entries from target");
            Ok(())
        }
    }
}
