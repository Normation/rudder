// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

#![allow(dead_code)]

mod archive;
mod cli;
mod cmd;
mod config;
mod database;
mod dependency;
mod info;
mod license;
mod list;
mod plugin;
mod repo_index;
mod repository;
mod signature;
mod versions;
mod webapp;

use std::{
    fs::create_dir_all,
    path::{Path, PathBuf},
    process,
};

use anyhow::{anyhow, bail, Context, Result};
use clap::Parser;
use cli::Args;
use tracing::{debug, error, info, warn};

use crate::{
    cli::Command,
    config::Configuration,
    database::Database,
    info::display_info,
    license::Licenses,
    list::ListOutput,
    plugin::{long_names, short_name},
    repo_index::RepoIndex,
    repository::Repository,
    signature::SignatureVerifier,
    versions::RudderVersion,
    webapp::Webapp,
};

const PACKAGES_FOLDER: &str = "/var/rudder/packages";
const LICENSES_FOLDER: &str = "/opt/rudder/etc/plugins/licenses";
const WEBAPP_XML_PATH: &str = "/opt/rudder/share/webapps/rudder.xml";
const PACKAGES_DATABASE_PATH: &str = "/var/rudder/packages/index.json";
const PACKAGE_SCRIPTS_ARCHIVE: &str = "scripts.txz";
const CONFIG_PATH: &str = "/opt/rudder/etc/rudder-pkg/rudder-pkg.conf";
const SIGNATURE_KEYRING_PATH: &str = "/opt/rudder/etc/rudder-pkg/rudder_plugins_key.gpg";
const RUDDER_VERSION_PATH: &str = "/opt/rudder/share/versions/rudder-server-version";
const REPOSITORY_INDEX_PATH: &str = "/var/rudder/tmp/plugins/rpkg.index";
const TMP_PLUGINS_FOLDER: &str = "/var/rudder/tmp/plugins";

fn am_i_root() -> Result<bool> {
    let out = process::Command::new("id").arg("--user").output()?;
    let uid = String::from_utf8_lossy(&out.stdout)
        .strip_suffix('\n')
        .unwrap()
        .parse::<usize>()?;
    Ok(uid == 0)
}

/// CLI entry point
pub fn run() -> Result<()> {
    // Read CLI args
    let args = cli::Args::parse();

    // Setup logger early
    rudder_cli::logs::init(
        if args.debug { 1 } else { 0 },
        false,
        rudder_cli::logs::OutputFormat::Human,
    );

    // Abort of not run as root
    // Ignore on error
    #[cfg(not(debug_assertions))]
    if let Ok(false) = am_i_root() {
        bail!("This program needs to run as root, aborting.");
    }

    let r = run_inner(args);
    if let Err(ref e) = r {
        error!("{:?}", e);
    }
    r
}

