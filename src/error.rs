use std::collections::HashMap;
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
use std::hash::Hash;

// TODO simplify errors into a single type and add from_* methods
//
#[derive(Debug, PartialEq, Clone)]
pub enum Error {
    //   message
    User(String),
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
macro_rules! err {
    ($origin:expr, $ ( $ arg : tt ) *) => ({
        let (file,line,col) = $origin.position();
        Error::Compilation(std::fmt::format( format_args!( $ ( $ arg ) * ) ),
                                       file,
                                       line,
                                       col
                                      )
    });
}

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
/// This is useful to aggregate and give the proper output type to results given by map.
/// Only support Result<()>, because it throws out Ok cases
pub fn fix_results<I>(it: I) -> Result<()>
where
    I: Iterator<Item = Result<()>>,
{
    let err_list = it.filter_map(|r| r.err()).collect::<Vec<Error>>();
    if err_list.is_empty() {
        Ok(())
    } else {
        Err(Error::List(err_list))
    }
}
/// map an iterator content with a Fn then fix the result.
pub fn map_results<I, F, X>(it: I, f: F) -> Result<()>
where
    I: Iterator<Item = X>,
    F: FnMut(X) -> Result<()> // also accepts Fn
{
    fix_results(it.map(f))
}
/// Same a map_results but knows how to extract a vector of values from the result list
pub fn map_vec_results<I, F, X, Y>(it: I, f: F) -> Result<Vec<Y>>
where
    I: Iterator<Item = X>,
    F: FnMut(X) -> Result<Y> // also accepts Fn
{
    let (vals, errs): (Vec<Result<Y>>, Vec<Result<Y>>) = it.map(f).partition(|r| r.is_ok());
    if errs.is_empty() {
        Ok(vals.into_iter().map(|r| r.unwrap()).collect())
    } else {
        Err(Error::List(
            errs.into_iter().map(|r| r.err().unwrap()).collect(),
        ))
    }
}
/// Same a map_vec_results but joins the output into a string
pub fn map_strings_results<I, F, X>(it: I, f: F, sep: &'static str) -> Result<String>
where
    I: Iterator<Item = X>,
    F: FnMut(X) -> Result<String> // also accepts Fn
{
    Ok(map_vec_results(it, f)?.join(sep))
}
/// Same a map_vec_results but for hashmap
pub fn map_hashmap_results<I, F, X, Y , Z>(it: I, f: F) -> Result<HashMap<Y,Z>>
where
    I: Iterator<Item = X>,
    F: FnMut(X) -> Result<(Y,Z)>,
    Y: Eq + Hash,
{
    #[allow(clippy::type_complexity)]
    let (vals, errs): (Vec<Result<(Y, Z)>>, Vec<Result<(Y, Z)>>) = it.map(f).partition(|r| r.is_ok());
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
            Error::User(msg) => write!(f, "Error:  {}", msg),
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
