pub mod database;
pub use database::Database;
pub mod archive;
pub mod config;
pub mod plugin;
pub mod repo_index;
pub mod webapp_xml;

const PACKAGES_FOLDER: &str = "/var/rudder/packages";
const WEBAPP_XML_PATH: &str = "/opt/rudder/share/webapps/rudder.xml";
const PACKAGES_DATABASE_PATH: &str = "/var/rudder/packages/index.json";
const CONFIG_PATH: &str = "/opt/rudder/etc/rudder-pkg/rudder-pkg.conf";
