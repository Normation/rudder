use crate::error::*;
use crate::parser::{PToken,PEnum,PEnumMapping,PEnumExpression};
use std::collections::HashMap;
use std::collections::HashSet;

// As single enum can be derived into different set through multiple mappings
// However a single enum can have only one parent
pub struct EnumList<'a> {
    // Map an enum name to another one which has a derived definition
    //                    to         from
    mapping_path: HashMap<PToken<'a>,PToken<'a>>,
    // Map an enum content to another one
    //         enum    from       to          mapping from       to
    mappings: HashMap<(PToken<'a>,PToken<'a>),HashMap<PToken<'a>,PToken<'a>>>,
    // List values for a given enum
    //             enum       global values
    enums: HashMap<PToken<'a>,(bool,HashSet<PToken<'a>>)>,
    // List global values (they must not be redefined)
    //                    value      enum
    global_values: HashMap<PToken<'a>,PToken<'a>>,
}

#[derive(Debug, PartialEq)]
pub enum EnumExpression<'a> {
    //       variable   enum        value
    Compare(PToken<'a>, PToken<'a>, PToken<'a>),
    And(Box<EnumExpression<'a>>, Box<EnumExpression<'a>>),
    Or(Box<EnumExpression<'a>>, Box<EnumExpression<'a>>),
    Not(Box<EnumExpression<'a>>),
    Default,
}

impl<'a> EnumList<'a> {
    // Constructor
    pub fn new() -> EnumList<'static> {
        EnumList {
            mapping_path: HashMap::new(),
            mappings: HashMap::new(),
            enums: HashMap::new(),
            global_values: HashMap::new(),
        }
    }
    
    // Insert a simple declared enum
    pub fn add_enum(&mut self, e: PEnum<'a>) -> Result<()> {
        let mut list = HashSet::new();
        // Check for set name duplicate
        if self.enums.contains_key(&e.name) {
            // trick to extract the original key, since they do not have the same debug info
            let position = self.enums.entry(e.name).key().position_str();
            fail!(self.enums.entry(e.name).key(), "Enum {} already defined at {}", e.name, position);
        }
        let parent_enum = self.mapping_path.get(&e.name);
        for v in &e.items {
            // Check for local uniqueness (not for mapping)
            if list.contains(v) && parent_enum.is_none() {
                fail!(v, "Value {} already declared in the same enum {}", v, e.name);
            }
            // check for global uniqueness
            // defined in parent is allowed, twice in the same mapping is allowed
            match self.global_values.get(v) {
                Some(e0) => {
                    if parent_enum.is_none() || (parent_enum.unwrap() != e0 && e.name != *e0) {
                        fail!(v, "Value {} from enum {} already declared in the global enum {}", v, e.name, e0);
                    }
                },
                None => (),
            }
            // store globaly uniques
            if e.global {
                // If the value already exist, update its enum
                // WARN: but do not update its position
                self.global_values.insert(*v, e.name);
            }
            // keep value
            list.insert(*v);
        }
        // store data
        self.enums.insert(e.name, (e.global, list));
        Ok(())
     }

    // insert a enum defined from a mapping
    pub fn add_mapping(&mut self, e: PEnumMapping<'a>) -> Result<()> {
        // From must exist
        match self.enums.get(&e.from) {
            Some((global,values)) => {
                // transform mapping into temporary hashmap
                let mut pmapping = HashMap::new();
                for (f,t) in e.mapping {
                    // check for duplicates
                    if pmapping.contains_key(&f) {
                        fail!(f, "{} used twice in mapping {}", f, e.to);
                    }
                    // Check for invalids
                    if !(values.contains(&f) || *f == "*") {
                        fail!(f, "Value {} used in mapping {} does not exist in {}", f, e.to, *e.from);
                    }
                    pmapping.insert(f,t);
                }
                // store mapping into final hashmap
                let mut mapping = HashMap::new();
                let mut items: Vec<PToken> = Vec::new();
                for v in values {
                    match pmapping.get(v).or_else(|| pmapping.get(&"*".into())) {
                        Some(t) => {
                            if **t == "*" {
                                mapping.insert(*v,*v);
                                items.push(*v);
                            } else {
                                mapping.insert(*v,*t);
                                items.push(*t);
                            }
                        },
                        None => fail!(v, "Value {} defined in {} is not used in mapping {}", v, *e.from, e.to),
                    }
                }
                self.mapping_path.insert(e.to,e.from);
                self.mappings.insert((e.from,e.to),mapping);
                self.add_enum(PEnum{global: *global, name: e.to, items})
            },
            None => fail!(e.to, "Enum {} missing when trying to define {}", e.from, e.to),
            //None => err( format!("Enum {} missing when trying to define {}", e.from, e.to) ),
        }
    }


    // find enum path from e1 fo e2
    fn find_path(&'a self, e1: PToken<'a>, e2: PToken<'a>) -> Option<Vec<PToken<'a>>> {
        // terminate recursion
        if e1 == e2 {
            Some(vec![e1])
        } else {
            // traverse mapping path starting from the end
            match self.mapping_path.get(&e2) {
                None => None,
                Some(e) => {
                    match self.find_path(e1, *e) {
                        None => None,
                        Some(mut path) => {
                            path.push(e2);
                            Some(path)
                        }
                    }
                }
            }
        }
    }

    // Find oldest ancestor of e1
    fn find_elder(&self, e1: PToken<'a>) -> PToken<'a> {
        match self.mapping_path.get(&e1) {
            None => e1,
            Some(e) => self.find_elder(*e),
        }
    }

    pub fn canonify_expression(&'a self, expr:&'a PEnumExpression) -> Result<EnumExpression<'a>> {
        match expr {
            PEnumExpression::Default   => Ok(EnumExpression::Default),
            PEnumExpression::Not(e)    => Ok(EnumExpression::Not(Box::new(self.canonify_expression(e)?))),
            PEnumExpression::Or(e1,e2) => Ok(EnumExpression::Or(
                    Box::new(self.canonify_expression(e1)?),
                    Box::new(self.canonify_expression(e2)?))),
            PEnumExpression::And(e1,e2) => Ok(EnumExpression::And(
                    Box::new(self.canonify_expression(e1)?),
                    Box::new(self.canonify_expression(e2)?))),
            PEnumExpression::Compare(var,enum1,value) => {
                // get enum1 real value
                let e1 = match enum1 {
                    Some(e) => e,
                    None => match var {
                        // no var -> global enum
                        None => match self.global_values.get(&value) {
                            None => fail!(value, "Global enum value {} does not exist", value),
                            Some(e) => e,
                        },
                        // var -> guess from type
                        // TODO we don't have var context yet
                        Some(var1) => fail!(var1,"TODO var context"),
                    },
                };
                // get var real value
                let var1 = match var {
                    Some(v) => *v,
                    None => match self.enums.get(&e1) {
                        // or get it from a global enum
                        None => fail!(e1, "No such enum {}", e1),
                        Some((false,_)) => fail!(e1, "Enum {} is not global, you must provide a variable", e1),
                        Some((true,_)) => self.find_elder(*e1),
                    },
                };
                // once we have everything:
                // check that var exists and type matches enum TODO when we have var context
                // check that enum exists and has var
                match self.enums.get(&e1) {
                    None => fail!(e1, "Enum {} does not exist", e1),
                    Some((_,list)) => if ! list.contains(&value) { fail!(value, "Value {} is not defined in enum {}", value, e1) },
                }
                Ok(EnumExpression::Compare(var1,*e1,*value))
            }
        }
    }

    // used only by is_ancestor (path is destroyed)
    fn transform_value(&self, mut path: Vec<PToken<'a>>, value: PToken<'a>) -> PToken<'a> {
        // <1 should not happen
        if path.len() <= 1 { return value; }
        let e0 = path.remove(0);
        let e1 = path[0];
        let v = self.mappings
                    .get(&(e0,e1))
                    .unwrap()  // should not fail since all enums must be defined
                    .get(&value)
                    .unwrap(); // should not fail since all values must be defined
        self.transform_value(path, *v)
    }
    // return true is e1::v1 is an ancestor of e2::v2
    fn is_ancestor(&self, e1: PToken<'a>, v1: PToken<'a>, e2: PToken<'a>, v2: PToken<'a>) -> bool {
        match self.find_path(e1, e2) {
            Some(path) => { 
                self.transform_value(path, v1) == v2
            },
            None => false,
        }
    }

//    // evaluate a boolean expression given a set of variable=enum:value
//    fn eval(&self, values:&HashMap<PToken<'a>,(PToken<'a>,PToken<'a>)>, expr:&PEnumExpression) -> bool {
//        match expr {
//            PEnumExpression::Default   => true,
//            PEnumExpression::Not(e)    => !self.eval(values,&e),
//            PEnumExpression::Or(e1,e2) => self.eval(values,&e1) || self.eval(values,&e2),
//            PEnumExpression::And(e1,e2) => self.eval(values,&e1) && self.eval(values,&e2),
//            PEnumExpression::Compare(var,enum1,value) => {
//                // unwrap panic would be a bug since all values should be defined now
//                let(s,v) = values.get(&var).unwrap();
//                self.is_ancestor(s,v,enum1,value)
//            }
//        }
//    }
}

