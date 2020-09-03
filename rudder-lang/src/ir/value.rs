// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use super::{
    context::Type,
    context::VarContext,
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
            .concat()
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
            .concat())
    }
}

#[derive(Debug, PartialEq, Clone)]
pub enum Constant<'src> {
    String(Token<'src>, String),
    Float(Token<'src>, f64),
    Integer(Token<'src>, i64),
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
            }
            PValue::Float(pos, n) => Ok(Constant::Float(pos, n)),
            PValue::Integer(pos, n) => Ok(Constant::Integer(pos, n)),
            PValue::Boolean(pos, b) => Ok(Constant::Boolean(pos, b)),
            PValue::EnumExpression(e) => {
                fail!(e.source, "Enum expression are not allowed in constants")
            }
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
    Float(Token<'src>, f64),
    Integer(Token<'src>, i64),
    Boolean(Token<'src>, bool),
    EnumExpression(EnumExpression<'src>),
    List(Vec<Value<'src>>),
    Struct(HashMap<String, Value<'src>>),
}

impl<'src> From<&Constant<'src>> for Value<'src> {
    fn from(val: &Constant<'src>) -> Self {
        match val {
            Constant::String(p, s) => Value::String(StringObject {
                pos: p.clone(),
                data: vec![PInterpolatedElement::Static(s.clone())],
            }),
            Constant::Float(p, f) => Value::Float(p.clone(), f.clone()),
            Constant::Integer(p, f) => Value::Integer(p.clone(), f.clone()),
            Constant::Boolean(p, b) => Value::Boolean(p.clone(), b.clone()),
            Constant::List(l) => Value::List(l.iter().map(Value::from).collect()),
            Constant::Struct(s) => {
                let spec = s.iter().map(|(k, v)| (k.clone(), v.into()));
                Value::Struct(spec.collect())
            }
        }
    }
}

impl<'src> Value<'src> {
    pub fn from_pvalue(
        enum_list: &EnumList<'src>,
        context: &VarContext<'src>,
        pvalue: PValue<'src>,
    ) -> Result<Self> {
        match pvalue {
            PValue::String(pos, s) => Ok(Value::String(StringObject::from_pstring(pos, s)?)),
            PValue::Float(pos, n) => Ok(Value::Float(pos, n)),
            PValue::Integer(pos, n) => Ok(Value::Integer(pos, n)),
            PValue::Boolean(pos, b) => Ok(Value::Boolean(pos, b)),
            PValue::EnumExpression(e) => Ok(Value::EnumExpression(
                enum_list.canonify_expression(context, e)?,
            )),
            PValue::List(l) => Ok(Value::List(map_vec_results(l.into_iter(), |x| {
                Value::from_pvalue(enum_list, context, x)
            })?)),
            PValue::Struct(s) => Ok(Value::Struct(map_hashmap_results(
                s.into_iter(),
                |(k, v)| Ok((k, Value::from_pvalue(enum_list, context, v)?)),
            )?)),
        }
    }

    pub fn context_check(&self, context: &VarContext<'src>) -> Result<()> {
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
            Value::Float(_, _) => unimplemented!(),
            Value::Integer(_, _) => unimplemented!(),
            Value::Boolean(_, _) => unimplemented!(),
            Value::EnumExpression(_) => Ok(()), // check already done at enum creation
            Value::List(_) => unimplemented!(),
            Value::Struct(_) => unimplemented!(),
        }
    }
}

#[derive(Debug, PartialEq, Clone)]
pub struct ComplexValue<'src> {
    source: Token<'src>,
    // nested complex values not supported
    cases: Vec<(EnumExpression<'src>, Option<Value<'src>>)>,
}
impl<'src> ComplexValue<'src> {
    pub fn from_pcomplex_value(
        enum_list: &EnumList<'src>,
        context: &VarContext<'src>,
        pvalue: PComplexValue<'src>,
    ) -> Result<Self> {
        let cases = map_vec_results(pvalue.cases.into_iter(), |(case, value)| {
            let case = enum_list.canonify_expression(context, case)?;
            let value = match value {
                None => None,
                Some(v) => Some(Value::from_pvalue(enum_list, context, v)?),
            };
            Ok((case, value))
        })?;
        Ok(ComplexValue {
            source: pvalue.source,
            cases,
        })
    }

    pub fn extend(
        &mut self,
        enum_list: &EnumList<'src>,
        context: &VarContext<'src>,
        pvalue: PComplexValue<'src>,
    ) -> Result<()> {
        let ComplexValue { source, mut cases } =
            ComplexValue::from_pcomplex_value(enum_list, context, pvalue)?;
        self.cases.append(&mut cases);
        Ok(())
    }

    pub fn first_value(&self) -> Result<&Value<'src>> {
        // just return the first case, type is checked later
        self.cases
            .iter()
            .find_map(|x| x.1.as_ref())
            .ok_or_else(|| err!(self.source, "Case list doesn't have any value"))
    }

    pub fn context_check(&self, context: &VarContext<'src>) -> Result<()> {
        map_results(self.cases.iter(), |x| match &x.1 {
            Some(v) => v.context_check(context),
            None => Ok(()),
        })
    }

    pub fn verify(&self, enum_list: &EnumList<'src>) -> Result<()> {
        // check enums
        let mut errors = enum_list.evaluate(&self.cases, "TODO".into());
        // check type
        let init_val = self
            .first_value()
            .expect("Verify a complex value only after knowing it has values!"); // type should have already been extracted once
        let init_type = Type::from_value(init_val);
        for value in self.cases.iter() {
            if let Some(val) = &value.1 {
                let type_ = Type::from_value(val);
                if type_ != init_type {
                    // TODO implement Display for Value
                    errors.push(err!(
                        self.source,
                        "{:?}:{} is not the same type as {:?}:{}",
                        val,
                        type_,
                        init_val,
                        init_type
                    ));
                }
            }
        }
        if !errors.is_empty() {
            Err(Error::from_vec(errors))
        } else {
            Ok(())
        }
    }
}
