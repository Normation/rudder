#![macro_use]
/// Parser tools
use super::PInput;
use nom::*;

/// Call this one When you are sure nothing else can match
/// For example if '{' has been matched and there is no '}'
macro_rules! or_fail (
    ($i:expr, $submac:ident!( $($args:tt)* ), $code:expr) => (return_error!($i, ErrorKind::Custom($code as u32), $submac!($($args)*)););
    ($i:expr, $f:expr, $code:expr ) => (or_fail!($i,call!($f),$code));
);

// replace return_error with add_return_error above to get a similar macro but without definitive fail

/// This macro replaces named with implicit input type (PInput)
macro_rules! pnamed (
    ($name:ident<$o:ty>, $submac:ident!( $($args:tt)* )) => (nom::named!($name<PInput,$o>, $submac!($($args)*)););
    (pub $name:ident<$o:ty>, $submac:ident!( $($args:tt)* )) => (nom::named!(pub $name<PInput,$o>, $submac!($($args)*)););
);

/// Eat char separators and simple comments
/// Should be used via sp!
pnamed!(pub space_s<&str>,
    do_parse!(
        eat_separator!(&" \t\r\n"[..]) >>
        many0!(delimited!(
            tag!("#"),
            opt!(preceded!(not!(tag!("#")),take_until!("\n"))),
            tag!("\n")
        )) >>
        eat_separator!(&" \t\r\n"[..]) >>
        ("")
    )
);

/// Equivalent of ws! but works for chars (instead of u8) and comments
macro_rules! sp (
    ($i:expr, $($args:tt)*) => (
        {
            sep!($i, space_s, $($args)*)
        }
    )
);

/// Eat char separators and simple comments but not newlines
/// Should be used via sp_nnl!
pnamed!(pub space_s_nnl<&str>,
    do_parse!(
        eat_separator!(&" \t"[..]) >>
        many0!(preceded!(
            tag!("#"),
            opt!(preceded!(not!(tag!("#")),take_until!("\n")))
        )) >>
        eat_separator!(&" \t"[..]) >>
        ("")
    )
);

/// Equivalent of sp! but doesn't eat newlines (only used for variable definition atm)
macro_rules! sp_nnl (
    ($i:expr, $($args:tt)*) => (
        {
            sep!($i, space_s_nnl, $($args)*)
        }
    )
);
