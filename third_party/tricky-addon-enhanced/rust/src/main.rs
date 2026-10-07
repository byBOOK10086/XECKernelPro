use clap::Parser;
use ta_enhanced::cli::{Cli, Commands};

fn main() -> anyhow::Result<()> {
    let cli = Cli::parse();

    if !matches!(cli.command, Commands::Daemon { .. }) {
        if let Err(e) = ta_enhanced::platform::process::camouflage() {
            eprintln!("camouflage failed (non-fatal): {e}");
        }
    }

    let cfg = ta_enhanced::config::Config::load(None)?;

    // Daemon re-initializes logging after fork — skip here to avoid double-init panic
    if !matches!(cli.command, Commands::Daemon { .. }) {
        ta_enhanced::logging::init(cli.verbose, &cfg.logging)?;
    }

    ta_enhanced::platform::signal::register_shutdown_handler();
    ta_enhanced::cli::dispatch(cli.command, &cfg)
}
