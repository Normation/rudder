use super::context::{VarContext, VarKind};
use super::Statement;
use crate::error::*;
use crate::parser::{PEnum, PEnumExpression, PEnumMapping, Token};
use std::collections::hash_set::Iter;
use std::collections::HashMap;
use std::collections::HashSet;

// TODO there can be only one mapping per item that defines an identical descendant

// As single enum can be derived into different set through multiple mappings
// However a single enum can have only one parent
#[derive(Debug)]
pub struct EnumList<'a> {
    // Map an enum name to another one which has a derived definition
    //                            to         from
    reverse_mapping_path: HashMap<Token<'a>, Token<'a>>,
    //                           from       to
    direct_mapping_path: HashMap<Token<'a>, Vec<Token<'a>>>,
    // Map an enum content to another one
    //         enum    from       to          mapping from       to
    mappings: HashMap<(Token<'a>, Token<'a>), HashMap<Token<'a>, Token<'a>>>,
    // List values for a given enum
    //             enum       global values
    enums: HashMap<Token<'a>, (bool, HashSet<Token<'a>>)>,
    // List global values (they must not be redefined)
    //                    value      enum
    global_values: HashMap<Token<'a>, Token<'a>>,
}

#[derive(Debug, PartialEq)]
pub enum EnumExpression<'a> {
    //       variable   enum        value
    Compare(Token<'a>, Token<'a>, Token<'a>),
    And(Box<EnumExpression<'a>>, Box<EnumExpression<'a>>),
    Or(Box<EnumExpression<'a>>, Box<EnumExpression<'a>>),
    Not(Box<EnumExpression<'a>>),
    Default,
}

impl<'a> EnumExpression<'a> {
    pub fn position_str(&self) -> String {
        let (file, line, col) = self.position();
        format!("{}:{}:{}", file, line, col)
    }
    pub fn position(&self) -> (String, u32, usize) {
        match self {
            EnumExpression::Compare(_, _, v) => v.position(),
            EnumExpression::And(a, _) => a.position(),
            EnumExpression::Or(a, _) => a.position(),
            EnumExpression::Not(a) => a.position(),
            EnumExpression::Default => (String::from("unknown"), 0, 0),
        }
    }
}

impl<'a> EnumList<'a> {
    // Constructor
    pub fn new() -> EnumList<'static> {
        EnumList {
            reverse_mapping_path: HashMap::new(),
            direct_mapping_path: HashMap::new(),
            mappings: HashMap::new(),
            enums: HashMap::new(),
            global_values: HashMap::new(),
        }
    }

    pub fn exists(&self, e: Token<'a>) -> bool {
        self.enums.contains_key(&e)
    }

    // panic if the enum doesn't exist
    pub fn is_global(&self, e: Token<'a>) -> bool {
        self.enums[&e].0
    }

    // Insert a simple declared enum
    pub fn add_enum(&mut self, e: PEnum<'a>) -> Result<()> {
        let mut list = HashSet::new();
        // Check for set name duplicate
        if self.enums.contains_key(&e.name) {
            // trick to extract the original key, since they do not have the same debug info
            let position = self.enums.entry(e.name).key().position_str();
            fail!(
                self.enums.entry(e.name).key(),
                "Enum {} already defined at {}",
                e.name,
                position
            );
        }
        // check that enum is not empty
        if e.items.is_empty() {
            fail!(
                e.name,
                "Enums must have at least one item, {} is empty",
                e.name
            );
        }
        let parent_enum = self.reverse_mapping_path.get(&e.name);
        for v in &e.items {
            // Check for local uniqueness (not for mapping)
            if list.contains(v) && parent_enum.is_none() {
                fail!(
                    v,
                    "Value {} already declared in the same enum {}",
                    v,
                    e.name
                );
            }
            // check for global uniqueness
            // defined in parent is allowed, twice in the same mapping is allowed
            if let Some(e0) = self.global_values.get(v) {
                if parent_enum.is_none() || (parent_enum.unwrap() != e0 && e.name != *e0) {
                    fail!(
                        v,
                        "Value {} from enum {} already declared in the global enum {}",
                        v,
                        e.name,
                        e0
                    );
                }
            }
            // store globally uniques
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
            Some((global, values)) => {
                // transform mapping into temporary hashmap
                let mut pmapping = HashMap::new();
                for (f, t) in e.mapping {
                    // check for duplicates
                    if pmapping.contains_key(&f) {
                        fail!(f, "{} used twice in mapping {}", f, e.to);
                    }
                    // Check for invalids
                    if !(values.contains(&f) || *f == "*") {
                        fail!(
                            f,
                            "Value {} used in mapping {} does not exist in {}",
                            f,
                            e.to,
                            *e.from
                        );
                    }
                    pmapping.insert(f, t);
                }
                // store mapping into final hashmap
                let mut mapping = HashMap::new();
                let mut items: Vec<Token> = Vec::new();
                for v in values {
                    match pmapping.get(v).or_else(|| pmapping.get(&"*".into())) {
                        Some(t) => {
                            if **t == "*" {
                                mapping.insert(*v, *v);
                                items.push(*v);
                            } else {
                                mapping.insert(*v, *t);
                                items.push(*t);
                            }
                        }
                        None => fail!(
                            v,
                            "Value {} defined in {} is not used in mapping {}",
                            v,
                            *e.from,
                            e.to
                        ),
                    }
                }
                self.reverse_mapping_path.insert(e.to, e.from);
                if self.direct_mapping_path.contains_key(&e.from) {
                    self.direct_mapping_path.get_mut(&e.from).unwrap().push(e.to);
                } else {
                    self.direct_mapping_path.insert(e.from, vec![e.to]);
                }
                self.mappings.insert((e.from, e.to), mapping);
                self.add_enum(PEnum {
                    global: *global,
                    name: e.to,
                    items,
                })
            }
            None => fail!(
                e.to,
                "Enum {} missing when trying to define {}",
                e.from,
                e.to
            ),
            //None => err( format!("Enum {} missing when trying to define {}", e.from, e.to) ),
        }
    }

    // return an iterator over an enum
    // !NO CHECK the enum must exist
    pub fn enum_iter(&'a self, e: Token<'a>) -> Iter<'a, Token<'a>> {
        self.enums[&e].1.iter()
    }

    // find ascending enum path from e1 fo e2
    fn find_path(&'a self, e1: Token<'a>, e2: Token<'a>) -> Option<Vec<Token<'a>>> {
        // terminate recursion
        if e1 == e2 {
            Some(vec![e1])
        } else {
            // traverse mapping path starting from the end
            match self.reverse_mapping_path.get(&e2) {
                None => None,
                Some(e) => match self.find_path(e1, *e) {
                    None => None,
                    Some(mut path) => {
                        path.push(e2);
                        Some(path)
                    }
                },
            }
        }
    }

    // Find oldest ancestor of e1
    fn find_elder(&self, e1: Token<'a>) -> Token<'a> {
        match self.reverse_mapping_path.get(&e1) {
            None => e1,
            Some(e) => self.find_elder(*e),
        }
    }

    // find the last descendant that hash the same name
    pub fn find_descendant_enum(&self, e1: Token<'a>, item: Token<'a>) -> Token<'a> {
        match self.direct_mapping_path.get(&e1) {
            None => return e1,
            Some(e2) =>
                for to in e2 {
                    let new_item = self.mappings.get(&(e1, *to)).unwrap().get(&item).unwrap();
                    if *new_item == item {
                        return self.find_descendant_enum(*to, item);
                    }
                }
        }
        e1
    }

    pub fn canonify_expression<'b>(
        &'b self,
        upper_context: Option<&'b VarContext<'a>>,
        context: &'b VarContext<'a>,
        expr: PEnumExpression<'a>,
    ) -> Result<EnumExpression<'a>> {
        match expr {
            PEnumExpression::Default => Ok(EnumExpression::Default),
            PEnumExpression::Not(e) => Ok(EnumExpression::Not(Box::new(
                self.canonify_expression(upper_context, context, *e)?,
            ))),
            PEnumExpression::Or(e1, e2) => Ok(EnumExpression::Or(
                Box::new(self.canonify_expression(upper_context, context, *e1)?),
                Box::new(self.canonify_expression(upper_context, context, *e2)?),
            )),
            PEnumExpression::And(e1, e2) => Ok(EnumExpression::And(
                Box::new(self.canonify_expression(upper_context, context, *e1)?),
                Box::new(self.canonify_expression(upper_context, context, *e2)?),
            )),
            PEnumExpression::Compare(var, enum1, value) => {
                // get enum1 real type
                let e1 = match enum1 {
                    Some(e) => e,
                    None => match self.global_values.get(&value) {
                        // global enum ?
                        Some(e) => *e,
                        // none -> try to guess from var
                        None => match var {
                            None => fail!(value, "Global enum value {} does not exist", value),
                            Some(var1) => match context.get_variable(upper_context, var1) {
                                Some(VarKind::Enum(t, _)) => t,
                                _ => fail!(var1, "Variable {} doesn't exist or doesn't have an enum type", var1),
                            },
                        },
                    },
                };
                // get var real name
                let var1 = match var {
                    Some(v) => v,
                    None => match self.enums.get(&e1) {
                        // or get it from a global enum
                        None => fail!(e1, "No such enum {}", e1),
                        Some((false, _)) => {
                            fail!(e1, "Enum {} is not global, you must provide a variable", e1)
                        }
                        Some((true, _)) => self.find_elder(e1),
                    },
                };
                // check that enum exists and has value
                match self.enums.get(&e1) {
                    None => fail!(e1, "Enum {} does not exist", e1),
                    Some((_, list)) => {
                        if !list.contains(&value) {
                            fail!(value, "Value {} is not defined in enum {}", value, e1)
                        }
                    }
                }
                // check that var exists
                match context.get_variable(upper_context, var1) {
                    None => fail!(var1, "Variable {} does not exist", var1),
                    // wrong enum type
                    Some(VarKind::Enum(t, _)) => {
                        // check variable enum type
                        if self.find_path(t, e1).is_none() {
                            fail!(
                                var1,
                                "Variable {} is {} but expected to be {} or an ancestor",
                                var1,
                                t,
                                e1
                            );
                        }
                    }
                    // TODO warn the user if variable is known
                    // not an enum
                    _ => fail!(var1, "Variable {} is not a {} enum", var1, e1),
                }
                Ok(EnumExpression::Compare(var1, e1, value))
            }
        }
    }

    // used only by is_ancestor (path is destroyed)
    fn transform_value(&self, mut path: Vec<Token<'a>>, value: Token<'a>) -> Token<'a> {
        // <1 should not happen
        if path.len() <= 1 {
            return value;
        }
        let e0 = path.remove(0);
        let e1 = path[0];
        let v = self.mappings[&(e0, e1)][&value];
        self.transform_value(path, v)
    }
    // return true is e1::v1 is an ancestor of e2::v2
    pub fn is_ancestor(&self, e1: Token<'a>, v1: Token<'a>, e2: Token<'a>, v2: Token<'a>) -> bool {
        match self.find_path(e1, e2) {
            Some(path) => self.transform_value(path, v1) == v2,
            None => false,
        }
    }

    // evaluate a boolean expression given a set of variable=enum:value
    fn eval(
        &self,
        values: &HashMap<Token<'a>, (Token<'a>, Token<'a>)>,
        expr: &EnumExpression,
    ) -> bool {
        match expr {
            EnumExpression::Default => true,
            EnumExpression::Not(e) => !self.eval(values, &e),
            EnumExpression::Or(e1, e2) => self.eval(values, &e1) || self.eval(values, &e2),
            EnumExpression::And(e1, e2) => self.eval(values, &e1) && self.eval(values, &e2),
            EnumExpression::Compare(var, enum1, value) => {
                // unwrap panic would be a bug since all values should be defined now
                let (s, v) = values.get(&var).unwrap();
                self.is_ancestor(*s, *v, *enum1, *value)
            }
        }
    }

    // list variable,enum that are used in an expression
    // this is recursive, pass it an empty hashmap at first call
    fn list_variable_enum(
        &self,
        variables: &mut HashMap<Token<'a>, Token<'a>>,
        expr: &'a EnumExpression,
    ) {
        match expr {
            EnumExpression::Default => (),
            EnumExpression::Not(e) => self.list_variable_enum(variables, e),
            EnumExpression::Or(e1, e2) => {
                self.list_variable_enum(variables, e1);
                self.list_variable_enum(variables, e2);
            }
            EnumExpression::And(e1, e2) => {
                self.list_variable_enum(variables, e1);
                self.list_variable_enum(variables, e2);
            }
            EnumExpression::Compare(var, enum1, _) => {
                match variables.get(var) {
                    None => {
                        variables.insert(*var, *enum1);
                    }
                    Some(e) => {
                        if e != enum1 {
                            match self.find_path(*enum1, *e) {
                                Some(_) => {
                                    variables.insert(*var, *enum1);
                                }
                                // Not sure this is necessary
                                None => {
                                    if self.find_path(*e, *enum1).is_none() {
                                        panic!(format!(
                                            "Variable {} is both {} and {} which is not possible",
                                            var, e, enum1
                                        ));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fn describe(&self, values: &HashMap<Token<'a>, (Token<'a>, Token<'a>)>) -> String {
        values
            .iter()
            .map(|(key, (e, val))| {
                format!("{}=~{}:{}", key.fragment(), e.fragment(), val.fragment())
            })
            .collect::<Vec<String>>()
            .join(" && ")
    }

    pub fn evaluate(
        &self,
        upper_context: Option<&'a VarContext>,
        context: &'a VarContext<'a>,
        cases: &Vec<(EnumExpression<'a>, Vec<Statement<'a>>)>,
        case_name: Token<'a>,
    ) -> Result<()> {
        let mut variables = HashMap::new();
        cases
            .iter()
            .for_each(|(e, _)| self.list_variable_enum(&mut variables, &e));
        let it = ContextIterator::new(self, upper_context, context, variables);
        fix_results(it.map(|values| {
            let mut matched_exp = cases.iter().filter(|(e,_)| self.eval(&values, e));
            match matched_exp.next() {
                // Missing case
                None => fail!(case_name, "Missing case in {}, '{}' is never processed", case_name, self.describe(&values)),
                Some((e1,_)) => match matched_exp.next() {
                    // Single matching case
                    None => Ok(()),
                    // Overlapping cases
                    Some((e2,_)) => fail!(case_name,"Duplicate case at {} and {}, '{}' is processed twice, result may be unexpected",e1.position_str(),e2.position_str(),self.describe(&values)),
                },
            }
        }))
    }
}

enum AltVal<'a> {
    Constant(Token<'a>),
    Iterator(Iter<'a, Token<'a>>),
}
struct ContextIterator<'a> {
    // enum reference
    enum_list: &'a EnumList<'a>,
    // variables to vary
    var_list: Vec<Token<'a>>,
    //                 name        value or iterator
    iterators: HashMap<Token<'a>, AltVal<'a>>,
    // current iteration         enum       value
    current: HashMap<Token<'a>, (Token<'a>, Token<'a>)>,
    // true before first iteration
    first: bool,
}

impl<'a> ContextIterator<'a> {
    fn new(
        enum_list: &'a EnumList<'a>,
        upper_context: Option<&'a VarContext>,
        context: &'a VarContext<'a>,
        variable_list: HashMap<Token<'a>, Token<'a>>,
    ) -> ContextIterator<'a> {
        let var_list: Vec<Token<'a>> = variable_list.keys().cloned().collect();
        let mut iterators = HashMap::new();
        let mut current = HashMap::new();
        for v in &var_list {
            match context.get_variable(upper_context, *v) {
                // known value
                Some(VarKind::Enum(e, Some(val))) => {
                    current.insert(*v, (e, val));
                    iterators.insert(*v, AltVal::Constant(val));
                }
                // iterable value
                Some(VarKind::Enum(e, None)) => {
                    let mut it = enum_list.enum_iter(e);
                    current.insert(*v, (e, *(it.next().unwrap()))); // enums must always have at least one value
                    iterators.insert(*v, AltVal::Iterator(it));
                }
                // impossible values
                None => panic!("BUG This missing var should have already been detected"),
                Some(_) => panic!("BUG This mistyped var should have already been detected"),
            };
        }
        ContextIterator {
            enum_list,
            var_list,
            iterators,
            current,
            first: true,
        }
    }
}

impl<'a> Iterator for ContextIterator<'a> {
    type Item = HashMap<Token<'a>, (Token<'a>, Token<'a>)>;
    fn next(&mut self) -> Option<Self::Item> {
        if self.first {
            self.first = false;
            // We cannot return a reference into self, so we must clone
            // probably not the best solution but at least it's correct
            return Some(self.current.clone());
        }
        for v in &self.var_list {
            let reset = match self.iterators.get_mut(v) {
                Some(AltVal::Constant(_)) => false, // skip this var
                Some(AltVal::Iterator(i)) => match i.next() {
                    None => true, // restart this iterator with current enum
                    Some(val) => {
                        // get next value in iterator and return
                        self.current.entry(*v).and_modify(|e| e.1 = *val);
                        return Some(self.current.clone());
                    }
                },
                _ => panic!("BUG some value disappeared"),
            };
            if reset {
                if let Some((e, _)) = self.current.get(v) {
                    // restart iterator and update current
                    let mut it = self.enum_list.enum_iter(*e);
                    self.current.insert(*v, (*e, *(it.next().unwrap())));
                    self.iterators.insert(*v, AltVal::Iterator(it));
                } // no else, it has been checked many times
            }
        }
        // no change or all iterator restarted -> the end
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::parser::*;
    use maplit::hashmap;

    // test utilities
    fn add_penum<'a>(e: &mut EnumList<'a>, string: &'a str) -> Result<()> {
        e.add_enum(penum(pinput("", string)).unwrap().1)
    }
    fn add_enum_mapping<'a>(e: &mut EnumList<'a>, string: &'a str) -> Result<()> {
        e.add_mapping(penum_mapping(pinput("", string)).unwrap().1)
    }
    fn parse_enum_expression(string: &str) -> PEnumExpression {
        penum_expression(pinput("", string)).unwrap().1
    }
    fn ident(string: &str) -> Token {
        pidentifier(pinput("", string)).unwrap().1
    }

    #[test]
    fn test_insert() {
        let ref mut e = EnumList::new();
        assert!(add_penum(e, "enum abc { a, a, c }").is_err());
        assert!(add_penum(e, "global enum abc { a, b, c }").is_ok());
        assert!(add_penum(e, "enum abc { a, b, c }").is_err());
        assert!(add_penum(e, "enum abc2 { a, b, c }").is_err());
        assert!(add_enum_mapping(e, "enum abc ~> def { a -> b, b -> b }").is_err());
        assert!(add_enum_mapping(e, "enum abx ~> def { a -> b, b -> b, c->c }").is_err());
        assert!(add_enum_mapping(e, "enum abc ~> abc { a -> b, b -> b, c->c }").is_err());
        assert!(add_enum_mapping(e, "enum abc ~> def { a -> b, b -> b, x->c }").is_err());
        assert!(add_enum_mapping(e, "enum abc ~> def { a -> b, b -> b, *->* }").is_ok());
    }

    fn init_tests() -> (EnumList<'static>, VarContext<'static>) {
        let mut e = EnumList::new();
        add_penum(
            &mut e,
            "global enum os { debian, ubuntu, redhat, centos, aix }",
        )
        .unwrap();
        add_enum_mapping(
            &mut e,
            "enum os ~> family { ubuntu->debian, centos->redhat, *->* }",
        )
        .unwrap();
        add_enum_mapping(
            &mut e,
            "enum family ~> type { debian->linux, redhat->linux, aix->unix }",
        )
        .unwrap();
        add_penum(&mut e, "enum outcome { kept, repaired, error }").unwrap();
        add_enum_mapping(
            &mut e,
            "enum outcome ~> okerr { kept->ok, repaired->ok, error->error }",
        )
        .unwrap();
        let mut gc = VarContext::new();
        gc.new_enum_variable(None, ident("os"), ident("os"), None)
            .unwrap();
        gc.new_enum_variable(None, ident("out"), ident("outcome"), None)
            .unwrap();
        gc.new_enum_variable(None, ident("in"), ident("outcome"), Some(ident("kept")))
            .unwrap();
        (e, gc)
    }

    #[test]
    fn test_path() {
        let (e, _) = init_tests();
        assert_eq!(e.find_path("os".into(), "outcome".into()), None);
        assert_eq!(
            e.find_path("os".into(), "os".into()),
            Some(vec!["os".into()])
        );
        assert_eq!(
            e.find_path("os".into(), "type".into()),
            Some(vec!["os".into(), "family".into(), "type".into()])
        );
        assert_eq!(e.find_path("type".into(), "os".into()), None);
    }

    #[test]
    fn test_ancestor() {
        let (e, _) = init_tests();
        assert_eq!(
            e.is_ancestor("os".into(), "ubuntu".into(), "os".into(), "ubuntu".into()),
            true
        );
        assert_eq!(
            e.is_ancestor("os".into(), "ubuntu".into(), "os".into(), "debian".into()),
            false
        );
        assert_eq!(
            e.is_ancestor(
                "os".into(),
                "ubuntu".into(),
                "family".into(),
                "debian".into()
            ),
            true
        );
        assert_eq!(
            e.is_ancestor("os".into(), "ubuntu".into(), "type".into(), "linux".into()),
            true
        );
        assert_eq!(
            e.is_ancestor("os".into(), "ubuntu".into(), "type".into(), "unix".into()),
            false
        );
        assert_eq!(
            e.is_ancestor(
                "os".into(),
                "ubuntu".into(),
                "outcome".into(),
                "kept".into()
            ),
            false
        );
        assert_eq!(
            e.is_ancestor(
                "os".into(),
                "ubuntu".into(),
                "outcome".into(),
                "debian".into()
            ),
            false
        );
    }

    #[test]
    fn test_elder() {
        let (e, _) = init_tests();
        assert_eq!(e.find_elder("os".into()), "os".into());
        assert_eq!(e.find_elder("type".into()), "os".into());
        assert_eq!(e.find_elder("outcome".into()), "outcome".into());
    }

    #[test]
    fn test_descendant() {
        let (e, _) = init_tests();
        assert_eq!(e.find_descendant_enum("os".into(), "debian".into()), "family".into());
        assert_eq!(e.find_descendant_enum("os".into(), "ubuntu".into()), "os".into());
        assert_eq!(e.find_descendant_enum("outcome".into(), "kept".into()), "outcome".into());
    }


    #[test]
    fn test_canonify() {
        let (e, c) = init_tests();
        assert!(e
            .canonify_expression(None, &c, parse_enum_expression("ubuntu"))
            .is_ok());
        assert!(e
            .canonify_expression(None, &c, parse_enum_expression("os:ubuntu"))
            .is_ok());
        assert!(e
            .canonify_expression(None, &c, parse_enum_expression("os=~ubuntu"))
            .is_ok());
        assert!(e
            .canonify_expression(None, &c, parse_enum_expression("os!~ubuntu"))
            .is_ok());
        assert!(e
            .canonify_expression(None, &c, parse_enum_expression("os=~os:ubuntu"))
            .is_ok());
        assert!(e
            .canonify_expression(None, &c, parse_enum_expression("os=~linux"))
            .is_ok());
        assert!(e
            .canonify_expression(None, &c, parse_enum_expression("out=~linux"))
            .is_err());
        assert!(e
            .canonify_expression(None, &c, parse_enum_expression("os=~outcome:kept"))
            .is_err());
        assert!(e
            .canonify_expression(None, &c, parse_enum_expression("os=~type:linux"))
            .is_ok());
        assert!(e
            .canonify_expression(None, &c, parse_enum_expression("os=~type:debian"))
            .is_err());
        assert!(e
            .canonify_expression(None, &c, parse_enum_expression("os=~typo:debian"))
            .is_err());
        assert!(e
            .canonify_expression(None, &c, parse_enum_expression("typo:debian"))
            .is_err());
        assert!(e
            .canonify_expression(None, &c, parse_enum_expression("outcome:kept"))
            .is_err());
        assert!(e
            .canonify_expression(None, &c, parse_enum_expression("kept"))
            .is_err());
    }

    #[test]
    fn test_eval() {
        let (e, c) = init_tests();
        assert!(e.eval(
            &hashmap! { Token::from("os") => (Token::from("os"),Token::from("ubuntu")) },
            &e.canonify_expression(None, &c, parse_enum_expression("ubuntu"))
                .unwrap()
        ));
        assert!(e.eval(
            &hashmap! { Token::from("os") => (Token::from("os"),Token::from("ubuntu")) },
            &e.canonify_expression(None, &c, parse_enum_expression("debian"))
                .unwrap()
        ));
        assert!(!e.eval(
            &hashmap! { Token::from("os") => (Token::from("os"),Token::from("ubuntu")) },
            &e.canonify_expression(None, &c, parse_enum_expression("os:debian"))
                .unwrap()
        ));
        assert!(e.eval(
            &hashmap! { Token::from("os") => (Token::from("os"),Token::from("ubuntu")) },
            &e.canonify_expression(None, &c, parse_enum_expression("!os:debian"))
                .unwrap()
        ));
        assert!(!e.eval(&hashmap!{ Token::from("os") => (Token::from("os"),Token::from("ubuntu")), Token::from("out") => (Token::from("outcome"),Token::from("kept")) }, 
                       &e.canonify_expression(None, &c, parse_enum_expression("os:debian && out =~ outcome:kept")).unwrap()));
        assert!(e.eval(&hashmap!{ Token::from("os") => (Token::from("os"),Token::from("ubuntu")), Token::from("out") => (Token::from("outcome"),Token::from("kept")) }, 
                       &e.canonify_expression(None, &c, parse_enum_expression("os:debian || out =~ outcome:kept")).unwrap()));
    }

    #[test]
    fn test_varlist() {
        let (e, c) = init_tests();
        {
            let mut var1 = HashMap::new();
            let ex = parse_enum_expression("os:debian");
            let exp = e.canonify_expression(None, &c, ex).unwrap();
            e.list_variable_enum(&mut var1, &exp);
            assert_eq!(var1, hashmap! { Token::from("os") => Token::from("os") });
        }
        {
            let mut var1 = HashMap::new();
            let ex = parse_enum_expression("os:debian && out =~ outcome:kept");
            let exp = e.canonify_expression(None, &c, ex).unwrap();
            e.list_variable_enum(&mut var1, &exp);
            assert_eq!(
                var1,
                hashmap! { Token::from("os") => Token::from("os"), Token::from("out") => Token::from("outcome") }
            );
        }
        {
            let mut var1 = HashMap::new();
            let ex = parse_enum_expression("family:debian && os:ubuntu");
            let exp = e.canonify_expression(None, &c, ex).unwrap();
            e.list_variable_enum(&mut var1, &exp);
            assert_eq!(var1, hashmap! { Token::from("os") => Token::from("os") });
        }
        {
            let mut var1 = HashMap::new();
            let ex1 = parse_enum_expression("family:debian && os:ubuntu");
            let exp1 = e.canonify_expression(None, &c, ex1).unwrap();
            e.list_variable_enum(&mut var1, &exp1);
            let ex2 = parse_enum_expression("os:debian && out =~ outcome:kept");
            let exp2 = e.canonify_expression(None, &c, ex2).unwrap();
            e.list_variable_enum(&mut var1, &exp2);
            assert_eq!(
                var1,
                hashmap! { Token::from("os") => Token::from("os"), Token::from("out") => Token::from("outcome") }
            );
        }
    }

    #[test]
    fn test_iterator() {
        let (e, c) = init_tests();
        let mut varlist = HashMap::new();
        let ex = parse_enum_expression("os:debian && out =~ outcome:kept");
        let exp = e.canonify_expression(None, &c, ex).unwrap();
        e.list_variable_enum(&mut varlist, &exp);
        let it = ContextIterator::new(&e, None, &c, varlist);
        assert_eq!(it.count(), 15);
    }

    #[test]
    fn test_evaluate() {
        let (e, c) = init_tests();
        let case = Token::from("case");
        let mut exprs = Vec::new();

        let ex = parse_enum_expression("family:debian || family:redhat");
        exprs.push((e.canonify_expression(None, &c, ex).unwrap(), Vec::new()));
        let result = e.evaluate(None, &c, &exprs, case);
        assert!(result.is_err());
        if let Error::List(errs) = result.unwrap_err() {
            assert_eq!(errs.len(), 1);
        }

        let ex = parse_enum_expression("os:aix");
        exprs.push((e.canonify_expression(None, &c, ex).unwrap(), Vec::new()));
        assert_eq!(e.evaluate(None, &c, &exprs, case), Ok(()));

        let ex = parse_enum_expression(" family:redhat");
        exprs.push((e.canonify_expression(None, &c, ex).unwrap(), Vec::new()));
        let result = e.evaluate(None, &c, &exprs, case);
        assert!(result.is_err());
        if let Error::List(errs) = result.unwrap_err() {
            assert_eq!(errs.len(), 2);
        }
    }
}
