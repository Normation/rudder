///
/// We write our own error type to have a consistent error type through all our code.
/// We translate other types to this one when necessary.
/// All case contain 4 elements:
/// - the detailed error message
/// - the file,line and column where the error can be found / fixed
/// The we have 3 types
/// - Parsing error: originating from nom, irrecoverable
/// - Compilation error: usually we can skip what we are doing and go to next iteration
/// - List: aggregate compilation errors so that user can fix them all ant once
///
use std::fmt;

#[derive(Debug, PartialEq)]
pub enum Error {
    //          message file    line  column
    Compilation(String, String, u32, usize),
    //      message file    line  column
    Parsing(String, String, u32, usize),
    //   Error list
    List(Vec<Error>),
}

/// Redefine our own result type with fixed error type for readability.
pub type Result<T> = std::result::Result<T, Error>;

/// This macro returns from current function/closure with an error.
/// When writing an iteration, use this within a map so we can continue on
/// next iteration and aggregate errors.
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

/// Transforms an iterator of error result into a result of list error.
/// This is useful to aggregate and give the proper output type to rRsults given by map.
/// Only support Result<()>, because it throws out Ok cases
pub fn fix_results<I>(res: I) -> Result<()>
where
    I: Iterator<Item = Result<()>>,
{
    let err_list = res.filter_map(|r| r.err()).collect::<Vec<Error>>();
    if err_list.is_empty() {
        Ok(())
    } else {
        Err(Error::List(err_list))
    }
}
/// Same a fix_results but knows how to extract a vector of values from the result list
pub fn fix_vec_results<I, T>(res: I) -> Result<Vec<T>>
where
    I: Iterator<Item = Result<T>>,
{
    let (vals, errs): (Vec<Result<T>>, Vec<Result<T>>) = res.partition(|r| r.is_ok());
    if errs.is_empty() {
        Ok(vals.into_iter().map(|r| r.unwrap()).collect())
    } else {
        Err(Error::List(
            errs.into_iter().map(|r| r.err().unwrap()).collect(),
        ))
    }
}

/// Display errors to the final user
impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        match self {
            Error::Compilation(msg, _, _, _) => write!(f, "Compilation error: {}", msg),
            Error::Parsing(msg, _, _, _) => write!(f, "Parsing error: {}", msg),
            Error::List(v) => write!(
                f,
                "{}",
                v.iter()
                    .map(|x| format!("{}", x))
                    .collect::<Vec<String>>()
                    .join("\n")
            ),
        }
    }
}
