use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt, EnvFilter};
use crate::config::LoggingConfig;

pub fn init(verbose_flag: bool, config: &LoggingConfig) -> anyhow::Result<()> {
    let verbose = verbose_flag
        || config.level == "trace"
        || std::path::Path::new("/data/adb/tricky_store/.verbose").exists();

    let level = if verbose { "trace" } else { &config.level };

    let env_filter = EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| EnvFilter::new(level));

    tracing_subscriber::registry()
        .with(env_filter)
        .with(tracing_subscriber::fmt::layer().with_writer(std::io::stderr))
        .init();

    Ok(())
}
