// SPDX-License-Identifier: GPL-3.0-only

use super::context::VarKind;
use super::enums::{EnumExpression, EnumList};
use crate::error::*;
use crate::parser::*;
use std::collections::HashMap;

#[derive(Debug, PartialEq, Clone)]
pub struct StringObject<'src> {
    pos: Token<'src>,
    // static means no evaluated content (no variable)
    pub data: Vec<PInterpolatedElement>,
}
impl<'src> StringObject<'src> {
    pub fn from_pstring(pos: Token<'src>, s: String) -> Result<StringObject> {
        let data = parse_string(&s[..])?;
        Ok(StringObject { pos, data })
    }

    pub fn from_static_pstring(pos: Token<'src>, s: String) -> Result<StringObject> {
        let obj = StringObject::from_pstring(pos, s.clone())?;
        if obj.is_static() {
            Ok(obj)
        } else {
            fail!(pos, "Dynamic data (eg: variables) is forbidden in '{}'", s)
        }
    }

    pub fn is_static(&self) -> bool {
        self.data.iter().fold(true, |b, pie| match pie {
            PInterpolatedElement::Static(_) => b,
            PInterpolatedElement::Variable(_) => false,
        })
    }

    pub fn append(&mut self, other: StringObject<'src>) {
        self.data.extend(other.data);
    }

    pub fn format<SF, VF>(&self, str_formatter: SF, var_formatter: VF) -> String
    where
        SF: Fn(&str) -> String,
        VF: Fn(&str) -> String,
    {
        self.data
            .iter()
            .map(|x| match x {
                PInterpolatedElement::Static(s) => str_formatter(s),
                PInterpolatedElement::Variable(v) => var_formatter(v),
            })
            .collect::<Vec<String>>()
            .join("")
    }
}

#[derive(Debug, PartialEq, Clone)]
pub enum Value<'src> {
    //     position   format  variables
    String(StringObject<'src>),
    Number(Token<'src>, f64),
    EnumExpression(EnumExpression<'src>),
    List(Vec<Value<'src>>),
    Struct(HashMap<String, Value<'src>>),
}
impl<'src> Value<'src> {
    pub fn from_pvalue<VG>(
        enum_list: &EnumList<'src>,
        getter: &VG,
        pvalue: PValue<'src>,
    ) -> Result<Value<'src>>
    where
        VG: Fn(Token<'src>) -> Option<VarKind<'src>>,
    {
        match pvalue {
            PValue::String(pos, s) => Ok(Value::String(StringObject::from_pstring(pos, s)?)),
            PValue::Number(pos, n) => Ok(Value::Number(pos, n)),
            PValue::EnumExpression(e) => Ok(Value::EnumExpression(
                enum_list.canonify_expression(getter, e)?,
            )),
            PValue::List(l) => Ok(Value::List(map_vec_results(l.into_iter(), |x| {
                Value::from_pvalue(enum_list, getter, x)
            })?)),
            PValue::Struct(s) => Ok(Value::Struct(map_hashmap_results(
                s.into_iter(),
                |(k, v)| Ok((k, Value::from_pvalue(enum_list, getter, v)?)),
            )?)),
        }
    }

    pub fn from_static_pvalue(pvalue: PValue<'src>) -> Result<Value<'src>> {
        match pvalue {
            PValue::String(pos, s) => Ok(Value::String(StringObject::from_static_pstring(pos, s)?)),
            PValue::Number(pos, n) => Ok(Value::Number(pos, n)),
            // TODO replace with real thing / the only accepted expression is true or false
            PValue::EnumExpression(_) => Ok(Value::Number("".into(), 1.)),
            //PValue::EnumExpression(e) => fail!(e.token(), "Enum expression are not allowed in static context"),
            PValue::List(l) => Ok(Value::List(map_vec_results(
                l.into_iter(),
                Value::from_static_pvalue,
            )?)),
            PValue::Struct(s) => Ok(Value::Struct(map_hashmap_results(
                s.into_iter(),
                |(k, v)| Ok((k, Value::from_static_pvalue(v)?)),
            )?)),
        }
    }

    // TODO check where it is called
    pub fn context_check<VG>(&self, _getter: &VG) -> Result<()>
    where
        VG: Fn(Token<'src>) -> Option<VarKind<'src>>,
    {
        match self {
            Value::String(s) => {
                map_results(s.data.iter(), |e| match e {
                    PInterpolatedElement::Static(_) => Ok(()),
                    PInterpolatedElement::Variable(_v) => Ok(()),
                    // TODO
                    //                            match getter(Token::new("", v)) {
                    //                                None => fail!(s.pos, "Variable {} does not exist at {}", v, s.pos),
                    //                                _ => Ok(()),
                    //                            },
                })
            }
            Value::Number(_, _) => unimplemented!(),
            Value::EnumExpression(_) => Ok(()), // check already done at enum creation
            Value::List(_) => unimplemented!(),
            Value::Struct(_) => unimplemented!(),
        }
    }

    // TODO is it still useful given that it exists for PValue
    pub fn get_type(&self) -> PType {
        match self {
            Value::String(_) => PType::String,
            Value::Number(_, _) => unimplemented!(),
            Value::EnumExpression(_) => PType::Boolean,
            Value::List(_) => unimplemented!(),
            Value::Struct(_) => unimplemented!(),
        }
    }
}
