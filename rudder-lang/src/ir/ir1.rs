// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use super::{context::*, enums::EnumList, resource::*, value::*};
use crate::{error::*, parser::*};
use std::{rc::Rc, collections::{HashMap, HashSet}};

/// An IR1 is the first intermediate representation
/// - the context is filled with all global variables: enum, definition and magics
/// - enums list are checked for existence, non duplication and validity
/// - variable definition are checked for non duplication, their type is known, extension are merged
/// - variable declaration (aka magic) are checked for non duplication
/// - default values are guaranteed to be constant
/// - parameter type are known
/// - resources hierarchy is created and they also have their IR
#[derive(Debug)]
pub struct IR1<'src> {
    pub errors: Vec<Error>,
    // the context is used for variable lookup whereas variable_definitions is used for code generation
    pub context: Rc<VarContext<'src>>,
    pub enum_list: EnumList<'src>,
    pub variable_definitions: HashMap<Token<'src>, ComplexValue<'src>>,
    pub parameter_defaults: HashMap<(Token<'src>, Option<Token<'src>>), Vec<Option<Constant<'src>>>>, // also used as parameter list since that's all we have
    pub resource_list: HashSet<Token<'src>>,
    pub resources: HashMap<Token<'src>, ResourceDef<'src>>,
}

