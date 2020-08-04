// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

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
use crate::parser::Token;
use crate::logger::Backtrace;
use colored::Colorize;
use ngrammatic::CorpusBuilder;
use std::{collections::HashMap, fmt, hash::Hash};

const FUZZY_THRESHOLD: f32 = 0.5; // pub + `crate::` before calling since it may end up in the main.rs file.

#[derive(Clone)]
pub enum Error {
    //   message  backtrace
    User((String, Option<Backtrace>)),
    //   Error list, for now it does not have backtrace
    List(Vec<(String, Option<Backtrace>)>),
}

// TODO add backtrace option for Errors : crate::logger::Backtrace::get()

/// Redefine our own result type with fixed error type for readability.
pub type Result<T> = std::result::Result<T, Error>;

impl Error {
    pub fn new(description: String) -> Self {
        let  backtrace: Option<Backtrace> = match std::env::var("RUDDERC_BACKTRACE") {
            Ok(ref val) if val != "0" => Some(Backtrace::new()),
            _ => None
        };
        Error::User((description, backtrace))
    }

    pub fn append(self, e2: Self) -> Self {
        match (self, e2) {
            (Error::User(u1), Error::User(u2)) => Error::List(vec![u1, u2]),
            (Error::List(mut l), Error::User(u)) => {
                l.push(u);
                Error::List(l)
            }
            (Error::User(u), Error::List(mut l)) => {
                l.push(u);
                Error::List(l)
            }
            (Error::List(mut l1), Error::List(l2)) => {
                l1.extend(l2);
                Error::List(l1)
            }
        }
    }

    pub fn from_vec(vec: Vec<Self>) -> Self {
        if vec.is_empty() {
            panic!("BUG do not call from_vec on empty vectors");
        }
        let mut it = vec.into_iter();
        let first = it.next().unwrap();
        it.fold(first, |e0, e| e0.append(e))
    }

    // results must only contain errors
    #[allow(dead_code)]
    pub fn from_vec_result<X>(vec: Vec<Result<X>>) -> Self
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
        use colored::Colorize;

        Error::new(
            format!(
                "{}:\n{} {}",
                $origin.position_str().bright_yellow(),
                "!-->".bright_blue(),
                format!( $ ( $ arg ) * )
            )
        )
    });
}

pub fn err_wrapper<E: std::fmt::Display>(filename: &str, err: E) -> Error {
    err!(Token::new(&filename.to_owned(), ""), "{}", err)
}

/// This macro returns from current function/closure with a Result.
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
            Error::User((msg, bt)) => {
                write!(
                    f,
                    "{} at {}{}",
                    "error".red(),
                    msg,
                    bt.as_ref().map_or("".to_owned(), |bt| bt.to_string())
                )
            },
            Error::List(v) => {
                write!(
                    f,
                    "{}:\n{}",
                    "errors".red(),
                    v.iter()
                        .map(|(msg, bt)| format!(
                            "{}{}",
                            msg,
                            bt.as_ref().map_or("".to_owned(), |bt| bt.to_string())
                        ))
                        .collect::<Vec<String>>()
                        .join("\n")
                )
            }
        }
    }
}

// WARNING: the only purpose of this impl is to print a proper output in case of core::panic 
// should not be used otherwise
// debug was called on Error which was breaking the compilation output in case core::panic was called
impl fmt::Debug for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let err_msg = match self {
            Error::User((msg, _)) => msg.to_owned(),
            Error::List(v) => {
                let errors = v.iter()
                    .map(|(msg, bt)| format!(
                        "{}{}",
                        msg,
                        bt.as_ref().map_or("".to_owned(), |bt| bt.to_string())
                    ))
                    .collect::<Vec<String>>()
                    .join("\n");
                format!("(list) {}", errors)
            }
        };
        write!(f, "{}", err_msg)
    }
}


impl PartialEq for Error {
    fn eq(&self, other: &Self) -> bool {
        match (self, other) {
            (Error::User(a), Error::User(b)) => a.0 == b.0,
            (Error::List(a), Error::List(b)) => {
                a.iter()
                    .map(|(e, _)| e)
                    .eq(b.iter().map(|(e, _)| e))
            }
            _ => false,
        }
    }
}

/// Searches for a matching string in an Iterator of Token
fn fuzzy_search<'src, I>(token_fragment: &str, list: I) -> Option<String>
where
    I: Iterator<Item = &'src Token<'src>>,
{
    let mut corpus = CorpusBuilder::new().finish();
    list.for_each(|token| corpus.add_text(token.fragment()));
    let results = corpus.search(token_fragment, FUZZY_THRESHOLD);
    if let Some(top_match) = results.first() {
        return Some(top_match.text.to_string());
    }
    None
}

/// Adds a suggestion o an error message if a similar Token name is found in the available context (scope + global)
pub fn get_suggestion_message<'src, I>(unmatched_token_fragment: &str, list: I) -> String
where
    I: Iterator<Item = &'src Token<'src>>,
{
    let separator = ". ";
    let mut output_str = "".to_owned();
    output_str.push_str(separator);
    match list.size_hint() {
        (_, Some(0)) => output_str.push_str("No variable in the current context"),
        (_, Some(1)) => {
            let top_match = list.last().unwrap();
            output_str.push_str(format!("Did you mean: \"{}\"?", top_match.fragment()).as_str())
        }
        _ => match fuzzy_search(unmatched_token_fragment, list) {
            Some(message) => {
                output_str.push_str(format!("Did you mean: \"{}\"?", message).as_str())
            }
            None => output_str.push_str("No similar name found."),
            // previous is explicit, testing purpose. prod -> None => return "".to_owned(),
        },
    };
    output_str
}
