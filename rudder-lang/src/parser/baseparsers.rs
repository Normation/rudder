use nom::{
    branch::*, bytes::complete::*, character::complete::*, combinator::*, multi::*, sequence::*,
    Slice,
};

use super::{error::*, token::*};

pub fn strip_comment(i: PInput) -> PResult<PInput> {
    // simple comments (ie # but not ##)
    terminated(
        etag("#"),
        alt((
            delimited(not(tag("#")), take_until("\n"), newline),
            // comment is the last line
            preceded(not(tag("#")), rest),
        )),
    )(i)
}

/// Eat everything that can be ignored between tokens
/// ie white spaces, newlines and simple comments (with a single #)
pub fn strip_spaces_and_comment(i: PInput) -> PResult<()> {
    let (i, _) = many0(alt((
        // spaces
        multispace1,
        // simple comments (ie # but not ##)
        strip_comment,
    )))(i)?;
    Ok((i, ()))
}

/// Combinator automatically call strip_spaces_and_comment before and after a parser
/// This avoids having to call it manually many times
pub fn sp<'src, O, F>(f: F) -> impl Fn(PInput<'src>) -> PResult<O>
where
    F: Fn(PInput<'src>) -> PResult<O>,
    O: 'src,
{
    move |i| {
        let (i, _) = strip_spaces_and_comment(i)?;
        let (i, r) = f(i)?;
        let (i, _) = strip_spaces_and_comment(i)?;
        Ok((i, r))
    }
}

/// A bit like do_parse!
///
/// Transforms:
///     {
///         variable: combinator(parser);
///         ...
///     } => Object { variable, ... }
/// Into a series of sequential calls like this:
///     |i|
///     let(i,variable) = combinator(parser)(i)?;
///     let (i,_) = strip_spaces_and_comment(i)?
///     ...
///     Ok((i,Object { variable, ... }))
///
/// The result is a closure parser that can be used in place of any other parser
///
/// We don't use a list or a tuple for sequence parsing because we want to
/// use some intermediary result at some steps (for example for error management).
#[macro_export]
macro_rules! sequence {
    ( { $($f:ident : $parser:expr;)* } => $output:expr ) => {{
        crate::generic_sequence!( s, _source, start, stop, $output, $($f : $parser;)* )
    }};
    ( { $($f:ident : $parser:expr;)* : $source:ident } => $output:expr ) => {{
        crate::generic_sequence!( s, $source, start, stop, $output, $($f : $parser;)* )
    }};
    ( { $($f:ident : $parser:expr;)* : $source:ident($start:ident..) } => $output:expr ) => {{
        crate::generic_sequence!( s, $source, $start, stop, $output, $($f : $parser;)* )
    }};
    ( { $($f:ident : $parser:expr;)* : $source:ident(..$stop:ident) } => $output:expr ) => {{
        crate::generic_sequence!( s, $source, start, $stop, $output, $($f : $parser;)* )
    }};
    ( { $($f:ident : $parser:expr;)* : $source:ident($start:ident..$stop:ident) } => $output:expr ) => {{
        crate::generic_sequence!( s, $source, $start, $stop, $output, $($f : $parser;)* )
    }};
}
/// wsequence is the same a sequence, but we automatically insert space parsing between each call
#[macro_export]
macro_rules! wsequence {
    ( { $($f:ident : $parser:expr;)* } => $output:expr ) => {{
        crate::generic_sequence!( w, _source, start, stop, $output, $($f : $parser;)* )
    }};
    ( { $($f:ident : $parser:expr;)* : $source:ident } => $output:expr ) => {{
        crate::generic_sequence!( w, $source, start, stop, $output, $($f : $parser;)* )
    }};
    ( { $($f:ident : $parser:expr;)* : $source:ident($start:ident..) } => $output:expr ) => {{
        crate::generic_sequence!( w, $source, $start, stop, $output, $($f : $parser;)* )
    }};
    ( { $($f:ident : $parser:expr;)* : $source:ident(..$stop:ident) } => $output:expr ) => {{
        crate::generic_sequence!( w, $source, start, $stop, $output, $($f : $parser;)* )
    }};
    ( { $($f:ident : $parser:expr;)* : $source:ident($start:ident..$stop:ident) } => $output:expr ) => {{
        crate::generic_sequence!( w, $source, $start, $stop, $output, $($f : $parser;)* )
    }};
}

// Macro to avoid writing n macros for various sequences
// This macro takes all parameters needed and is called by all other sequence macros
#[macro_export]
macro_rules! generic_sequence {
    ( $space:ident, $context:ident, $start:ident, $stop:ident, $output:expr, $($f:ident : $parser:expr;)*   ) => {{
        // macros within macro to have the equivalent of if then else
        // the first line is called when we must strip whitespace
        macro_rules! __if_space_strip {
            ($xi:ident,$xj:ident,w) => { let ($xi,_) = strip_spaces_and_comment($xj)?; };
            ($xi:ident,$xj:ident,s) => { let $xi = $xj; };
        }
        // start and stop are identifier for return values of one element of the sequence
        macro_rules! __if_id_start {
            ($start,$ctx:ident,$xi:ident) => { $ctx = $xi; };
            ($xx:ident,$ctx:ident,$xi:ident) => { };
        }
        macro_rules! __if_id_stop {
            ($stop,$ctx:ident,$xi:ident) => { $ctx = $xi; };
            ($xx:ident,$ctx:ident,$xi:ident) => { };
        }

        // create a parser
        move |i| {
            // remember initial input
            let i0 = i;
            // context limits
            #[allow(unused_mut,unused_assignments)]
            let mut ctx_start = i0;
            #[allow(unused_mut,unused_assignments)]
            let mut ctx_stop = i0;
            $(
                // store context start position
                __if_id_start!($f,ctx_start,i);
                // intercept error to update its context if it should lead to a handled compilation error
                let (j, $f) = match $parser (i) {
                    Ok(res) => res,
                    Err(e) => return Err(update_error_context(e, Context { extractor: get_error_context, text: i0, token: i}))
                };
                // store context stop position
                __if_id_stop!($f,ctx_stop,j);
                // strip space if $space==w
                __if_space_strip!(i,j,$space);
            )*
            // no other stop found
            if ctx_stop == i0 { ctx_stop = j; }
            let $context = get_parsed_context(i0, ctx_start, ctx_stop);
            // those lines should be optimized out when source is not used
            Ok((i, $output))
        }
    }};
}

/// extract the parsed data once something hase been parsed
pub fn get_parsed_context<'src>(
    input: PInput<'src>,
    start: PInput<'src>,
    stop: PInput<'src>,
) -> Token<'src> {
    let start_offset = start.location_offset() - input.location_offset();
    let stop_offset = stop.location_offset() - input.location_offset();
    input.slice(start_offset..stop_offset).into()
}

