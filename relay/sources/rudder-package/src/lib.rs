// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

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

#[cfg(not(debug_assertions))]
use std::process;
use std::{
    fs::create_dir_all,
    path::{Path, PathBuf},
    process::ExitCode,
};

use anyhow::{Context, Result, anyhow, bail};
use clap::Parser;
use cli::Args;
use repository::RepositoryError;
use rudder_cli::custom_panic_hook_ignore_sigpipe;
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
    versions::RudderVersion,
    webapp::Webapp,
};

const PACKAGES_FOLDER: &str = "/var/rudder/packages";
const DEFAULT_LOG_FOLDER: &str = "/var/log/rudder/rudder-pkg/";
const LICENSES_FOLDER: &str = "/opt/rudder/etc/plugins/licenses";
const WEBAPP_XML_PATH: &str = "/opt/rudder/share/webapps/rudder.xml";
const PACKAGES_DATABASE_PATH: &str = "/var/rudder/packages/index.json";
const PACKAGE_SCRIPTS_ARCHIVE: &str = "scripts.txz";
const SIGNATURE_KEYRING_PATH: &str = "/opt/rudder/etc/rudder-pkg/rudder_plugins_key.gpg";
const RUDDER_VERSION_PATH: &str = "/opt/rudder/share/versions/rudder-server-version";
const REPOSITORY_INDEX_PATH: &str = "/var/rudder/tmp/plugins/rpkg.index";
const TMP_PLUGINS_FOLDER: &str = "/var/rudder/tmp/plugins";
const PLUGIN_STATUS_BACKUP_PATH: &str = "/tmp/rudder-plugins-upgrade";
const DONT_RESTART_ENV_VAR: &str = "RUDDER_PACKAGE_DONT_RESTART";
const DONT_RUN_POSTINST_ENV_VAR: &str = "RUDDER_PACKAGE_DONT_RUN_POSTINST";

#[cfg(not(debug_assertions))]
fn am_i_root() -> Result<bool> {
    let out = process::Command::new("id").arg("--user").output()?;
    let uid = String::from_utf8_lossy(&out.stdout)
        .strip_suffix('\n')
        .unwrap()
        .parse::<usize>()?;
    Ok(uid == 0)
}

/// CLI entry point
pub fn run() -> Result<ExitCode> {
    custom_panic_hook_ignore_sigpipe();

    // Read CLI args
    let args = cli::Args::parse();

    // Setup logger early
    let log_r = rudder_cli::logs::init(
        if args.debug { 1 } else { 0 },
        args.quiet,
        rudder_cli::logs::OutputFormat::Human,
        Some((Path::new(DEFAULT_LOG_FOLDER), "rudder-pkg")),
    );
    if let Err(ref e) = log_r {
        eprintln!("{e:?}");
        return Ok(ExitCode::FAILURE);
    }

    let r = run_inner(args);
    match r {
        Err(ref e) => {
            error!("{:?}", e);
            match e.downcast_ref::<RepositoryError>() {
                Some(err) => Ok(ExitCode::from(err)),
                None => Ok(ExitCode::FAILURE),
            }
        }
        Ok(()) => Ok(ExitCode::SUCCESS),
    }
}

