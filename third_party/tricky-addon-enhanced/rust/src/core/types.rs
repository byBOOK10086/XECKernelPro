use std::fmt;
use std::str::FromStr;
use serde::{Serialize, Deserialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum RootManager {
    Magisk,
    KernelSU,
    APatch,
}

impl RootManager {
    pub fn name(&self) -> &'static str {
        match self {
            Self::Magisk => "Magisk",
            Self::KernelSU => "KernelSU",
            Self::APatch => "APatch",
        }
    }

    pub fn base_dir(&self) -> &'static str {
        match self {
            Self::Magisk => "/data/adb/magisk",
            Self::KernelSU => "/data/adb/ksu",
            Self::APatch => "/data/adb/ap",
        }
    }

    pub fn busybox_path(&self) -> &'static str {
        match self {
            Self::Magisk => "/data/adb/magisk/busybox",
            Self::KernelSU => "/data/adb/ksu/bin/busybox",
            Self::APatch => "/data/adb/ap/bin/busybox",
        }
    }

    pub fn modules_dir(&self) -> &'static str {
        "/data/adb/modules"
    }
}

impl fmt::Display for RootManager {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Magisk => write!(f, "magisk"),
            Self::KernelSU => write!(f, "kernelsu"),
            Self::APatch => write!(f, "apatch"),
        }
    }
}

impl FromStr for RootManager {
    type Err = anyhow::Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        match s.to_ascii_lowercase().as_str() {
            "magisk" => Ok(Self::Magisk),
            "kernelsu" | "ksu" => Ok(Self::KernelSU),
            "apatch" => Ok(Self::APatch),
            other => anyhow::bail!("unknown root manager: {other}"),
        }
    }
}
