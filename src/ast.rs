mod context;
mod enums;
mod value;
mod resource;

use crate::error::*;
use crate::parser::*;
use std::collections::{HashMap,HashSet};

use self::context::VarContext;
use self::enums::EnumList;
use self::value::Value;
use self::resource::ResourceDef;

#[derive(Debug)]
pub struct AST<'src> {
    errors: Vec<Error>,
    context: VarContext<'src>,
    enum_list: EnumList<'src>,
    variable_declarations: HashMap<Token<'src>, Value<'src>>,
    parameter_defaults: HashMap<(Token<'src>, Option<Token<'src>>), Vec<Option<Value<'src>>>>,
    resource_list: HashSet<Token<'src>>,
    children: HashMap<Token<'src>,HashSet<Token<'src>>>, // TODO maybe we don't need both
    parents: HashMap<Token<'src>,Token<'src>>,
    resources: HashMap<Token<'src>, ResourceDef<'src>>,
//    pub global_context: GlobalContext<'src>,
//    pub resources: HashMap<Token<'src>, ResourceDef<'src>>,
//    pub variable_declarations: HashMap<Token<'src>, Value<'src>>,
}

//pub fn collect_results<I,F,X>(it: I, f: F, errors: &mut Vec<Error>)
//where
//    I: Iterator<Item = X>,
//    F: FnMut(X) -> Result<()>,
//{
//    errors.extend(it.map(f).filter(Result::is_err).map(Result::unwrap_err))
//}
pub fn vec_collect_results<I,F,X,Y>(it: I, f: F, errors: &mut Vec<Error>) -> Vec<Y>
where
    I: Iterator<Item = X>,
    F: FnMut(X) -> Result<Y>,
{
    it.map(f)
      .filter(|r| if let Err(e) = r { errors.push(e.clone()); false } else { true })
      .map(Result::unwrap)
      .collect()
}
impl<'src> AST<'src> {
    /// Produce the final AST data structure.
    /// Call this when all files have been parsed.
    pub fn from_past(past: PAST) -> Result<AST> {
        let PAST { 
            enums, enum_mappings, resources, states,
            variable_declarations, parameter_defaults,
            parents, aliases,
        } = past;
        let mut ast = AST::new();
        ast.add_enums(enums);
        ast.add_enum_mappings(enum_mappings);
        ast.add_variables(variable_declarations);
        ast.add_default_values(parameter_defaults);
        ast.add_resource_list(&resources);
        ast.add_parent_list(parents);
        ast.add_resources(resources, states);
        //ast.add_aliases(aliases);
        // TODO codeindex checks
        if ast.errors.is_empty() {
            Ok(ast)
        } else {
            Err(Error::from_vec(ast.errors))
        }
    }
    fn new() -> AST<'src> {
        AST {
            errors: Vec::new(),
            context: VarContext::new(),
            enum_list: EnumList::new(),
            variable_declarations: HashMap::new(),
            parameter_defaults: HashMap::new(),
            resource_list: HashSet::new(),
            children: HashMap::new(),
            parents: HashMap::new(),
            resources: HashMap::new(),
        }
    }
    
    /// Insert all initial enums
    fn add_enums(&mut self, enums: Vec<PEnum<'src>>) {
        let context = &mut self.context; // borrow checking out of the closure
        for en in enums {
            if en.global {
                if let Err(e) = context.new_enum_variable(None, en.name, en.name, None) {
                    self.errors.push(e);
                }
            }
            if let Err(e) = self.enum_list.add_enum(en) {
                self.errors.push(e);
            }
        }
    }

    /// Insert all enum mappings
    fn add_enum_mappings(&mut self, enum_mappings: Vec<PEnumMapping<'src>>) {
        let mut mappings = enum_mappings;
        let enum_list = &mut self.enum_list; // borrow checking out of the closure
        // Iterate over mappings as long as we can insert some
        loop {
            let map_count = mappings.len();
            // Try inserting every mapping that have an existing ancestor until there is no more
            let mut new_mappings = Vec::new();
            for em in mappings {
                if enum_list.enum_exists(em.from) {
                    if let Err(e) = enum_list.add_mapping(em) {
                        self.errors.push(e);
                    }
                } else {
                    new_mappings.push(em);
                }
            }
            if new_mappings.is_empty() {
                // Yay, finished !
                break;
            } else if map_count == new_mappings.len() {
                // Nothing changed since last loop, we failed !
                for em in new_mappings {
                    self.errors.push(err!(
                            em.to,
                            "Enum {} not found when trying to define mapping {}",
                            em.from,
                            em.to
                    ));
                }
                break;
            }
            mappings = new_mappings;
        }
    }

    /// Insert variables types into the variables context
    /// Insert the variables definition into the global declaration space
    fn add_variables(&mut self, variable_declarations: Vec<(Token<'src>, PValue<'src>)>) {
        // TODO must also store variable declarations
        let context = &mut self.context; // borrow checking out of the closure
        for (variable, value) in variable_declarations {
            if let Err(e) = context.new_variable(None, variable, value.get_type()) {
                self.errors.push(e);
            }
        }
    }

    /// Compute default parameter values
    fn add_default_values(
        &mut self,
        parameter_defaults: Vec<(Token<'src>, Option<Token<'src>>, Vec<Option<PValue<'src>>>)>,
    ) {
        let pdefaults = &mut self.parameter_defaults;
        let errors = &mut self.errors;
        for (resource,state,defaults) in parameter_defaults {
            // parameters with default values must be the last ones
            if let Err(e) = defaults.iter()
                .fold(Ok(None), |state, pv|
                      match (state, pv) {
                          (Ok(None), None)       => Ok(None),
                          (Ok(None), Some(x))    => Ok(Some(x)),
                          (Ok(Some(x)), Some(y)) => Ok(Some(y)),
                          //default followed by non default
                          //TODO pvalue to error
                          //(Ok(Some(x)), None)    => Err(err!(x,"Parameter default {} follow by parameter without default",x)),
                          (Ok(Some(x)), None)    => Err(Error::User("Parameter default follow by parameter without default".into())),
                          (Err(e), _)            => Err(e),
                      }
                )
            { errors.push(e); } // -> no default values
            pdefaults.insert(
                (resource,state),
                vec_collect_results(
                    defaults.into_iter().filter(Option::is_some),
                    |def| {
                        Ok(match def {
                            Some(pvalue) => Some(Value::from_static_pvalue(pvalue)?),
                            None => None,
                        })
                    },
                    errors
                )
            );
        }
    }

    /// List all resources and detect duplicates
    fn add_resource_list(&mut self, resources: &Vec<PResourceDef<'src>>) {
        for res in resources {
            if self.resource_list.contains(&res.name) {
                self.errors.push(err!(&res.name, "Resource {} already defined at {}", &res.name, self.resource_list.get(&res.name).unwrap()));
            } else {
                self.resource_list.insert(res.name);
            }
        }
    }

    /// Compute manually declared parent/child relationships
    fn add_parent_list(&mut self, parents: Vec<(Token<'src>, Token<'src>)>) {
        // TODO complete with autodetected relationships
        for (child,parent) in parents {
            if !self.resource_list.contains(&parent) {
                self.errors.push(err!(&child, "Resource {} declares {} as a parent, but it doesn't exist", child, parent));
            } else {
                self.parents.insert(child,parent);
                self.children.entry(parent).or_insert(HashSet::new());
                self.children.get_mut(&parent).unwrap().insert(child);
            }
        }
    }

    /// Create and store resource objects
    fn add_resources(&mut self, resources: Vec<PResourceDef<'src>>, states: Vec<PStateDef<'src>>) {
        // first separate states by resource
        let mut state_list = self.resource_list.iter()
            .map(|k| (*k,Vec::new()))
            .collect::<HashMap<Token<'src>,Vec<PStateDef<'src>>>>();
        for st in states {
            match state_list.get_mut(&st.resource_name) {
                None => self.errors.push(err!(st.name, "Resource {} has not been defined for state {}", st.resource_name, st.name)),
                Some(v) => v.push(st),
            }
        }
        // now create resources
        for res in resources {
            let name = res.name.clone();
            // or else because we have not stopped on duplicate resources
            let states = state_list.remove(&name).unwrap_or_else(|| Vec::new());
            match ResourceDef::from_presourcedef(res, states, &self.context, &self.parameter_defaults, &self.enum_list) {
                Err(e) => self.errors.push(e),
                Ok(r)  => { self.resources.insert(name, r); } ,
            }
        }
        
    }
}    
