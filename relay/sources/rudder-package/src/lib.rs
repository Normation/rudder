// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

#![allow(dead_code)]

mod archive;
mod cli;
mod cmd;
mod config;
mod database;
mod dependency;
mod list;
mod plugin;
mod repo_index;
mod repository;
mod signature;
mod versions;
mod webapp;

use std::{
    path::{Path, PathBuf},
    process,
};

use anyhow::{anyhow, bail, Context, Result};
use clap::Parser;
use log::{debug, LevelFilter};

use crate::{
    cli::Command, config::Configuration, database::Database, list::ListOutput,
    repo_index::RepoIndex, repository::Repository, signature::SignatureVerifier,
    versions::RudderVersion, webapp::Webapp,
};

const PACKAGES_FOLDER: &str = "/var/rudder/packages";
const LICENSES_FOLDER: &str = "/opt/rudder/etc/plugins/licenses";
const WEBAPP_XML_PATH: &str = "/opt/rudder/share/webapps/rudder.xml";
const PACKAGES_DATABASE_PATH: &str = "/var/rudder/packages/index.json";
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
    // Abort of not run as root
    // Ignore on error
    #[cfg(not(debug_assertions))]
    if let Ok(false) = am_i_root() {
        eprintln!("This program needs to run as root, aborting.");
        process::exit(1);
    }

    // Read CLI args
    let args = cli::Args::parse();

    // Setup logger early
    let filter = if args.debug {
        LevelFilter::Debug
    } else {
        LevelFilter::Info
    };
    env_logger::builder()
        .format_timestamp(None)
        .format_module_path(false)
        .format_target(false)
        .filter_level(filter)
        .init();

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
    let mut db = Database::read(PACKAGES_DATABASE_PATH)?;
    let index = RepoIndex::from_path(REPOSITORY_INDEX_PATH)?;

    match args.command {
        Command::Install { force, package } => package
            .into_iter()
            .try_for_each(|p| action::install(force, p, &repo, &index, &mut webapp))?,
        Command::Uninstall { package: packages } => packages
            .into_iter()
            .try_for_each(|p| db.uninstall(&p, &mut webapp))?,
        Command::List {
            all,
            enabled,
            format,
        } => ListOutput::new(all, enabled, &db, &index, &webapp)?.display(format)?,
        Command::Show { package } => println!(
            "{}",
            db.plugins
                .get(&package)
                .ok_or_else(|| anyhow!("Could not find plugin"))?
                .metadata
        ),
        Command::Update {} => repo.update()?,
        Command::Enable {
            package,
            all,
            save,
            restore,
        } => {
            // If all is passed, enabled all installed plugins
            let to_enable = if all {
                db.plugins.keys().cloned().collect()
            } else {
                package
            };
            if to_enable.is_empty() {
                let backup_path = Path::new(TMP_PLUGINS_FOLDER).join("plugins_status.backup");
                if save {
                    action::save(&backup_path, &db, &mut webapp)?
                } else if restore {
                    action::restore(&backup_path, &db, &mut webapp)?
                } else {
                    bail!("No plugin provided")
                }
            } else {
                to_enable.iter().try_for_each(|p| match db.plugins.get(p) {
                    None => bail!("Plugin {} not installed", p),
                    Some(p) => p.enable(&mut webapp),
                })?
            }
        }
        Command::Disable { package, all } => {
            let to_disable = if all {
                db.plugins.keys().cloned().collect()
            } else {
                package
            };
            to_disable
                .iter()
                .try_for_each(|p| match db.plugins.get(p) {
                    None => bail!("Plugin {} not installed", p),
                    Some(p) => p.disable(&mut webapp),
                })?
        }
        Command::CheckConnection {} => repo.test_connection()?,
    }
    // Restart if needed
    webapp.apply_changes()?;
    Ok(())
}

pub mod action {
    use std::{fs, fs::File, io::BufRead, path::Path};

    use anyhow::{anyhow, bail, Context, Result};
    use log::debug;

    use crate::{
        archive::Rpkg, database::Database, repo_index::RepoIndex, repository::Repository,
        webapp::Webapp, TMP_PLUGINS_FOLDER,
    };

    pub fn install(
        force: bool,
        package: String,
        repository: &Repository,
        index: &RepoIndex,
        webapp: &mut Webapp,
    ) -> Result<()> {
        let rpkg_path = if Path::new(&package).exists() && package.ends_with(".rpkg") {
            package
        } else {
            // Find compatible plugin if any
            let to_dl_and_install = match index.get_compatible_plugin(&webapp.version, &package) {
                    None => bail!("Could not find any compatible '{}' plugin with the current Rudder version in the configured repository.", package),
                    Some(p) => {
                        debug!("Found a compatible plugin in the repository:\n{:?}", p);
                        p
                    }
                };
            let dest = Path::new(TMP_PLUGINS_FOLDER).join(
                Path::new(&to_dl_and_install.path)
                    .file_name()
                    .ok_or_else(|| {
                        anyhow!(
                            "Could not retrieve filename from path '{}'",
                            to_dl_and_install.path
                        )
                    })?,
            );
            // Download rpkg
            repository.download(&to_dl_and_install.path, &dest)?;
            dest.as_path().display().to_string()
        };
        let rpkg = Rpkg::from_path(&rpkg_path)?;
        rpkg.install(force, webapp)?;
        Ok(())
    }

    pub fn save(backup_path: &Path, db: &Database, webapp: &mut Webapp) -> Result<()> {
        let saved = webapp
            .jars()?
            .iter()
            // Let's ignore unknown jars
            .flat_map(|j| db.plugin_provides_jar(j))
            .map(|p| format!("enable {}", p.metadata.name))
            .collect::<Vec<String>>()
            .join("\n");
        fs::write(backup_path, saved)?;
        Ok(())
    }

    pub fn restore(backup_path: &Path, db: &Database, webapp: &mut Webapp) -> Result<()> {
        let file = File::open(backup_path)?;
        let buf = std::io::BufReader::new(file);
        for l in buf.lines() {
            let line = l.context("Could not read line from plugin backup status file")?;
            let plugin_name = line.trim().split(' ').nth(1);
            match plugin_name {
                None => debug!("Malformed line in plugin backup status file"),
                Some(x) => match db.plugins.get(x) {
                    None => debug!("Plugin {} is not installed, it could not be enabled", x),
                    Some(y) => y.enable(webapp)?,
                },
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn i_am_not_root() {
        assert!(!super::am_i_root().unwrap())
    }
}
