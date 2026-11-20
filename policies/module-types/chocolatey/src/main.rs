use rudder_module_chocolatey::choco::{ChocoRunner, commands};
use rudder_module_chocolatey::parameters::RudderPackageVersion;
fn main() {
    let runner = ChocoRunner::new();
    let v = commands::Version;
    let c = commands::ListInstalledPackages;
    let result = runner.run(&v);
    println!("{:?}", result);
    let result = runner.run(&c);
    println!("{:?}", result);
    let i = commands::InstallPackage::new("git", RudderPackageVersion::Latest, vec![]).unwrap();
    let result = runner.run(&i);
    println!("{:?}", result);
}
