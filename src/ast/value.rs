use crate::error::*;
use crate::parser::*;
use super::context::VarContext;
use super::enums::EnumExpression;
use crate::ast::context::GlobalContext;

#[derive(Debug, PartialEq, Clone)]
pub struct StringObject<'src> {
    pos: Token<'src>,
    data: Vec<PInterpolatedElement>,
}
impl<'src> StringObject<'src> {
    pub fn from_pstring(pos: Token<'src>, s: String) -> Result<StringObject> {
        let data = parse_string(&s[..])?;
        Ok(StringObject { pos, data })
    }
    pub fn format<SF, VF>(&self, str_formatter: SF, var_formatter: VF) -> String
    // string, is_a_suffix, is_a_prefix
    where
        SF: Fn(&str) -> String,
        VF: Fn(&str) -> String,
    {
        self.data.iter()
            .map(
                |x| match x {
                    PInterpolatedElement::Static(s) => str_formatter(s),
                    PInterpolatedElement::Variable(v) => var_formatter(v),
                }
            ).collect::<Vec<String>>()
            .join("")
    }
    pub fn is_empty(&self) -> bool {
        self.data.iter()
            .filter(
                |x| match x {
                    PInterpolatedElement::Static(_) => false,
                    PInterpolatedElement::Variable(_) => true,
                })
            .count() == 0
    }
}

#[derive(Debug, PartialEq, Clone)]
pub enum Value<'src> {
    //     position   format  variables
    String(StringObject<'src>),
    EnumExpression(EnumExpression<'src>),
}
impl<'src> Value<'src> {
    pub fn from_pvalue(gc: &GlobalContext<'src>, lc: Option<&VarContext<'src>>, pvalue: PValue<'src>) -> Result<Value<'src>> {
        match pvalue {
            PValue::String(pos, s) => Ok(Value::String(StringObject::from_pstring(pos, s)?)),
            PValue::Number(pos, n) => unimplemented!(),
            PValue::EnumExpression(e) => Ok(Value::EnumExpression(
                gc.enum_list.canonify_expression(gc,lc,e)?
            )),
            PValue::Struct(s) => unimplemented!(),
            PValue::List(l) => unimplemented!(),
        }
    }

    pub fn from_static_pvalue(pvalue: PValue<'src>) -> Result<Value<'src>> {
        match pvalue {
            PValue::String(pos, s) => Ok(Value::String(StringObject::from_pstring(pos, s)?)),
            PValue::Number(pos, n) => unimplemented!(),
            PValue::EnumExpression(e) => fail!(e.token(), "Enum expression are not allowed in static context"),
            PValue::Struct(s) => unimplemented!(),
            PValue::List(l) => unimplemented!(),
        }
    }

    // TODO check where it is called
    pub fn context_check(
        &self,
        gc: &GlobalContext<'src>,
        lc: Option<&VarContext<'src>>,
    ) -> Result<()> {
        match self {
            Value::String(s) => {
                fix_results(s.data.iter().map(
                    |e| match e {
                        PInterpolatedElement::Static(_) => Ok(()),
                        PInterpolatedElement::Variable(v) => 
                            match gc.get_variable(lc, Token::new("", v)) {
                                None => fail!(s.pos, "Variable {} does not exist at {}", v, s.pos),
                                _ => Ok(()),
                            },
                    },
                ))
            },
            Value::EnumExpression(_) => Ok(()), // check already done at enum creation
        }
    }

    // TODO is it still useful given that it exists for PValue
    pub fn get_type(&self) -> PType {
        match self {
            Value::String(_) => PType::String,
            Value::EnumExpression(_) => PType::Boolean,
        }
    }
}