//fn list_variables_set(expr:&EnumExpression) -> Vec<(String,String) {
//    
//}
//pub fn evaluate(Vec<expr>) -> (True/missing(expr), disjointe/overlap(e1,e2))
//{
//    pset = get_parameter_sets(Vec<expr>)
//    pset = get_root_sets(pset)
//    for i in pset.values
//        for j in expr, 
//            over[j] = eval expr
//        if count(over) > 1 -> overlap
//        if count(over) = 0 -> missing (create_expr(i))
//
//    // There's probably a place for optimisation here, we know the truth table or the expression,
//    // -> it can probably be simplified
//}
//
//fn largest_set(set,name,target_set)

#[cfg(test)]
mod tests {
    use super::*;
    use crate::parser;
    use crate::parser::*;

    fn penum(string: &str) -> PEnum { parser::penum(pinput("", string)).unwrap().1 }
    #[test]
    fn test_insert() {
        let mut e = EnumList::new();
        assert!(e.add_enum(penum("enum abc { a, a, c }")).is_err());
        assert!(e.add_enum(penum("global enum abc { a, b, c }")).is_ok());
        assert!(e.add_enum(penum("enum abc { a, b, c }")).is_err());
        assert!(e.add_enum(penum("enum abc2 { a, b, c }")).is_err());
        assert!(e.add_mapping(enum_mapping("enum abc ~> def { a -> b, b -> b }")).is_err());
        assert!(e.add_mapping(enum_mapping("enum abx ~> def { a -> b, b -> b, c->c }")).is_err());
        assert!(e.add_mapping(enum_mapping("enum abc ~> abc { a -> b, b -> b, c->c }")).is_err());
        assert!(e.add_mapping(enum_mapping("enum abc ~> def { a -> b, b -> b, x->c }")).is_err());
        assert!(e.add_mapping(enum_mapping("enum abc ~> def { a -> b, b -> b, *->* }")).is_ok());
    }