pub fn run_inner(args: Args) -> Result<()> {
    // Parse configuration file
    debug!("Parsed CLI arguments: {args:?}");
    let cfg = Configuration::read(Path::new(&args.config))
        .with_context(|| format!("Reading configuration from '{}'", &args.config))?;
    debug!("Parsed configuration: {cfg:?}");

    // Now initialize all common data structures
    let verifier = SignatureVerifier::new(PathBuf::from(SIGNATURE_KEYRING_PATH));
    let repo = Repository::new(&cfg, verifier)?;
    let webapp_version = RudderVersion::from_path(RUDDER_VERSION_PATH)?;
    let mut webapp = Webapp::new(PathBuf::from(WEBAPP_XML_PATH), webapp_version);
    let mut db = Database::read(Path::new(PACKAGES_DATABASE_PATH))?;

    create_dir_all(TMP_PLUGINS_FOLDER).context("Create temporary directory")?;

    match args.command {
        Command::Install { force, package } => {
            let index = RepoIndex::from_path(REPOSITORY_INDEX_PATH)?;
            let mut errors = false;
            for p in &long_names(package) {
                if let Err(e) = db.install(force, p, &repo, index.as_ref(), &mut webapp) {
                    errors = true;
                    error!("Installation of {} failed: {e:?}", short_name(p));
                }
            }
            if errors {
                bail!("Some plugin installation failed");
            } else {
                info!("Installation ran successfully");
            }
        }
        Command::Uninstall { package } => {
            let mut errors = false;
            for p in &long_names(package) {
                if let Err(e) = db.uninstall(p, true, &mut webapp) {
                    errors = true;
                    error!("Uninstallation of {} failed: {e:?}", short_name(p));
                }
            }
            if errors {
                bail!("Some plugin uninstallation failed");
            } else {
                info!("Uninstallation ran successfully");
            }
        }
        Command::Upgrade {
            all,
            package,
            all_postinstall,
        } => {
            let index = RepoIndex::from_path(REPOSITORY_INDEX_PATH)?;
            if all_postinstall {
                let mut errors = false;
                for p in db.plugins.values() {
                    if let Err(e) = p.metadata.run_package_script(
                        archive::PackageScript::Postinst,
                        archive::PackageScriptArg::Upgrade,
                    ) {
                        errors = true;
                        // Don't fail and continue
                        error!(
                            "Postinst script for {} failed: {e:?}",
                            p.metadata.short_name()
                        );
                    }
                }
                if errors {
                    bail!("Some scripts failed");
                } else {
                    info!("All postinstall scripts ran successfully");
                }
            } else {
                // Normal upgrades
                let to_upgrade: Vec<String> = if all {
                    db.plugins.keys().cloned().collect()
                } else {
                    let packages = long_names(package);
                    for p in &packages {
                        if db.plugins.get(p).is_none() {
                            bail!(
                                "Plugin {} is not installed, stopping upgrade",
                                short_name(p)
                            )
                        }
                    }
                    packages
                };
                let mut errors = false;
                for p in &to_upgrade {
                    if let Err(e) = db.install(false, p, &repo, index.as_ref(), &mut webapp) {
                        errors = true;
                        error!("Could not upgrade {}: {e:?}", short_name(p))
                    }
                }
                if errors {
                    bail!("Some plugins were not upgraded correctly");
                } else {
                    info!("All plugins were upgraded successfully");
                }
            }
        }
        Command::List {
            all,
            enabled,
            format,
        } => {
            let index = RepoIndex::from_path(REPOSITORY_INDEX_PATH)?;
            let licenses = Licenses::from_path(Path::new(LICENSES_FOLDER))?;
            ListOutput::new(all, enabled, &licenses, &db, index.as_ref(), &webapp)?
        }
        .display(format)?,
        Command::Show { package } => {
            for p in long_names(package) {
                println!(
                    "{}",
                    db.plugins
                        .get(&p)
                        .ok_or_else(|| anyhow!("Could not find plugin"))?
                        .metadata
                )
            }
        }
        Command::Update {
            check,
            if_available,
        } => {
            if check {
                repo.test_connection()?;
            } else {
                if if_available {
                    if repo.test_connection().is_err() {
                        info!("Repository is not reachable, stopping");
                    } else {
                        repo.update(&webapp)?;
                    }
                } else {
                    repo.update(&webapp)?;
                }
                info!("Index and licenses successfully updated")
            }
        }
        Command::Enable {
            package,
            all,
            save,
            restore,
        } => {
            // If all is passed, enabled all installed plugins
            let to_enable: Vec<String> = if all {
                db.plugins.keys().cloned().collect()
            } else {
                long_names(package)
            };
            if to_enable.is_empty() {
                let backup_path = Path::new(TMP_PLUGINS_FOLDER).join("plugins_status.backup");
                if save {
                    db.enabled_plugins_save(&backup_path, &mut webapp)?;
                    info!("Plugins status successfully saved");
                } else if restore {
                    db.enabled_plugins_restore(&backup_path, &mut webapp)?;
                    info!("Plugins status successfully restored");
                } else {
                    bail!("No plugin provided");
                }
            } else {
                let mut errors = false;
                for p in &to_enable {
                    match db.plugins.get(p) {
                        None => {
                            warn!("Plugin {} not installed", short_name(p))
                        }
                        Some(p) => {
                            if let Err(e) = p.enable(&mut webapp) {
                                errors = true;
                                error!(
                                    "Could not enable plugin {}: {e:?}",
                                    p.metadata.short_name()
                                );
                            }
                        }
                    }
                }
                if errors {
                    error!("Some plugins could not be enabled");
                } else {
                    info!("Plugins successfully enabled");
                }
            }
        }
        Command::Disable {
            package,
            all,
            incompatible,
        } => {
            let to_disable: Vec<String> = if all {
                db.plugins.keys().cloned().collect()
            } else if incompatible {
                db.plugins
                    .iter()
                    .filter(|(_, p)| {
                        !webapp
                            .version
                            .is_compatible(&p.metadata.version.rudder_version)
                    })
                    .map(|(p, _)| p.to_string())
                    .collect()
            } else {
                long_names(package)
            };

            let mut errors = false;
            for p in &to_disable {
                match db.plugins.get(p) {
                    None => {
                        warn!("Plugin {} not installed", short_name(p))
                    }
                    Some(p) => {
                        if let Err(e) = p.disable(&mut webapp) {
                            errors = true;
                            error!(
                                "Could not disable plugin {}: {e:?}",
                                p.metadata.short_name()
                            );
                        }
                    }
                }
            }
            if errors {
                error!("Some plugins could not be disabled");
            } else {
                info!("Plugins successfully disabled");
            }
        }
        Command::Info {} => {
            let index = RepoIndex::from_path(REPOSITORY_INDEX_PATH)?;
            let licenses = Licenses::from_path(Path::new(LICENSES_FOLDER))?;
            display_info(&licenses, &repo, index.as_ref())
        }
    }
    // Restart if needed
    webapp.apply_changes()?;
    Ok(())
}

#[cfg(test)]
mod tests {
    #[test]
    fn i_am_not_root() {
        assert!(!super::am_i_root().unwrap())
    }
}
