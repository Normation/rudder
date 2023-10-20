mod rpkg;

fn main() {
    let x = rpkg::webapp_xml::WebappXml::new(String::from("./tests/webapp_xml/rudder.xml"));
    let _ = x.disable_jar(String::from("patapouf"));
}
