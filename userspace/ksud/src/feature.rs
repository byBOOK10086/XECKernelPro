use crate::sulog;
use anyhow::{Context, Result, bail};
use const_format::concatcp;
use std::collections::HashMap;
#[path = "feature_store.rs"]
mod feature_store;
use std::path::Path;

use crate::defs;
use crate::feature_id::{FeatureId, parse_feature_id};

const FEATURE_CONFIG_PATH: &str = concatcp!(defs::WORKING_DIR, ".feature_config");
fn on_feature_applied(feature_id: FeatureId, value: u64) {
    if feature_id == FeatureId::Sulog
        && value != 0
        && let Err(err) = sulog::ensure_sulogd_running()
    {
        log::warn!("failed to ensure sulogd is running after feature init: {err:#}");
    }
}

fn set_kernel_feature(feature_id: FeatureId, value: u64) -> Result<()> {
    crate::ksucalls::set_feature(feature_id as u32, value)
        .with_context(|| format!("Failed to set feature {} to {value}", feature_id.name()))?;

    on_feature_applied(feature_id, value);

    Ok(())
}

fn is_forced(id: u32) -> bool {
    crate::ksucalls::get_feature(id).is_ok_and(|state| state.forced)
}

pub fn load_binary_config() -> Result<HashMap<u32, u64>> {
    feature_store::load(Path::new(FEATURE_CONFIG_PATH)).context("Failed to load feature config")
}
pub fn apply_config(features: &HashMap<u32, u64>) {
    log::info!("Applying feature configuration to kernel...");

    let mut applied = 0;
    for (&id, &value) in features {
        if is_forced(id) {
            log::info!("Skip feature {id}: forced by boot config");
            continue;
        }
        match FeatureId::from_u32(id) {
            Some(feature_id) => match set_kernel_feature(feature_id, value) {
                Ok(()) => {
                    log::info!("Set feature {} to {value}", feature_id.name());
                    applied += 1;
                }
                Err(e) => {
                    log::warn!("Failed to set feature {}: {e}", feature_id.name());
                }
            },
            None => match crate::ksucalls::set_feature(id, value) {
                Ok(()) => {
                    log::info!("Set feature {id} to {value}");
                    applied += 1;
                }
                Err(e) => {
                    log::warn!("Failed to set feature {id}: {e}");
                }
            },
        }
    }

    log::info!("Applied {applied} features successfully");
}

pub fn get_feature(id: &str) -> Result<()> {
    let feature_id = parse_feature_id(id)?;
    let state = crate::ksucalls::get_feature(feature_id as u32)
        .with_context(|| format!("Failed to get feature {id}"))?;

    if !state.supported {
        println!("Feature '{id}' is not supported by kernel");
        return Ok(());
    }

    println!("Feature: {} ({})", feature_id.name(), feature_id as u32);
    println!("Description: {}", feature_id.description());
    println!("Value: {}", state.value);
    println!(
        "Status: {}",
        if state.value != 0 {
            "enabled"
        } else {
            "disabled"
        }
    );
    if state.forced {
        println!("Forced: by boot config");
    }

    Ok(())
}

pub fn get_feature_config(id: &str) -> Result<()> {
    let feature_id = parse_feature_id(id)?;

    let features = load_binary_config()?;
    let id_u32 = feature_id as u32;

    println!("Feature: {} ({})", feature_id.name(), id_u32);
    println!("Description: {}", feature_id.description());

    if let Some(value) = features.get(&id_u32) {
        println!("Value: {value}");
        println!(
            "Status: {}",
            if *value != 0 { "enabled" } else { "disabled" }
        );
    } else {
        println!("Not set in config");
    }

    Ok(())
}

pub fn set_feature(id: &str, value: u64) -> Result<()> {
    let feature_id = parse_feature_id(id)?;

    if let Ok(state) = crate::ksucalls::get_feature(feature_id as u32)
        && state.forced
    {
        bail!(
            "Feature '{}' is forced to {} by boot config. Direct modification is not allowed.",
            feature_id.name(),
            state.value
        );
    }

    // Check if this feature is managed by any module
    if let Ok(managed_features_map) = crate::module::get_managed_features() {
        // Find which modules manage this feature
        let managing_modules: Vec<&String> = managed_features_map
            .iter()
            .filter(|(_, features)| features.iter().any(|f| f == feature_id.name()))
            .map(|(module_id, _)| module_id)
            .collect();

        if !managing_modules.is_empty() {
            // Feature is managed, check if caller is an authorized module
            let caller_module = std::env::var("KSU_MODULE").unwrap_or_default();

            if caller_module.is_empty() || !managing_modules.contains(&&caller_module) {
                bail!(
                    "Feature '{}' is managed by module(s): {}. Direct modification is not allowed.",
                    feature_id.name(),
                    managing_modules
                        .iter()
                        .map(|s| s.as_str())
                        .collect::<Vec<_>>()
                        .join(", ")
                );
            }

            log::info!(
                "Module '{caller_module}' is setting managed feature '{}'",
                feature_id.name()
            );
        }
    }

    set_kernel_feature(feature_id, value)?;

    println!(
        "Feature '{}' set to {value} ({})",
        feature_id.name(),
        if value != 0 { "enabled" } else { "disabled" }
    );

    Ok(())
}