pub fn vec_collect_results<I, F, X, Y>(it: I, f: F, errors: &mut Vec<Error>) -> Vec<Y>
    where
        I: Iterator<Item = X>,
        F: FnMut(X) -> Result<Y>,
{
    it.map(f)
      .filter(|r| {
          if let Err(e) = r {
              errors.push(e.clone());
              false
          } else {
              true
          }
      })
      .map(Result::unwrap)
      .collect()
}
impl<'src> IR1<'src> {
    /// Produce the IR1 data structure.
    /// Call this when all files have been parsed.
    pub fn from_past(past: PAST<'src>) -> Result<Self> {
        let PAST {
            enum_aliases,
            enums,
            sub_enums,
            resources,
            states,
            variable_definitions,
            variable_extensions,
            variable_declarations,
            parameter_defaults,
            parents,
            aliases: _aliases,
        } = past;
        let mut ir = IR1::new();
        ir.add_enums(enums);
        ir.add_sub_enums(sub_enums);
        ir.add_enum_aliases(enum_aliases);
        ir.add_variables(variable_definitions);
        ir.add_variable_extensions(variable_extensions);
        ir.add_magic_variables(variable_declarations);
        ir.add_default_values(parameter_defaults);
        ir.add_resource_list(&resources);
        let children = ir.create_children_list(parents);
        ir.add_resources(resources, states, children);
        //ir.add_aliases(aliases);
        if ir.errors.is_empty() {
            Ok(ir)
        } else {
            Err(Error::from_vec(ir.errors))
        }
    }
    fn new() -> IR1<'src> {
        IR1 {
            errors: Vec::new(),
            context: Rc::new(VarContext::new(None)),
            enum_list: EnumList::new(),
            variable_definitions: HashMap::new(),
            parameter_defaults: HashMap::new(),
            resource_list: HashSet::new(),
            resources: HashMap::new(),
        }
    }

    /// Insert all initial enums
    fn add_enums(&mut self, enums: Vec<PEnum<'src>>) {
        let context = Rc::get_mut(&mut self.context).expect("Context has not been allocated before enums !");
        for en in enums {
            if en.global {
                if let Err(e) = context
                    .add_variable_declaration(en.name, Type::Enum(en.name))
                {
                    self.errors.push(e);
                }
            }
            if let Err(e) = self.enum_list.add_enum(en) {
                self.errors.push(e);
            }
        }
    }

    /// Insert all sub enum
    fn add_sub_enums(&mut self, mut sub_enums: Vec<PSubEnum<'src>>) {
        loop {
            let map_count = sub_enums.len();
            // Try inserting every mapping that have an existing ancestor until there is no more
            let mut new_enums = Vec::new();
            for se in sub_enums {
                match self.enum_list.extend_enum(se) {
                    Ok(Some(e)) => new_enums.push(e),
                    Ok(None) => {}
                    Err(e) => self.errors.push(e),
                }
            }
            if new_enums.is_empty() {
                // Yay, finished !
                break;
            } else if map_count == new_enums.len() {
                // Nothing changed since last loop, we failed !
                for se in new_enums {
                    match se.enum_name {
                        // This should be a global enum item
                        None => self.errors.push(err!(
                            se.name,
                            "Global enum item {} not found when trying to define sub enum {}",
                            se.name,
                            get_suggestion_message(
                                se.name.fragment(),
                                self.enum_list.global_item_iter()
                            )
                        )),
                        // We know in which enum we shoud be
                        Some(name) => self.errors.push(err!(
                            se.name,
                            "Enum {} item {} not found when trying to define sub enum {}",
                            name,
                            se.name,
                            get_suggestion_message(
                                se.name.fragment(),
                                self.enum_list.enum_item_iter(name)
                            )
                        )),
                    }
                }
                break;
            }
            sub_enums = new_enums;
        }
    }

    /// Insert all enums aliases
    fn add_enum_aliases(&mut self, aliases: Vec<PEnumAlias<'src>>) {
        for alias in aliases {
            if let Err(e) = self.enum_list.add_alias(alias) {
                self.errors.push(e);
            }
        }
    }

    /// Insert variables definition into the global context
    /// Insert the variables definition into the global definition space
    fn add_variables(&mut self, variables: Vec<PVariableDef<'src>>) {
        let context = Rc::get_mut(&mut self.context).expect("Context has not been allocated before variables !");
        for variable in variables {
            let PVariableDef {
                metadata,
                name,
                value,
            } = variable;
            match ComplexValue::from_pcomplex_value(&self.enum_list, context, value) {
                Err(e) => self.errors.push(e),
                Ok(val) => match Type::from_complex_value(&val) {
                    Err(e) => self.errors.push(e),
                    Ok(type_) => match context.add_variable_declaration(name, type_) {
                        Err(e) => self.errors.push(e),
                        Ok(()) => { self.variable_definitions.insert(name, val); }
                    }
                }
            }
        }
    }

    /// Add the variables extensions to the global definition space
    fn add_variable_extensions(&mut self, variables: Vec<PVariableExt<'src>>) {
        for variable in variables {
            let PVariableExt {
                name,
                value,
            } = variable;
            match self.variable_definitions.get_mut(&name) {
                None => self.errors.push(err!(name, "Variable {} has never been defined", name)),
                Some(v) => if let Err(e) = v.extend(&self.enum_list, &self.context, value) {
                    self.errors.push(e);
                },
            }
        }
    }

    /// Insert the variables declarations into the global context
    fn add_magic_variables(&mut self, variables: Vec<PVariableDecl<'src>>) {
        let context = Rc::get_mut(&mut self.context).expect("Context has not been allocated before magic variables !");
        for variable in variables {
            let PVariableDecl {
                metadata,
                name,
                sub_elts,
                type_,
            } = variable;
            match Type::from_ptype(type_, sub_elts) {
                Ok(var_type) => {
                    if let Err(e) = context.add_variable_declaration(name, var_type) {
                        self.errors.push(e);
                    }
                }
                Err(e) => self.errors.push(e),
            }
        }
    }

    /// Compute default parameter values
    fn add_default_values(
        &mut self,
        parameter_defaults: Vec<(Token<'src>, Option<Token<'src>>, Vec<Option<PValue<'src>>>)>,
    ) {
        for (resource, state, defaults) in parameter_defaults {
            // parameters with default values must be the last ones
            if let Err(e) = defaults.iter()
                                    .fold(Ok(None), |status, pv|
                                        match (status, pv) {
                                            (Ok(None), None) => Ok(None),
                                            (Ok(None), Some(x)) => Ok(Some(x)),
                                            (Ok(Some(_)), Some(y)) => Ok(Some(y)),
                                            //default followed by non default
                                            (Ok(Some(_)), None) => {
                                                match state {
                                                    Some(s) => self.errors.push(err!(s,"Parameter default for state {} followed by parameter without default",s)),
                                                    None => self.errors.push(err!(resource,"Parameter default for resource {} followed by parameter without default",resource)),
                                                };
                                                Ok(None)
                                            },
                                            (Err(e), _) => Err(e),
                                        }
                                    )
            { self.errors.push(e); } // -> no default values
            self.parameter_defaults.insert(
                (resource, state),
                vec_collect_results(
                    // we could keep only 'Some' parameter if this was not aso used for parameter
                    // counting
                    defaults.into_iter(), //.filter(Option::is_some),
                    |def| {
                        Ok(match def {
                            Some(pvalue) => Some(Constant::from_pvalue(pvalue)?),
                            None => None,
                        })
                    },
                    &mut self.errors,
                ),
            );
        }
    }

    /// List all resources and detect duplicates
    fn add_resource_list(&mut self, resources: &[PResourceDef<'src>]) {
        for res in resources {
            if self.resource_list.contains(&res.name) {
                self.errors.push(err!(
                    &res.name,
                    "Resource {} already defined at {}",
                    &res.name,
                    self.resource_list.get(&res.name).unwrap()
                ));
            } else {
                self.resource_list.insert(res.name);
            }
        }
    }

    /// Compute manually declared parent/child relationships
    fn create_children_list(
        &mut self,
        parents: Vec<(Token<'src>, Token<'src>)>,
    ) -> HashMap<Token<'src>, HashSet<Token<'src>>> {
        let mut children = HashMap::new();
        for (child, parent) in parents {
            if !self.resource_list.contains(&parent) {
                self.errors.push(err!(
                    &child,
                    "Resource {} declares {} as a parent, but it doesn't exist{}",
                    child,
                    parent,
                    get_suggestion_message(parent.fragment(), self.resource_list.iter()),
                ));
            } else {
                children.entry(parent).or_insert_with(HashSet::new);
                children.get_mut(&parent).unwrap().insert(child);
            }
        }
        children
    }

    /// Create and store resource objects
    fn add_resources(
        &mut self,
        resources: Vec<PResourceDef<'src>>,
        states: Vec<PStateDef<'src>>,
        mut children: HashMap<Token<'src>, HashSet<Token<'src>>>,
    ) {
        // first separate states by resource
        let mut state_list = self
            .resource_list
            .iter()
            .map(|k| (*k, Vec::new()))
            .collect::<HashMap<Token<'src>, Vec<PStateDef<'src>>>>();
        for st in states {
            match state_list.get_mut(&st.resource_name) {
                None => self.errors.push(err!(
                    st.name,
                    "Resource {} has not been defined for state {}{}",
                    st.resource_name,
                    st.name,
                    get_suggestion_message(st.resource_name.fragment(), state_list.keys())
                )),
                Some(v) => v.push(st),
            }
        }
        // now create resources
        for res in resources {
            let name = res.name;
            // if this is a duplicate, just ignore, the case has already been handled in add_resource_list
            if self.resources.contains_key(&name) { continue }
            // or else because we have not stopped on duplicate resources
            let states = state_list.remove(&name).unwrap_or_else(Vec::new);
            let res_children = children.remove(&name).unwrap_or_else(HashSet::new);
            let (errs, resource) = ResourceDef::from_presourcedef(
                // TODO move param count errors checker out of from_presourcedef
                res,
                states,
                res_children,
                self.context.clone(),
                &self.parameter_defaults,
                &self.enum_list,
            );
            self.errors.extend(errs);
            if let Some(r) = resource {
                self.resources.insert(name, r);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // parse_str_ok appends to source while parse_str_err doesn't
    // this allow continuing tests with previous ok tests
    fn parse_str_ok<'src>(source: &mut String, name: &'src str, append: &'src str) {
        source.push_str(append);
        let res = parse_str(name, &source);
        assert!(res.is_ok(), "Error instead of OK {:?}", res);
    }

    fn parse_str_err<'src>(source: &mut String, name: &'src str, append: &'src str) {
        let mut source = source.clone();
        source.push_str(append);
        let res = parse_str(name, &source);
        assert!(res.is_err(), "OK instead of Error {:?}", res);
    }

    fn parse_str<'src>(name: &'src str, source: &'src str) -> Result<IR1<'src>> {
        let mut past = PAST::new();
        past.add_file(name, source).expect("Test should use valid syntax");
        IR1::from_past(past)
    }

    /// - enums list are checked for existence, non duplication and validity
    /// - context is filled
    #[test]
    fn test_enums() {
        let mut source = "@format=0\n".to_string();

        parse_str_ok(&mut source, "global_enum1", "global enum G { a, b, c }\n");
        parse_str_err(&mut source, "global_enum2", "global enum G { d, e, f }\n"); // enum names are unique
        parse_str_err(&mut source, "global_enum3", "global enum H { a, b, c }\n"); // global enum items are unique
        parse_str_err(&mut source, "global_enum4", "items in a { b, c }\n");       // global enum items are unique within tree
        parse_str_ok(&mut source, "global_enum5", "items in a { aa, ab }\n");

        parse_str_ok(&mut source, "enum1", "enum E { a, b, c }\n");       // non global enum item are not globally unique
        parse_str_ok(&mut source, "enum2", "enum F { d, e, f, g }\n");
        parse_str_err(&mut source, "enum3", "items in g { ga, gb }\n");   // non global enum item need an enum identifier
        parse_str_ok(&mut source, "enum4", "items in F.g { ga, gb }\n");
        parse_str_err(&mut source, "enum5", "items in F.f { ga, gb }\n"); // enum items are unique within tree

        parse_str_err(&mut source, "enum_var1", "let G=\"x\"\n");  // global enum automatically declare an variable
        parse_str_ok(&mut source, "enum_var2", "let E=\"x\"\n");

        parse_str_err(&mut source, "enum_alias1", "enum alias A=G\n");    // alias are for items
        parse_str_err(&mut source, "enum_alias2", "enum alias a=G.a\n");  // global alias are unique like items are unique
        parse_str_err(&mut source, "enum_alias3", "enum alias a=E.a\n");  // non global alias are unique like items are unique
        parse_str_err(&mut source, "enum_alias3", "enum alias w=X.a\n");  // alias must reference existing item
        parse_str_ok(&mut source, "enum_alias4", "enum alias ax=G.a\n");
        parse_str_ok(&mut source, "enum_alias5", "enum alias ay=E.a\n");
        parse_str_ok(&mut source, "enum_alias6", "enum alias az=F.ga\n");
    }

    /// - variable definition are checked for non duplication, their type is known, extension are merged
    /// - variable declaration (aka magic) are checked for non duplication
    /// - context is filled
    #[test]
    fn test_vars() {
        let mut source = "@format=0\n".to_string();

        parse_str_ok(&mut source, "magic1", "let m\n");
        parse_str_err(&mut source, "magic2", "let m\n");      // variable cannot be overridden
        parse_str_ok(&mut source, "magic3", "let n.m\n");
        parse_str_ok(&mut source, "magic4", "let n.n\n");
        parse_str_ok(&mut source, "magic5", "let o.m.m\n");
        parse_str_ok(&mut source, "magic6", "let p:string\n");

        parse_str_ok(&mut source, "var1", "let v=\"val\"\n");
        parse_str_err(&mut source, "var2", "let v=\"val\"\n"); // variable cannot be overridden
        parse_str_err(&mut source, "var3", "let m=\"val\"\n"); // magic variable cannot be overridden
        parse_str_err(&mut source, "var4", "let v\n");

        parse_str_ok(&mut source, "complex0", "global enum complete { X, Y }\n");
        parse_str_ok(&mut source, "complex1", "let c1 = case { X => \"val\", default => \"val2\" }\n");
        parse_str_ok(&mut source, "complex2", "let c2 = case { X => \"val\", Y => \"val2\" }\n");

        parse_str_ok(&mut source, "ext1", "v=if X => \"val\"\n");
    }

    /// - default values are guaranteed to be constant
    /// - parameter type are known
    #[test]
    fn test_resource() {
        let mut source = "@format=0\n".to_string();

        parse_str_ok(&mut source, "resource1", "resource r(x) {}\n");
        parse_str_err(&mut source, "resource2", "resource r(x) {}\n"); // redefining the same resource is forbidden
        parse_str_err(&mut source, "resource3", "resource r() {}\n");  // redefining a resource with different parameters is forbidden
        parse_str_ok(&mut source, "resource4", "resource s(x)\n");
        parse_str_ok(&mut source, "resource5", "resource t(x:string,y) {}\n");
        parse_str_err(&mut source, "resource6", "resource u(x=\"str\",y) {}\n"); // default values must come last
        parse_str_ok(&mut source, "resource7", "resource u(y,x=\"str\") {}\n");
        parse_str_err(&mut source, "resource8", "resource v(y,x=value) {}\n");   // default values must be constant
        parse_str_err(&mut source, "resource9", "resource v(y,x=\"${value}\") {}\n"); // default values must be constant
    }

    /// - resources hierarchy is created
    #[test]
    fn test_resource_children() {
        let mut source = "@format=0\n".to_string();

        parse_str_ok(&mut source, "resource1", "resource r(x)\n");
        // TODO
    }

    // TODO
    // test_resource_body
    /// - resources also have their IR
    // test_state
}
