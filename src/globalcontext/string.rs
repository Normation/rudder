use nom::types::CompleteStr;
use nom::*;
use crate::context::{VarContext, VarKind};
use crate::error::*;
use crate::parser::{PEnum, PEnumExpression, PEnumMapping, Token};
use std::collections::HashMap;

// interpolated string
pub struct IString {
    s: String,
}

impl IString {
    pub fn new(s: String) -> IString {
        IString { s }
    }
}

pub fn interpolate(context: &VarContext, s: &String) -> Result<IString> {
    Ok(())
}