    fn enum_mapping(string: &str) -> PEnumMapping { parser::enum_mapping(pinput("", string)).unwrap().1 }
    fn init_tests() -> EnumList<'static> {
        let mut e = EnumList::new();
        e.add_enum(penum("global enum os { debian, ubuntu, redhat, centos, aix }")).unwrap();
        e.add_mapping(enum_mapping("enum os ~> family { ubuntu->debian, centos->redhat, *->* }")).unwrap();
        e.add_mapping(enum_mapping("enum family ~> type { debian->linux, redhat->linux, aix->unix }")).unwrap();
        e.add_enum(penum("enum outcome { kept, repaired, error }")).unwrap();
        e
    }

    #[test]
    fn test_path() {
        let e = init_tests();
        assert_eq!(e.find_path("os".into(),"outcome".into()), None);
        assert_eq!(e.find_path("os".into(),"os".into()), Some(vec!["os".into()]));
        assert_eq!(e.find_path("os".into(),"type".into()), Some(vec!["os".into(),"family".into(),"type".into()]));
        assert_eq!(e.find_path("type".into(),"os".into()), None);
    }

    #[test]
    fn test_ancestor() {
        let e = init_tests();
        assert_eq!(e.is_ancestor("os".into(),"ubuntu".into(),"os".into(),"ubuntu".into()),true);
        assert_eq!(e.is_ancestor("os".into(),"ubuntu".into(),"os".into(),"debian".into()),false);
        assert_eq!(e.is_ancestor("os".into(),"ubuntu".into(),"family".into(),"debian".into()),true);
        assert_eq!(e.is_ancestor("os".into(),"ubuntu".into(),"type".into(),"linux".into()),true);
        assert_eq!(e.is_ancestor("os".into(),"ubuntu".into(),"type".into(),"unix".into()),false);
        assert_eq!(e.is_ancestor("os".into(),"ubuntu".into(),"outcome".into(),"kept".into()),false);
        assert_eq!(e.is_ancestor("os".into(),"ubuntu".into(),"outcome".into(),"debian".into()),false);
    }

    #[test]
    fn test_elder() {
        let e = init_tests();
        assert_eq!(e.find_elder("os".into()), "os".into());
        assert_eq!(e.find_elder("type".into()), "os".into());
        assert_eq!(e.find_elder("outcome".into()), "outcome".into());
    }

    fn enum_expression(string: &str) -> PEnumExpression { parser::enum_expression(pinput("", string)).unwrap().1 }
    #[test]
    fn test_canonify() {
        let e = init_tests();
        assert!(e.canonify_expression(&enum_expression("ubuntu")).is_ok());
        assert!(e.canonify_expression(&enum_expression("os::ubuntu")).is_ok());
        //assert!(e.canonify_expression(&enum_expression("os=~ubuntu")).is_ok()); TODO var context
        assert!(e.canonify_expression(&enum_expression("os=~os::ubuntu")).is_ok());
        // assert!(e.canonify_expression(&enum_expression("os=~linux")).is_ok()); TODO var context
        assert!(e.canonify_expression(&enum_expression("os=~type::linux")).is_ok());
        assert!(e.canonify_expression(&enum_expression("os=~type::debian")).is_err());
        assert!(e.canonify_expression(&enum_expression("os=~typo::debian")).is_err());
        assert!(e.canonify_expression(&enum_expression("typo::debian")).is_err());
        assert!(e.canonify_expression(&enum_expression("outcome::kept")).is_err());
        assert!(e.canonify_expression(&enum_expression("kept")).is_err());
    }
}
