pub mod database;
pub use database::Database;
pub mod archive;
pub mod plugin;
pub mod repo_index;
pub mod webapp_xml;

const PACKAGES_FOLDER: &str = "/var/rudder/packagesA";
const WEBAPP_XML_PATH: &str = "/opt/rudder/share/webapps/rudder.xml";
const PACKAGES_DATABASE_PATH: &str = "/var/rudder/packages/index.json";
