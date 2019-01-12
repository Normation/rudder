use std::fmt;

// Error management

#[derive(Debug, PartialEq)]
pub enum PError {
    //          message file    line  column
    Compilation(String, String, u32, usize),
    //Parsing(nom::Err),
    //       message file    line  column
    Warning(String, String, u32, usize),
}

// Error management definitions
pub type Result<T> = std::result::Result<T, PError>;
//pub type OptResult<T> = std::result::Result<Option<T>, PError>;

//#[macro_export]
macro_rules! fail {
    ($origin:expr, $ ( $ arg : tt ) *) => ({
        let (file,line,col) = $origin.position();
        return Err(PError::Compilation(std::fmt::format( format_args!( $ ( $ arg ) * ) ),
                                       file,
                                       line,
                                       col
                                      ))
    });
}

macro_rules! warn {
    ($origin:expr, $ ( $ arg : tt ) *) => ({
        let (file,line,col) = $origin.position();
        PError::Warning(std::fmt::format( format_args!( $ ( $ arg ) * ) ),
                                       file,
                                       line,
                                       col
                                      )
    });
}

impl fmt::Display for PError {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        match self {
            PError::Compilation(msg, _, _, _) => write!(f, "Compilation error: {}", msg),
            PError::Warning(msg, _, _, _) => write!(f, "Compilation warning: {}", msg),
        }
    }
}
