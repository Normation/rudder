// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use super::{
    context::Type,
    enums::{EnumExpression, EnumList},
};
use crate::{error::*, parser::*};
use std::{collections::HashMap, convert::TryFrom};

#[derive(Debug, PartialEq, Clone)]
pub struct StringObject<'src> {
    pos: Token<'src>,
    // static means no evaluated content (no variable)
    pub data: Vec<PInterpolatedElement>,
}
impl<'src> StringObject<'src> {
    pub fn from_pstring(pos: Token<'src>, s: String) -> Result<Self> {
        let data = parse_string(&s[..])?;
        Ok(StringObject { pos, data })
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

/// transform into a string object if there is no variable interpolation
impl<'src> TryFrom<&StringObject<'src>> for String {
    type Error = Error; // rudder-lang

    fn try_from(value: &StringObject<'src>) -> Result<Self> {
        if value
            .data
            .iter()
            .any(|x| !matches!(x, PInterpolatedElement::Static(_)))
        {
            fail!(
                value.pos,
                "Value cannot be extracted since it contains data"
            );
        }
        Ok(value
            .data
            .iter()
            .map(|x| match x {
                PInterpolatedElement::Static(s) => s.clone(),
                _ => "".into(),
            })
            .collect::<Vec<String>>()
            .join(""))
    }
}

#[derive(Debug, PartialEq, Clone)]
pub enum Constant<'src> {
    String(Token<'src>, String),
    Number(Token<'src>, f64),
    Boolean(Token<'src>, bool),
    List(Vec<Constant<'src>>),
    Struct(HashMap<String, Constant<'src>>),
}
impl<'src> Constant<'src> {
    pub fn from_pvalue(pvalue: PValue<'src>) -> Result<Self> {
        match pvalue {
            // TODO use source instead of pos
            PValue::String(pos, s) => {
                let so = StringObject::from_pstring(pos, s)?;
                let value = String::try_from(&so)?;
                Ok(Constant::String(so.pos, value))
            },
            PValue::Number(pos, n) => Ok(Constant::Number(pos, n)),
            PValue::Boolean(pos, b) => Ok(Constant::Boolean(pos, b)),
            PValue::EnumExpression(e) => fail!(e.source, "Enum expression are not allowed in constants"),
            PValue::List(l) => Ok(Constant::List(map_vec_results(
                l.into_iter(),
                Constant::from_pvalue,
            )?)),
            PValue::Struct(s) => Ok(Constant::Struct(map_hashmap_results(
                s.into_iter(),
                |(k, v)| Ok((k, Constant::from_pvalue(v)?)),
            )?)),
        }
    }
}

#[derive(Debug, PartialEq, Clone)]
pub enum Value<'src> {
    //     position   format  variables
    String(StringObject<'src>),
    Number(Token<'src>, f64),
    Boolean(Token<'src>, bool),
    EnumExpression(EnumExpression<'src>),
    List(Vec<Value<'src>>),
    Struct(HashMap<String, Value<'src>>),
}

impl<'src> From<&Constant<'src>> for Value<'src> {
    fn from(val: &Constant<'src>) -> Self {
        match val {
            Constant::String(p, s) => Value::String(
                StringObject{ pos: p.clone(), data: vec![PInterpolatedElement::Static(s.clone())] }
            ),
            Constant::Number(p, f) => Value::Number(p.clone(), f.clone()),
            Constant::Boolean(p, b) => Value::Boolean(p.clone(), b.clone()),
            Constant::List(l) => Value::List(l.iter().map(Value::from).collect()),
            Constant::Struct(s) => {
                let spec = s
                    .iter()
                    .map(|(k, v)| (k.clone(), v.into()));
                Value::Struct(spec.collect())
            }
        }
    }
}

impl<'src> Value<'src> {
    pub fn from_pvalue<VG>(
        enum_list: &EnumList<'src>,
        getter: &VG,
        pvalue: PValue<'src>,
    ) -> Result<Self>
    where
        VG: Fn(Token<'src>) -> Option<Type<'src>>,
    {
        match pvalue {
            PValue::String(pos, s) => Ok(Value::String(StringObject::from_pstring(pos, s)?)),
            PValue::Number(pos, n) => Ok(Value::Number(pos, n)),
            PValue::Boolean(pos, b) => Ok(Value::Boolean(pos, b)),
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

    // TODO check where it is called
    pub fn context_check<VG>(&self, _getter: &VG) -> Result<()>
    where
        VG: Fn(Token<'src>) -> Option<Type<'src>>,
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
            Value::Boolean(_, _) => unimplemented!(),
            Value::EnumExpression(_) => Ok(()), // check already done at enum creation
            Value::List(_) => unimplemented!(),
            Value::Struct(_) => unimplemented!(),
        }
    }
}
