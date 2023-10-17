use std::collections::HashMap;
mod rpkg;
 

fn main() {
  let mut a = rpkg::Database::read("./tests/plugin_database_parsing.json").unwrap();
  let addon = rpkg::database::InstalledPlugin {
    files: vec!(String::from("/tmp/bob")),
    metadata: rpkg::plugin::Metadata {
      plugin_type: String::from("fda_plugin"),
      name: String::from("fda_name"),
      version: String::from("1.2.3.4"),
      build_date: String::from("aujourd'hui"),
      depends: None,
      build_commit: String::from("abcd"),
      content: HashMap::from([
        (String::from("files.txz"), String::from("/opt/rudder/share/plugins")),
      ]),
      jar_files: None
    }
  };
  a.plugins.insert(addon.metadata.name.clone(), addon);
  let _ = rpkg::database::Database::write("/tmp/plugins.json", a);
}