use std::fmt;

// Error management


#[derive(Debug, PartialEq)]
pub enum Error {
    //          message file    line  column
    Compilation(String, String, u32, usize),
    //      message file    line  column
    Parsing(String, String, u32, usize),
    //   Error list
    List(Vec<Error>),
}

// Error management definitions
pub type Result<T> = std::result::Result<T, Error>;
//pub type OptResult<T> = std::result::Result<Option<T>, Error>;

macro_rules! fail {
    ($origin:expr, $ ( $ arg : tt ) *) => ({
        let (file,line,col) = $origin.position();
        return Err(Error::Compilation(std::fmt::format( format_args!( $ ( $ arg ) * ) ),
                                       file,
                                       line,
                                       col
                                      ))
    });
}
// TODO remove
macro_rules! warn {
    ($origin:expr, $ ( $ arg : tt ) *) => ({
        let (file,line,col) = $origin.position();
        Error::Compilation(std::fmt::format( format_args!( $ ( $ arg ) * ) ),
                                       file,
                                       line,
                                       col
                                      )
    });
}


impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        match self {
            Error::Compilation(msg, _, _, _) => write!(f, "Compilation error: {}", msg),
            Error::Parsing(msg, _, _, _) => write!(f, "Parsing error: {}", msg),
            Error::List(_) => write!(f, "TODO print error list"),
        }
    }
}