pub fn run_inner(args: Args) -> Result<()> {
    // Abort of not run as root
    // Ignore on error
    #[cfg(not(debug_assertions))]
    if let Ok(false) = am_i_root() {
        bail!("This program needs to run as root, aborting.");
    }

    // Parse configuration file
    debug!("Parsed CLI arguments: {args:?}");
    let cfg = Configuration::read(Path::new(&args.config))
        .with_context(|| format!("Reading configuration from '{}'", &args.config))?;
    debug!("Parsed configuration: {cfg:?}");

    // Now initialize all common data structures
    let keyring_path = PathBuf::from(SIGNATURE_KEYRING_PATH);
    let repo = Repository::new(&cfg, signature::verifier(keyring_path)?)?;
    let webapp_version = RudderVersion::from_path(RUDDER_VERSION_PATH)?;
    let mut webapp = Webapp::new(PathBuf::from(WEBAPP_XML_PATH), webapp_version);
    let mut db = Database::read(Path::new(PACKAGES_DATABASE_PATH))?;

    // Global error flag, used to exit with non-zero code
    // but not interrupt the program.
    let mut errors = false;

    create_dir_all(TMP_PLUGINS_FOLDER).with_context(|| {
        format!("Failed to create temporary directory in '{TMP_PLUGINS_FOLDER}'")
    })?;

    match args.command {
        Command::Install { force, package } => {
            let index = RepoIndex::from_path(REPOSITORY_INDEX_PATH)?;

            let to_install = long_names(package);

            if to_install.is_empty() {
                bail!("No plugin name provided");
            }

            for full_p in &to_install {
                if full_p.ends_with("-license.tar.gz") {
                    if let Err(e) =
                        Licenses::update_from_archive(Path::new(full_p), Path::new(LICENSES_FOLDER))
                    {
                        errors = true;
                        error!("Installation of licenses from {} failed: {e:?}", full_p);
                    }
                } else {
                    // Extract version numbers
                    let (p, v) = match full_p.split_once(':') {
                        None => (full_p.as_str(), None),
                        Some((p, v)) => (p, Some(v)),
                    };

                    if let Err(e) = db.install(force, p, v, &repo, index.as_ref(), &mut webapp) {
                        errors = true;
                        error!("Installation of {} failed: {e:?}", short_name(p));
                    }
                }
            }
            if errors {
                error!("Some plugin installation failed");
            } else {
                info!("Installation ran successfully");
            }
        }
        Command::Uninstall { package } => {
            let to_uninstall = long_names(package);

            if to_uninstall.is_empty() {
                bail!("No plugin name provided");
            }

            for p in &to_uninstall {
                if let Err(e) = db.uninstall(p, true, &mut webapp) {
                    errors = true;
                    error!("Uninstallation of {} failed: {e:?}", short_name(p));
                }
            }

            if errors {
                error!("Some plugin uninstallation failed");
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
                    error!("Some scripts failed");
                } else {
                    info!("All postinstall scripts ran successfully");
                }
            } else {
                // Normal upgrades
                let to_upgrade: Vec<String> = if all {
                    db.plugins.keys().cloned().collect()
                } else {
                    let packages = long_names(package);
                    if packages.is_empty() {
                        bail!("No plugin name provided");
                    }
                    for p in &packages {
                        if !db.plugins.contains_key(p) {
                            bail!(
                                "Plugin {} is not installed, stopping upgrade",
                                short_name(p)
                            )
                        }
                    }
                    packages
                };
                for p in &to_upgrade {
                    if let Err(e) = db.install(false, p, None, &repo, index.as_ref(), &mut webapp) {
                        errors = true;
                        error!("Could not upgrade {}: {e:?}", short_name(p))
                    }
                }
                if errors {
                    error!("Some plugins were not upgraded correctly");
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
            let to_show = long_names(package);

            if to_show.is_empty() {
                bail!("No plugin name provided");
            }

            for p in to_show {
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
                let backup_path = Path::new(PLUGIN_STATUS_BACKUP_PATH);
                if save {
                    db.disabled_plugins_save(backup_path, &mut webapp)?;
                    info!("Plugins status successfully saved");
                } else if restore {
                    db.enabled_plugins_restore(backup_path, &mut webapp)?;
                    info!("Plugins status successfully restored");
                } else {
                    bail!("No plugin provided");
                }
            } else {
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
                    .filter(|(_, p)| !webapp.version.is_compatible(&p.metadata.version))
                    .map(|(p, _)| p.to_string())
                    .collect()
            } else {
                let plugins = long_names(package);
                if plugins.is_empty() {
                    bail!("No plugin name provided");
                }
                plugins
            };

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

    if errors {
        bail!("Plugin action failed");
    }
    Ok(())
}