pub fn list_features() {
    println!("Available Features:");
    println!("{}", "=".repeat(80));

    // Get managed features from modules
    let managed_features_map = crate::module::get_managed_features().unwrap_or_default();

    // Build a reverse map: feature_name -> Vec<module_id>
    let mut feature_to_modules: HashMap<String, Vec<String>> = HashMap::new();
    for (module_id, feature_list) in &managed_features_map {
        for feature_name in feature_list {
            feature_to_modules
                .entry(feature_name.clone())
                .or_default()
                .push(module_id.clone());
        }
    }

    for feature_id in &FeatureId::ALL {
        let id = *feature_id as u32;
        let state = crate::ksucalls::get_feature(id).unwrap_or_default();

        let status = if !state.supported {
            "NOT_SUPPORTED".to_string()
        } else if state.value != 0 {
            format!("ENABLED ({})", state.value)
        } else {
            "DISABLED".to_string()
        };

        let managed_by = feature_to_modules.get(feature_id.name());
        let managed_mark = if state.forced {
            " [FORCED]"
        } else if managed_by.is_some() {
            " [MODULE_MANAGED]"
        } else {
            ""
        };

        println!(
            "[{}] {} (ID={}){}",
            status,
            feature_id.name(),
            id,
            managed_mark
        );
        println!("    {}", feature_id.description());

        if let Some(modules) = managed_by {
            println!(
                "    ⚠️  Managed by module(s): {} (forced to 0 on initialization)",
                modules.join(", ")
            );
        }

        println!();
    }
}

pub fn load_config_and_apply() -> Result<()> {
    let features = load_binary_config()?;

    if features.is_empty() {
        println!("No features found in config file");
        return Ok(());
    }

    apply_config(&features);
    println!("Feature configuration loaded and applied");
    Ok(())
}

pub fn save_config(id: Option<&str>, value: Option<u64>) -> Result<()> {
    let selected = id.map(parse_feature_id).transpose()?;
    if value.is_some() && selected.is_none() {
        bail!("A feature ID is required with a value");
    }
    feature_store::update(Path::new(FEATURE_CONFIG_PATH), |features| {
        if let Some(feature) = selected {
            let state = crate::ksucalls::get_feature(feature as u32)
                .map_err(|err| std::io::Error::other(err.to_string()))?;
            if !state.supported || state.forced {
                return Err(std::io::Error::other("Feature unsupported or forced"));
            }
            features.insert(feature as u32, value.unwrap_or(state.value));
        } else {
            // Legacy no-argument save remains a complete runtime snapshot.
            for feature in FeatureId::ALL {
                let state = crate::ksucalls::get_feature(feature as u32)
                    .map_err(|err| std::io::Error::other(err.to_string()))?;
                if state.supported && !state.forced {
                    features.insert(feature as u32, state.value);
                }
            }
        }
        Ok(())
    })
    .context("Failed to save feature config")?;
    println!("Feature configuration saved");
    Ok(())
}
pub fn check_feature(id: &str) -> Result<()> {
    let feature_id = parse_feature_id(id)?;

    // Forced by boot config takes precedence over module management
    if crate::ksucalls::get_feature(feature_id as u32).is_ok_and(|state| state.forced) {
        println!("forced");
        return Ok(());
    }

    // Check if this feature is managed by any module
    let managed_features_map = crate::module::get_managed_features().unwrap_or_default();
    let is_managed = managed_features_map
        .values()
        .any(|features| features.iter().any(|f| f == feature_id.name()));

    if is_managed {
        println!("managed");
        return Ok(());
    }

    // Check if the feature is supported by kernel
    let state = crate::ksucalls::get_feature(feature_id as u32)
        .with_context(|| format!("Failed to get feature {id}"))?;

    if state.supported {
        println!("supported");
    } else {
        println!("unsupported");
    }

    Ok(())
}

/// Forced features are applied by the kernel itself, only do the userspace part.
/// Runs regardless of safe mode and the persisted config, as the kernel applies
/// them in both cases.
pub fn init_forced_features() {
    for feature_id in FeatureId::ALL {
        if let Ok(state) = crate::ksucalls::get_feature(feature_id as u32)
            && state.forced
        {
            log::info!(
                "Feature '{}' is forced to {} by boot config, skip loading",
                feature_id.name(),
                state.value
            );
            on_feature_applied(feature_id, state.value);
        }
    }
}

pub fn init_features() -> Result<()> {
    log::info!("Initializing features from config...");

    feature_store::update(Path::new(FEATURE_CONFIG_PATH), |features| {

    // Get managed features from active modules and skip them during init
    if let Ok(managed_features_map) = crate::module::get_managed_features() {
        if !managed_features_map.is_empty() {
            log::info!(
                "Found {} modules managing features",
                managed_features_map.len()
            );

            // Build a set of all managed feature IDs to skip
            for (module_id, feature_list) in &managed_features_map {
                log::info!(
                    "Module '{module_id}' manages {} feature(s)",
                    feature_list.len()
                );

                for feature_name in feature_list {
                    if let Ok(feature_id) = parse_feature_id(feature_name) {
                        let feature_id_u32 = feature_id as u32;
                        // Remove managed features from config, let modules control them
                        if features.remove(&feature_id_u32).is_some() {
                            log::info!(
                                "  - Skipping managed feature '{feature_name}' (controlled by module: {module_id})",
                            );
                        } else {
                            log::info!(
                                "  - Feature '{feature_name}' is managed by module '{module_id}', skipping",
                            );
                        }
                    } else {
                        log::warn!(
                            "  - Unknown managed feature '{feature_name}' from module '{module_id}', ignoring",
                        );
                    }
                }
            }
        }
    } else {
        log::warn!(
            "Failed to get managed features from modules, continuing with normal initialization"
        );
    }

    if features.is_empty() {
        log::info!("No features to apply, skipping initialization");
        return Ok(());
    }

    apply_config(features);

    // Save the configuration (excluding managed features)
    Ok(())
    }).context("Failed to initialize feature configuration")?;
    log::info!("Saved feature configuration to file");

    Ok(())
}
