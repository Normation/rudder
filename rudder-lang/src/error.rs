// Copyright 2019 Normation SAS
//
// This file is part of Rudder.
//
// Rudder is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// In accordance with the terms of section 7 (7. Additional Terms.) of
// the GNU General Public License version 3, the copyright holders add
// the following Additional permissions:
// Notwithstanding to the terms of section 5 (5. Conveying Modified Source
// Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
// Public License version 3, when you create a Related Module, this
// Related Module is not considered as a part of the work and may be
// distributed under the license agreement of your choice.
// A "Related Module" means a set of sources files including their
// documentation that, without modification of the Source Code, enables
// supplementary functions or services in addition to those offered by
// the Software.
//
// Rudder is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

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
use colored::*;

#[derive(Debug, PartialEq, Clone)]
pub enum Error {
    //   message
    User(String),
    //   Error list
    List(Vec<String>),
}

/// Redefine our own result type with fixed error type for readability.
pub type Result<T> = std::result::Result<T, Error>;

impl Error {
    pub fn append(self, e2: Error) -> Error {
        match (self, e2) {
            (Error::User(s1), Error::User(s2)) => Error::List(vec![s1, s2]),
            (Error::List(mut l), Error::User(s)) => {
                l.push(s);
                Error::List(l)
            }
            (Error::User(s), Error::List(mut l)) => {
                l.push(s);
                Error::List(l)
            }
            (Error::List(mut l1), Error::List(l2)) => {
                l1.extend(l2);
                Error::List(l1)
            }
        }
    }

    pub fn from_vec(vec: Vec<Error>) -> Error {
        if vec.is_empty() {
            panic!("BUG do not call from_vec on empty vectors");
        }
        let mut it = vec.into_iter();
        let first = it.next().unwrap();
        it.fold(first, |e0, e| e0.append(e))
    }

    // results must only contain errors
    #[allow(dead_code)]
    pub fn from_vec_result<X>(vec: Vec<Result<X>>) -> Error
    where
        X: fmt::Debug,
    {
        if vec.is_empty() {
            panic!("BUG do not call from_vec_result on empty vectors");
        }
        let mut it = vec.into_iter().map(Result::unwrap_err);
        let first = it.next().unwrap();
        it.fold(first, |e0, e| e0.append(e))
    }
}

/// This macro returns from current function/closure with an error.
/// When writing an iteration, use this within a map so we can continue on
/// next iteration and aggregate errors.
macro_rules! err {
    ($origin:expr, $ ( $ arg : tt ) *) => ({
        use crate::error::Error;
        use colored::*;

        Error::User(format!(
                "{}:\n{} {}",
                $origin.position_str().bright_yellow(),
                "-->".bright_blue(),
                format!( $ ( $ arg ) * )
        ))
    });
}

/// This macro returns from current function/closure with an error.
/// When writing an iteration, use this within a map so we can continue on
/// next iteration and aggregate errors.
macro_rules! fail {
    ($origin:expr, $ ( $ arg : tt ) *) => ({
        return Err(err!($origin, $ ( $ arg ) *))
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
        Err(Error::from_vec(err_list))
    }
}
/// map an iterator content with a Fn then fix the result.
pub fn map_results<I, F, X>(it: I, f: F) -> Result<()>
where
    I: Iterator<Item = X>,
    F: FnMut(X) -> Result<()>, // also accepts Fn
{
    let err_list = it.map(f).filter_map(|r| r.err()).collect::<Vec<Error>>();
    if err_list.is_empty() {
        Ok(())
    } else {
        Err(Error::from_vec(err_list))
    }
}
/// Same a map_results but knows how to extract a vector of values from the result list
pub fn map_vec_results<I, F, X, Y>(it: I, f: F) -> Result<Vec<Y>>
where
    I: Iterator<Item = X>,
    F: FnMut(X) -> Result<Y>, // also accepts Fn
{
    let (vals, errs): (Vec<Result<Y>>, Vec<Result<Y>>) = it.map(f).partition(|r| r.is_ok());
    if errs.is_empty() {
        Ok(vals.into_iter().map(|r| r.unwrap()).collect())
    } else {
        Err(Error::from_vec(
            errs.into_iter().map(|r| r.err().unwrap()).collect(),
        ))
    }
}
/// Same a map_vec_results but joins the output into a string
pub fn map_strings_results<I, F, X>(it: I, f: F, sep: &'static str) -> Result<String>
where
    I: Iterator<Item = X>,
    F: FnMut(X) -> Result<String>, // also accepts Fn
{
    Ok(map_vec_results(it, f)?.join(sep))
}
/// Same a map_vec_results but for hashmap
pub fn map_hashmap_results<I, F, X, Y, Z>(it: I, f: F) -> Result<HashMap<Y, Z>>
where
    I: Iterator<Item = X>,
    F: FnMut(X) -> Result<(Y, Z)>,
    Y: Eq + Hash,
{
    #[allow(clippy::type_complexity)]
    let (vals, errs): (Vec<Result<(Y, Z)>>, Vec<Result<(Y, Z)>>) =
        it.map(f).partition(|r| r.is_ok());
    if errs.is_empty() {
        Ok(vals.into_iter().map(|r| r.unwrap()).collect())
    } else {
        Err(Error::from_vec(
            errs.into_iter().map(|r| r.err().unwrap()).collect(),
        ))
    }
}

/// Display errors to the final user
impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        match self {
            Error::User(msg) => write!(f, "{} at {}", "error".red(), msg),
            Error::List(v) => write!(
                f,
                "{}:\n{}",
                "errors".red(),
                v.iter()
                    .map(|x| x.to_string())
                    .collect::<Vec<String>>()
                    .join("\n")
            ),
        }
    }
}
