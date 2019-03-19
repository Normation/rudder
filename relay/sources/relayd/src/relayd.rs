extern crate relayd;

use relayd::{error::Error, start};

fn main() -> Result<(), Error> {
    start()
}
