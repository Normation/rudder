use super::value::Value;
use super::context::VarContext;
use super::enums::*;
use crate::error::*;
use crate::parser::*;
use std::collections::{HashMap, HashSet};


#[derive(Debug)]
pub struct Resources<'src> {
    pub metadata: HashMap<Token<'src>, Value<'src>>,
    pub parameters: Vec<Parameter<'src>>,
    pub states: HashMap<Token<'src>, StateDef<'src>>,
    pub children: HashSet<Token<'src>>,
}

#[derive(Debug)]
pub struct StateDef<'src> {
    pub metadata: HashMap<Token<'src>, Value<'src>>,
    pub parameters: Vec<Parameter<'src>>,
    pub statements: Vec<Statement<'src>>,
    pub variables: VarContext<'src>,
}


#[derive(Debug)]
pub struct Parameter<'src> {
    pub name: Token<'src>,
    pub ptype: PType,
}
impl<'src> Parameter<'src> {
    pub fn from_pparameter(
        p: PParameter<'src>,
        default: &Option<Value<'src>>,
    ) -> Result<Parameter<'src>> {
        let ptype = match p.ptype {
            Some(t) => t,
            None => {
                if let Some(val) = default {
                    val.get_type()
                } else {
                    // Nothing -> String
                    PType::TString
                }
            }
        };
        Ok(Parameter {
            name: p.name,
            ptype,
        })
    }

    pub fn value_match(&self, param_ref: &Value) -> Result<()> {
        match (&self.ptype, param_ref) {
            (PType::TString, Value::String(_)) => Ok(()),
            (t, _v) => fail!(
                self.name,
                "Parameter {} is not of the type {:?}",
                self.name,
                t
            ),
        }
    }
}

#[derive(Debug)]
pub enum Statement<'src> {
    Comment(PComment<'src>),
    VariableDefinition(Token<'src>, Value<'src>),
    StateCall(
        PCallMode,           // mode
        Token<'src>,         // resource
        Vec<Value<'src>>,    // resource parameters
        Token<'src>,         // state name
        Vec<Value<'src>>,    // parameters
        Option<Token<'src>>, // outcome
    ),
    //   keyword    list of condition          then
    Case(
        Token<'src>,
        Vec<(EnumExpression<'src>, Vec<Statement<'src>>)>,
    ),
    // Stop engine
    Fail(Value<'src>),
    // Inform the user of something
    Log(Value<'src>),
    // Return a specific outcome
    Return(Token<'src>),
    // Do nothing
    Noop,
}
impl<'src> Statement<'src> {
    pub fn fom_pstatement<'b>(
        enum_list: &'b EnumList<'src>,
        gc: Option<&'b VarContext<'src>>,
        context: &'b mut VarContext<'src>,
        children: &'b mut HashSet<Token<'src>>,
        parameter_defaults: &'b HashMap<(Token<'src>, Option<Token<'src>>), Vec<Option<Value<'src>>>>,
        st: PStatement<'src>,
    ) -> Result<Statement<'src>> {
        Ok(match st {
            PStatement::Comment(c) => Statement::Comment(c),
            PStatement::VariableDefinition(var, val) => {
                let value = Value::from_pvalue(val)?;
                // check that definition use existing variables
                value.context_check(gc, context)?;
                context.new_variable(gc, var, value.get_type())?;
                Statement::VariableDefinition(var, value)
            }
            PStatement::StateCall(mode, res, res_params, st, params, out) => {
                if let Some(out_var) = out {
                    // outcome must be defined, token comes from internal compilation, no value known a compile time
                    context.new_enum_variable(
                        gc,
                        out_var,
                        Token::new("internal", "outcome"),
                        None,
                    )?;
                }
                children.insert(res);
                let mut res_parameters = fix_vec_results(res_params.into_iter().map(Value::from_pvalue))?;
                let res_defaults = &parameter_defaults[&(res,None)];
                let res_missing = res_defaults.len() as i32 - res_parameters.len() as i32;
                if res_missing > 0 {
                    fix_results(
                        res_defaults.iter()
                                    .skip(res_parameters.len())
                                    .map(|param| {
                                        match param {
                                            Some(p) => res_parameters.push(p.clone()),
                                            None => fail!(res, "Resources instance of {} is missing parameters and there is no default values for them", res),
                                        };
                                        Ok(())
                                    })
                    )?;
                } else if res_missing < 0 {
                    fail!(res, "Resources instance of {} has too many parameters, expecting {}, got {}", res, res_defaults.len(), res_parameters.len());
                }
                let mut st_parameters =
                    fix_vec_results(params.into_iter().map(Value::from_pvalue))?;
                let st_defaults = &parameter_defaults[&(res,Some(st))];
                let st_missing = st_defaults.len() as i32 - st_parameters.len() as i32;
                if st_missing > 0 {
                    fix_results(
                        st_defaults.iter()
                                   .skip(st_parameters.len())
                                   .map(|param| {
                                       match param {
                                           Some(p) => st_parameters.push(p.clone()),
                                           None => fail!(st, "Resources state instance of {} is missing parameters and there is no default values for them", st),
                                       };
                                       Ok(())
                                   })
                    )?;
                } else if st_missing < 0 {
                    fail!(st, "Resources state instance of {} has too many parameters, expecting {}, got {}", st, st_defaults.len(), st_parameters.len());
                }
                // check that parameters use existing variables
                fix_results(res_parameters.iter().map(|p| p.context_check(gc, context)))?;
                fix_results(st_parameters.iter().map(|p| p.context_check(gc, context)))?;
                Statement::StateCall(mode, res, res_parameters, st, st_parameters, out)
            }
            PStatement::Fail(f) => {
                let value = Value::from_pvalue(f)?;
                // check that definition use existing variables
                value.context_check(gc, context)?;
                // we must fail with a string
                match &value {
                    Value::String(_) => (),
                    _ => unimplemented!(), // TODO must fail here with a message
                }
                Statement::Fail(value)
            }
            PStatement::Log(l) => {
                let value = Value::from_pvalue(l)?;
                // check that definition use existing variables
                value.context_check(gc, context)?;
                // we must fail with a string
                match &value {
                    Value::String(_) => (),
                    _ => unimplemented!(), // TODO must fail here with a message
                }
                Statement::Log(value)
            }
            PStatement::Return(r) => {
                if r == Token::new("", "kept")
                    || r == Token::new("", "repaired")
                    || r == Token::new("", "error")
                {
                    Statement::Return(r)
                } else {
                    fail!(
                        r,
                        "Can only return an outcome (kept, repaired or error) instead of {}",
                        r
                    )
                }
            }
            PStatement::Noop => Statement::Noop,
            PStatement::Case(case, v) => Statement::Case(
                case,
                fix_vec_results(v.into_iter().map(|(exp_str, sts)| {
                    let exp = parse_enum_expression(exp_str)?;
                    Ok((
                        enum_list.canonify_expression(gc, context, exp)?,
                        fix_vec_results(sts.into_iter().map(|st| {
                            Statement::fom_pstatement(enum_list, gc, context, children, parameter_defaults, st)
                        }))?,
                    ))
                }))?,
            ),
        })
    }
}