/// Parse a tag or return an error
pub fn etag<'src>(token: &'static str) -> impl Fn(PInput<'src>) -> PResult<PInput<'src>> {
    move |i| or_err(tag(token), || PErrorKind::ExpectedToken(token))(i)
}

/// Parse a tag of fail the parser
pub fn ftag<'src>(token: &'static str) -> impl Fn(PInput<'src>) -> PResult<PInput<'src>> {
    move |i| or_fail(tag(token), || PErrorKind::ExpectedToken(token))(i)
}

/// Parse a tag that must be terminated by a space or return an error
pub fn estag<'src>(token: &'static str) -> impl Fn(PInput<'src>) -> PResult<PInput<'src>> {
    move |i| {
        or_err(terminated(tag(token), space1), || {
            PErrorKind::ExpectedKeyword(token)
        })(i)
    }
}

/// parses a delimited sequence (same as nom delimited but with spaces and specific error)
pub fn delimited_parser<'src, O, P>(
    open_delimiter: &'static str,
    parser: P,
    close_delimiter: &'static str,
) -> impl Fn(PInput<'src>) -> PResult<O>
where
    P: Copy + Fn(PInput<'src>) -> PResult<O>,
    O: 'src,
{
    wsequence!({
            open: etag(open_delimiter);
            list: parser;
            _x:   opt(tag(",")); // end of list comma is authorized but optional
            _y:   or_fail(tag(close_delimiter), || PErrorKind::UnterminatedOrInvalid(open));
        } => list
    )
}

/// parses a list of something separated by separator with specific delimiters
pub fn delimited_list<'src, O, P>(
    open_delimiter: &'static str,
    parser: P,
    separator: &'static str,
    close_delimiter: &'static str,
) -> impl Fn(PInput<'src>) -> PResult<Vec<O>>
where
    P: Copy + Fn(PInput<'src>) -> PResult<O>,
    O: 'src,
{
    move |i| {
        delimited_parser(
            open_delimiter,
            |j| {
                terminated(
                    separated_list(sp(etag(separator)), parser),
                    opt(tag(separator)),
                )(j)
            },
            close_delimiter,
        )(i)
    }
}

/// parses a list of something separated by separator with specific delimiters
pub fn delimited_nonempty_list<'src, O, P>(
    open_delimiter: &'static str,
    parser: P,
    separator: &'static str,
    close_delimiter: &'static str,
) -> impl Fn(PInput<'src>) -> PResult<Vec<O>>
where
    P: Copy + Fn(PInput<'src>) -> PResult<O>,
    O: 'src,
{
    move |i| {
        delimited_parser(
            open_delimiter,
            |j| {
                terminated(
                    separated_nonempty_list(sp(etag(separator)), parser),
                    opt(tag(separator)),
                )(j)
            },
            close_delimiter,
        )(i)
    }
}

/// Function to extract the context string, ie what was trying to be parsed when an error happened
/// It extracts the longest string between a single line and everything until the parsing error
pub fn get_error_context<'src>(i: PInput<'src>, err_pos: PInput<'src>) -> PInput<'src> {
    // One line, or everything else if no new line (end of file)
    let line: PResult<PInput> = alt((take_until("\n"), rest))(i);
    let line = match line {
        Ok((_, rest)) => Some(rest),
        _ => None,
    };

    // Until next text
    let complete: PResult<PInput> = take_until("\n")(err_pos);
    let complete = match complete {
        Ok((_, rest)) => Some(rest),
        _ => None,
    };

    match (line, complete) {
        (Some(l), Some(c)) => {
            if l.location_line() > c.location_line()
                || (l.location_line() == c.location_line()
                    && l.fragment().len() > c.fragment().len())
            {
                l
            } else {
                c
            }
        }
        (Some(l), None) => l,
        (None, Some(c)) => c,
        (None, None) => panic!("Context should never be empty"),
    }
}

/// An identifier is a word that contains alphanumeric chars.
/// Be liberal here, they are checked again later
pub fn pidentifier(i: PInput) -> PResult<Token> {
    map(
        take_while1(|c: char| c.is_alphanumeric() || (c == '_')),
        |x: PInput| x.into(),
    )(i)
}

/// A variable identifier is a list of dot separated identifiers
pub fn pvariable_identifier(i: PInput) -> PResult<Token> {
    map(
        take_while1(|c: char| c.is_alphanumeric() || (c == '_') || (c == '.')),
        |x: PInput| x.into(),
    )(i)
}
