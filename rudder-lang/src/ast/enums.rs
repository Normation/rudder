// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use super::context::VarKind;
use super::resource::Statement;
use crate::error::*;
use crate::parser::{PEnum, PEnumExpression, PSubEnum, Token};
use std::collections::{HashMap, HashSet};

// TODO Should a non global enum item be permitted as a variable name and must it be unique ?
/// An enum tree is a structure containing the whole definition of an enum
#[derive(Debug)]
pub struct EnumTree<'src> {
    // a global tree has an automatic variable of its name and its items cannot be used as a variable name
    global: bool,
    // Tree name = root element
    name: Token<'src>,
    // parent -> ordered children
    children: HashMap<Token<'src>, Vec<Token<'src>>>,
    // child -> parent
    parents: HashMap<Token<'src>, Token<'src>>,
    // List of parents that have an incomplete children list
    incompletes: HashSet<Token<'src>>,
}
impl<'src> EnumTree<'src> {
    /// create a new enumtree with a single level of children
    pub fn new(
        name: Token<'src>,
        elders: Vec<Token<'src>>,
        incomplete: bool,
        global: bool,
    ) -> EnumTree<'src> {
        let mut myself = EnumTree {
            global,
            name,
            children: HashMap::new(),
            parents: HashMap::new(),
            incompletes: HashSet::new(),
        };
        if incomplete {
            myself.incompletes.insert(name);
        }
        for i in elders.iter() {
            myself.parents.insert(*i, name);
        }
        myself.children.insert(name, elders);
        myself
    }

    /// add a new level to an existing tree
    pub fn extend(&mut self, father: Token<'src>, children: Vec<Token<'src>>, incomplete: bool) {
        if !self.parents.contains_key(&father) {
            panic!("Subtree's parent must exist !")
        }
        if incomplete {
            self.incompletes.insert(father);
        }
        for i in children.iter() {
            self.parents.insert(*i, father);
        }
        self.children.insert(father, children);
    }

    /// iterate over ascendance (root, ... great father, father )
    fn get_ascendance(&self, item: Token<'src>) -> Vec<Token<'src>> {
        if self.parents.contains_key(&item) {
            let mut ascendance = self.get_ascendance(self.parents[&item]);
            ascendance.push(self.parents[&item]);
            ascendance
        } else {
            vec![]
        }
    }

    /*    // iterate over a range (A..D -> A,B,C,D),
    // a range can be unterminated or unstarted but not both
    fn get_range(&self, first: Option<Token<'src>>, last: Option<Token<'src>>) -> &[Token<'src>] {
        match first {
            None => match last {
                None => panic!("BUG"),
                Some(last) => {
                    let siblings = &self.children[&self.parents[&last]];
                    let pos = siblings.iter().position(|i| *i==last).unwrap();
                    &siblings[..pos+1]
                },
            }
            Some(first) => match last {
                None => {
                    let siblings = &self.children[&self.parents[&first]];
                    let pos = siblings.iter().position(|i| *i==first).unwrap();
                    &siblings[pos..]
                },
                Some(last) => {
                    let siblings = &self.children[&self.parents[&first]];
                    let pos1 = siblings.iter().position(|i| *i==first).unwrap();
                    let pos2 = siblings.iter().position(|i| *i==last).unwrap();
                    &siblings[pos1..pos2+1]
                }
            }
        }
    }*/

    /// Given a list of nodes, find the subtree that includes all those nodes and their siblings
    /// This subtree is the minimal enum tree use by an expression
    /// Terminators returns the leaves of this subtree
    fn terminators(&self, items: HashSet<Token<'src>>) -> HashSet<Token<'src>> {
        let mut terminators = HashSet::new();
        // insert root elements of tree
        terminators.insert(self.name);
        // invariant: terminators is the leaves list of a subtree of 'tree'
        for item in items.iter() {
            // from top to bottom ascendant, if ascendant in list, remove it and add its children
            for ascendant in self.get_ascendance(*item).iter() {
                if terminators.contains(ascendant) {
                    terminators.remove(ascendant);
                    for i in self.children[ascendant].iter() {
                        terminators.insert(*i);
                    }
                }
            }
        }
        terminators
    }
}

/// EnumList Is a singleton containing all enum definitions
/// It also has a direct pointer from an item name to its enum type
#[derive(Debug)]
pub struct EnumList<'src> {
    // element -> treename
    elements: HashMap<Token<'src>, Token<'src>>,
    // treename -> (tree, global)
    enums: HashMap<Token<'src>, EnumTree<'src>>,
}

impl<'src> EnumList<'src> {
    /// Create an empty list
    pub fn new() -> EnumList<'static> {
        EnumList {
            elements: HashMap::new(),
            enums: HashMap::new(),
        }
    }

    /// Returns true if the given enum is global and None if the enum doesn't exist
    pub fn enum_is_global(&self, e: Token<'src>) -> Option<bool> {
        self.enums.get(&e).map(|e| e.global)
    }

    /// Returns the item if it is global and None otherwise
    pub fn global_item(&self, e: Token<'src>) -> Option<Token<'src>> {
        if let Some((item, treename)) = self.elements.get_key_value(&e) {
            if self.enums[treename].global {
                Some(*item)
            } else {
                None
            }
        } else {
            None
        }
    }

    /// Iterate over enum names
    pub fn enum_iter(&self) -> impl Iterator<Item = &Token<'src>> {
        self.enums.iter().map(|(k, _)| k)
    }

    /// Iterate over enum values
    pub fn item_iter(&self) -> impl Iterator<Item = &Token<'src>> {
        self.elements.iter().map(|(k, _)| k)
    }

    // TODO Update when parser will be modified
    /// Add an enum definition from the parser
    pub fn add_enum(&mut self, mut e: PEnum<'src>) -> Result<()> {
        // check for tree name duplicate
        if self.enums.contains_key(&e.name) {
            fail!(
                e.name,
                "Enum {} duplicates {}",
                e.name,
                self.enums.entry(e.name).key()
            );
        }
        // detect incompleteness
        let incomplete = e.items.contains(&Token::from("*"));
        if incomplete {
            e.items.retain(|x| *x != Token::from("*"));
        }
        // check that enum is not empty
        if e.items.is_empty() {
            fail!(e.name, "Enum {} is empty", e.name);
        }
        // check for key name duplicate and insert reference
        for i in e.items.iter() {
            if self.elements.contains_key(i) {
                fail!(
                    i,
                    "Enum Item {}:{} is already defined by {}",
                    e.name,
                    i,
                    self.elements.entry(*i).key()
                );
            }
            self.elements.insert(*i, e.name);
        }
        let tree = EnumTree::new(e.name, e.items, incomplete, e.global);
        self.enums.insert(e.name, tree);
        Ok(())
    }

    // TODO Update when parser will be modified
    /// Extend an existing enum with "enum in" from the parser
    /// return the structue back if the parent doesn't exist (yet)
    pub fn extend_enum(&mut self, mut e: PSubEnum<'src>) -> Result<Option<PSubEnum<'src>>> {
        // find tree name
        let treename = match self.elements.get(&e.name) {
            None => return Ok(Some(e)),
            Some(t) => *t,
        };
        // detect incompleteness
        let incomplete = e.items.contains(&Token::from("*"));
        if incomplete {
            e.items.retain(|x| *x != Token::from("*"));
        }
        // find tree
        if let Some(tree) = self.enums.get_mut(&treename) {
            // check that enum is not empty
            if e.items.is_empty() {
                fail!(e.name, "Enum {} is empty", e.name);
            }
            // check that we do not extend twice the same entry
            if tree.children.contains_key(&e.name) {
                fail!(e.name, "Sub enum {} is already defined", e.name);
            }
            // check for key name duplicate and insert reference
            for i in e.items.iter() {
                if self.elements.contains_key(i) {
                    fail!(
                        i,
                        "Enum Item {}:{} is already defined by {}",
                        e.name,
                        i,
                        self.elements.entry(*i).key()
                    );
                }
                self.elements.insert(*i, treename);
            }
            // insert keys
            tree.extend(e.name, e.items, incomplete);
        }
        Ok(None)
    }

    /// Transforms a parsed enum expression into its final form using all enum definitions
    /// It needs a variable context (local and global) to check for proper variable existence
    // TODO rewrite with parser rewrite
    pub fn canonify_expression<VG>(
        &self,
        getter: &VG,
        expr: PEnumExpression<'src>,
    ) -> Result<EnumExpression<'src>>
    where
        VG: Fn(Token<'src>) -> Option<VarKind<'src>>,
    {
        match expr {
            PEnumExpression::Default(t) => Ok(EnumExpression::Default(t)),
            PEnumExpression::Not(e) => Ok(EnumExpression::Not(Box::new(
                self.canonify_expression(getter, *e)?,
            ))),
            PEnumExpression::Or(e1, e2) => {
                let r1 = self.canonify_expression(getter, *e1);
                let r2 = self.canonify_expression(getter, *e2);
                match (r1, r2) {
                    (Err(er), Ok(_)) => Err(er),
                    (Ok(_), Err(er)) => Err(er),
                    (Err(er1), Err(er2)) => Err(er1.append(er2)),
                    (Ok(ex1), Ok(ex2)) => Ok(EnumExpression::Or(Box::new(ex1), Box::new(ex2))),
                }
            }
            PEnumExpression::And(e1, e2) => {
                let r1 = self.canonify_expression(getter, *e1);
                let r2 = self.canonify_expression(getter, *e2);
                match (r1, r2) {
                    (Err(er), Ok(_)) => Err(er),
                    (Ok(_), Err(er)) => Err(er),
                    (Err(er1), Err(er2)) => Err(er1.append(er2)),
                    (Ok(ex1), Ok(ex2)) => Ok(EnumExpression::And(Box::new(ex1), Box::new(ex2))),
                }
            }
            PEnumExpression::Compare(var, enum1, value) => {
                // get enum1 real type
                let treename = match enum1 {
                    Some(e) => e,
                    None => match self.elements.get(&value) {
                        Some(t) => *t,
                        None => fail!(value, "Enum value unknown {}", value), // TODO do not ignore the fact that it can be a variable name representing a boolean
                                                                              // TODO and some other error cases (see original version)
                    },
                };
                // get var real name
                let varname = match var {
                    Some(v) => v,
                    None => treename, // TODO forbidden if tree is not global
                                      // TODO  and some other error cases (see original version)
                };
                // TODO check that var exists and has the right type
                Ok(EnumExpression::Compare(varname, treename, value))
            }
        }
    }

    /// Evaluate a boolean expression (final version) given a set of variable=value
    fn eval_case(
        &self,
        values: &HashMap<Token<'src>, Token<'src>>,
        expr: &EnumExpression<'src>,
    ) -> bool {
        match expr {
            EnumExpression::Default(_) => true,
            EnumExpression::Not(e) => !self.eval_case(values, &e),
            EnumExpression::Or(e1, e2) => {
                self.eval_case(values, &e1) || self.eval_case(values, &e2)
            }
            EnumExpression::And(e1, e2) => {
                self.eval_case(values, &e1) && self.eval_case(values, &e2)
            }
            EnumExpression::Compare(var, _, value) => values[&var] == *value,
            // TODO implement range
            EnumExpression::Range(var, _, None, None) => true,
            EnumExpression::Range(var, _, Some(a), None) => unimplemented!(),
            EnumExpression::Range(var, _, None, Some(b)) => unimplemented!(),
            EnumExpression::Range(var, _, Some(a), Some(b)) => unimplemented!(),
        }
    }

    /// Evaluates a set of expressions possible outcome to check for missing cases and redundancy
    /// Variable context is here to guess variable type and to properly evaluate variables that are constant
    pub fn evaluate<VG>(
        &self,
        getter: &VG,
        cases: &[(EnumExpression<'src>, Vec<Statement<'src>>)],
        case_name: Token<'src>,
    ) -> Vec<Error> {
        // list variables
        let mut variables = HashMap::new();
        for case in cases.iter() {
            case.0.list_variables_tree(&mut variables);
        }
        // list terminators (highest nodes for each variables that can be used for iteration)
        let var_items: Vec<(Token<'src>, HashSet<Token<'src>>)> = variables
            .into_iter()
            .map(|((variable, treename), items)| {
                let tree = &self.enums[&treename];
                (variable, tree.terminators(items))
            })
            .collect();
        // create a cross product iterator var1_terminators x var2_terminators x ...
        let mut errors = Vec::new();
        for context in CrossIterator::new(&var_items) {
            // evaluate each case for context and count matching cases
            let count = cases.iter().fold(0, |c, (exp, _)| {
                if self.eval_case(&context, exp) {
                    c + 1
                } else {
                    c
                }
            });
            // Now check for errors
            if count == 0 {
                errors.push(err!(case_name, "case {} is ignored", format_case(&context)));
            }
            if count > 1 {
                errors.push(err!(
                    case_name,
                    "case {} is handles at least twoce",
                    format_case(&context)
                ));
            }
            // TODO also check for default and open ranges
        }
        // Future improvement aggregate errors for better error message
        errors
    }
}

// just format a list of var=value
fn format_case(context: &HashMap<Token, Token>) -> String {
    context
        .iter()
        .map(|(k, v)| format!("{}=~{}", k, v))
        .collect::<Vec<String>>()
        .join(" & ")
}

// we need an item reference because ve we a pointer to the item list
// we cannot store the list in the same structure as its iterator
// so let the caller store it and juste take reference
struct VariableIterator<'it, 'src> {
    variable: Token<'src>,
    current: Token<'src>,
    items: &'it HashSet<Token<'src>>,
    iterator: std::collections::hash_set::Iter<'it, Token<'src>>,
}

impl<'it, 'src> VariableIterator<'it, 'src> {
    fn new(variable: Token<'src>, items: &'it HashSet<Token<'src>>) -> VariableIterator<'it, 'src> {
        let mut iterator = items.iter();
        VariableIterator {
            variable,
            current: *iterator.next().unwrap(),
            items,
            iterator,
        }
    }
    fn next(&mut self) -> Option<Token<'src>> {
        match self.iterator.next() {
            // end of iterator, signal it then loop again
            None => {
                self.iterator = self.items.iter();
                None
            }
            Some(c) => {
                self.current = *c;
                Some(*c)
            }
        }
    }
}

/// Iterator that can iterate over all possible value set that a variable set can take
/// Ex: if var1 and var2 are variables of an enum type with 3 item
///     it will produce 9 possible value combinations
/// TODO we currently ignore variables that have a known value (constants) it may be useful
struct CrossIterator<'it, 'src> {
    // all iterators
    var_iterators: Vec<VariableIterator<'it, 'src>>,
    // true before first iteration
    init: bool,
}

impl<'it, 'src> CrossIterator<'it, 'src> {
    /// Create the iterator from a variable list and their possible values
    fn new(variables: &'it Vec<(Token<'src>, HashSet<Token<'src>>)>) -> CrossIterator<'it, 'src> {
        CrossIterator {
            var_iterators: variables
                .into_iter()
                .map(|(v, i)| VariableIterator::new(*v, i))
                .collect(),
            init: true,
        }
    }
}

impl<'it, 'src> Iterator for CrossIterator<'it, 'src> {
    //                  variable  -> value
    type Item = HashMap<Token<'src>, Token<'src>>;
    /// Iterate: we store an iterator for each variables
    /// - we increment the first iterator
    /// - if it ended reset it and increment the next iterator
    /// - continue until we reach the end of the last iterator
    fn next(&mut self) -> Option<Self::Item> {
        if self.init {
            self.init = false;
            return Some(
                self.var_iterators
                    .iter()
                    .map(|vi| (vi.variable, vi.current))
                    .collect(),
            );
        }
        for v in &mut self.var_iterators {
            if let None = v.next() {
                v.next(); // reset iterator then increment next one
            } else {
                return Some(
                    self.var_iterators
                        .iter()
                        .map(|vi| (vi.variable, vi.current))
                        .collect(),
                ); // return here to avoid incrementing next one
            }
        }
        // no change or all iterator restarted -> the end
        None
    }
}
/// A boolean expression that can be defined using enums
#[derive(Debug, PartialEq, Clone)]
pub enum EnumExpression<'src> {
    //      variable     enum tree    enum item
    Compare(Token<'src>, Token<'src>, Token<'src>),
    //      variable   enum tree    first item           last item
    Range(
        Token<'src>,
        Token<'src>,
        Option<Token<'src>>,
        Option<Token<'src>>,
    ),
    And(Box<EnumExpression<'src>>, Box<EnumExpression<'src>>),
    Or(Box<EnumExpression<'src>>, Box<EnumExpression<'src>>),
    Not(Box<EnumExpression<'src>>),
    Default(Token<'src>), // token for position handling only
}

impl<'src> EnumExpression<'src> {
    /// List all variables that are used in an expression,
    /// and put them into the 'variables' hashset
    /// this is recursive mutable, pass it an empty hashset at first call
    /// Only used by evaluate.
    fn list_variables_tree(
        &self,
        variables: &mut HashMap<(Token<'src>, Token<'src>), HashSet<Token<'src>>>, // (variable, tree) -> item list
    ) {
        match self {
            EnumExpression::Default(_) => (),
            EnumExpression::Not(e) => e.list_variables_tree(variables),
            EnumExpression::Or(e1, e2) => {
                e1.list_variables_tree(variables);
                e2.list_variables_tree(variables);
            }
            EnumExpression::And(e1, e2) => {
                e1.list_variables_tree(variables);
                e2.list_variables_tree(variables);
            }
            EnumExpression::Compare(var, tree, item) => {
                let list = variables.entry((*var, *tree)).or_insert(HashSet::new());
                list.insert(*item);
            }
            EnumExpression::Range(var, tree, item1, item2) => {
                let list = variables.entry((*var, *tree)).or_insert(HashSet::new());
                // we only need one variable for its siblings
                // a range must be withing siblings
                // -> pushing only one item is sufficient
                if let Some(item) = item1 {
                    list.insert(*item);
                } else if let Some(item) = item2 {
                    list.insert(*item);
                } // else no item at all is forbidden
            }
        }
    }

    /// return true if this is just the expression 'default'
    pub fn is_default(&self) -> bool {
        match self {
            EnumExpression::Default(_) => true,
            _ => false,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::parser::tests::*;
    use maplit::{hashmap, hashset};

    #[test]
    fn test_tree() {
        let mut tree = EnumTree::new(
            "T".into(),
            vec!["a".into(), "b".into(), "c".into()],
            true,
            true,
        );
        tree.extend("a".into(), vec!["d".into(), "e".into(), "f".into()], false);
        tree.extend("e".into(), vec!["g".into(), "h".into(), "i".into()], false);
        /*// siblings
        assert_eq!(tree.get_siblings("e".into()), vec![ "d".into(), "f".into() ]);
        assert_eq!(tree.get_siblings("d".into()), vec![ "e".into(), "f".into() ]);
        assert_eq!(tree.get_siblings("a".into()), vec![ "b".into(), "c".into() ]);
        assert_eq!(tree.get_siblings("b".into()), vec![ "a".into(), "c".into() ]);
        // elders
        //assert_eq!(tree.get_elders().map(|i| i.clone()).collect::<Vec<Token>>(), vec![ "a".into(), "b".into(), "c".into() ]);*/
        // ascendance
        assert_eq!(tree.get_ascendance("a".into()), vec!["T".into()]);
        assert_eq!(
            tree.get_ascendance("g".into()),
            vec!["T".into(), "a".into(), "e".into()]
        );
        /*// range
        //assert_eq!(tree.get_range(Some("e".into()), None), &[ "e".into(), "f".into() ]);
        //assert_eq!(tree.get_range(None, Some("e".into())), &[ "d".into(), "e".into() ]);
        //assert_eq!(tree.get_range(Some("d".into()), Some("e".into())), &[ "d".into(), "e".into() ]);
         */

        assert_eq!(
            tree.terminators(hashset! {"a".into(), "b".into()}),
            hashset! {"a".into(), "b".into(), "c".into()}
        );
        assert_eq!(
            tree.terminators(hashset! {"a".into(), "a".into(), "e".into()}),
            hashset! {"b".into(), "c".into(), "d".into(), "e".into(), "f".into()}
        );
        assert_eq!(
            tree.terminators(hashset! {"a".into(), "g".into()}),
            hashset! {"b".into(), "c".into(), "d".into(), "f".into(), "g".into(), "h".into(), "i".into()}
        );
    }

    #[test]
    fn test_enums() {
        let mut elist = EnumList::new();
        assert!(elist.add_enum(penum_t("global enum T { a, b, c }")).is_ok());
        assert!(elist.add_enum(penum_t("enum T { a, b, c }")).is_err());
        assert!(elist.add_enum(penum_t("enum U { c, d, e }")).is_err());
        assert!(elist.add_enum(penum_t("enum V { f, g, h }")).is_ok());
        assert!(elist
            .extend_enum(psub_enum_t("items in a { aa, ab, ac }"))
            .is_ok());
        assert!(elist
            .extend_enum(psub_enum_t("items in a { ba, bb, bc }"))
            .is_err());
        assert!(elist
            .extend_enum(psub_enum_t("items in b { aa, ab, ac }"))
            .is_err());
        assert!(elist
            .extend_enum(psub_enum_t("items in aa { ba, bb, bc }"))
            .is_ok());
    }

    #[test]
    fn test_canonify() {
        let mut elist = EnumList::new();
        let getter = |_| None;
        elist
            .add_enum(penum_t("global enum T { a, b, c }"))
            .unwrap();
        elist
            .extend_enum(psub_enum_t("items in a { d, e, f }"))
            .unwrap();
        elist
            .extend_enum(psub_enum_t("items in b { g, h, i }"))
            .unwrap();
        elist
            .extend_enum(psub_enum_t("items in k { k, k, l }"))
            .unwrap();
        assert_eq!(
            elist
                .canonify_expression(&getter, penum_expression_t("a"))
                .unwrap(),
            EnumExpression::Compare("T".into(), "T".into(), "a".into())
        );
        assert_eq!(
            elist
                .canonify_expression(&getter, penum_expression_t("var=~a"))
                .unwrap(),
            EnumExpression::Compare("var".into(), "T".into(), "a".into())
        );
        assert_eq!(
            elist
                .canonify_expression(&getter, penum_expression_t("var=~T:a"))
                .unwrap(),
            EnumExpression::Compare("var".into(), "T".into(), "a".into())
        );
        assert_eq!(
            elist
                .canonify_expression(&getter, penum_expression_t("var=~a:T"))
                .unwrap(),
            EnumExpression::Compare("var".into(), "a".into(), "T".into())
        ); // TODO this one should fail (or the previous)
        assert_eq!(
            elist
                .canonify_expression(&getter, penum_expression_t("var=~d"))
                .unwrap(),
            EnumExpression::Compare("var".into(), "T".into(), "d".into())
        );
        assert_eq!(
            elist
                .canonify_expression(&getter, penum_expression_t("var=~d:T"))
                .unwrap(),
            EnumExpression::Compare("var".into(), "d".into(), "T".into())
        );
        assert_eq!(
            elist
                .canonify_expression(&getter, penum_expression_t("d:T"))
                .unwrap(),
            EnumExpression::Compare("d".into(), "d".into(), "T".into())
        );
        assert!(elist
            .canonify_expression(&getter, penum_expression_t("d|a&!b"))
            .is_ok());
    }

    #[test]
    fn test_listvars() {
        let mut elist = EnumList::new();
        let getter = |_| None;
        elist
            .add_enum(penum_t("global enum T { a, b, c }"))
            .unwrap();
        elist
            .extend_enum(psub_enum_t("items in b { g, h, i }"))
            .unwrap();
        elist
            .extend_enum(psub_enum_t("items in a { d, e, f }"))
            .unwrap();
        elist
            .extend_enum(psub_enum_t("items in g { j, k, l }"))
            .unwrap();
        let mut h1 = HashMap::new();
        elist
            .canonify_expression(&getter, penum_expression_t("a"))
            .unwrap()
            .list_variables_tree(&mut h1);
        assert_eq!(
            h1,
            hashmap! { ("T".into(), "T".into()) => hashset!{ "a".into() } }
        );

        let mut h2 = HashMap::new();
        elist
            .canonify_expression(&getter, penum_expression_t("d"))
            .unwrap()
            .list_variables_tree(&mut h2);
        assert_eq!(
            h2,
            hashmap! { ("T".into(), "T".into()) => hashset!{ "d".into() } }
        );

        let mut h3 = HashMap::new();
        elist
            .canonify_expression(&getter, penum_expression_t("var=~T:a"))
            .unwrap()
            .list_variables_tree(&mut h3);
        assert_eq!(
            h3,
            hashmap! { ("var".into(), "T".into()) => hashset!{ "a".into() } }
        );

        let mut h4 = HashMap::new();
        elist
            .canonify_expression(&getter, penum_expression_t("d|h"))
            .unwrap()
            .list_variables_tree(&mut h4);
        assert_eq!(
            h4,
            hashmap! { ("T".into(), "T".into()) => hashset!{ "d".into(), "e".into(), "f".into(), "g".into(), "h".into(), "i".into(), "c".into() } }
        );
    }

    #[test]
    fn test_iterator() {
        let a = hashset! {"a1".into(), "a2".into()};
        let b = hashset! {"b1".into()};
        let c = hashset! {"c1".into(), "c2".into(), "c3".into()};
        let varlist = vec![("a".into(), a), ("b".into(), b), ("c".into(), c)];
        let it = CrossIterator::new(&varlist);
        // hadr to test since hash(set/map) iterator don't have a fixed order
        let result = it.collect::<Vec<HashMap<Token, Token>>>();
        assert_eq!(result.len(), 6);
        assert!(result.contains(&hashmap!{ Token::from("a") => Token::from("a1"), Token::from("b") => Token::from("b1"), Token::from("c") => Token::from("c1")}));
        assert!(result.contains(&hashmap!{ Token::from("a") => Token::from("a1"), Token::from("b") => Token::from("b1"), Token::from("c") => Token::from("c2")}));
        assert!(result.contains(&hashmap!{ Token::from("a") => Token::from("a1"), Token::from("b") => Token::from("b1"), Token::from("c") => Token::from("c3")}));
        assert!(result.contains(&hashmap!{ Token::from("a") => Token::from("a2"), Token::from("b") => Token::from("b1"), Token::from("c") => Token::from("c1")}));
        assert!(result.contains(&hashmap!{ Token::from("a") => Token::from("a2"), Token::from("b") => Token::from("b1"), Token::from("c") => Token::from("c2")}));
        assert!(result.contains(&hashmap!{ Token::from("a") => Token::from("a2"), Token::from("b") => Token::from("b1"), Token::from("c") => Token::from("c3")}));
    }

    #[test]
    fn test_evaluation() {
        let mut elist = EnumList::new();
        let getter = |_| None;
        elist
            .add_enum(penum_t("global enum T { a, b, c }"))
            .unwrap();
        elist
            .extend_enum(psub_enum_t("items in a { d, e, f }"))
            .unwrap();
        elist
            .extend_enum(psub_enum_t("items in b { g, h, i }"))
            .unwrap();
        elist
            .extend_enum(psub_enum_t("items in g { j, k, l }"))
            .unwrap();
        elist
            .add_enum(penum_t("global enum U { A, B, C }"))
            .unwrap();
        let e1 = elist
            .canonify_expression(&getter, penum_expression_t("a"))
            .unwrap();
        let e2 = elist
            .canonify_expression(&getter, penum_expression_t("b"))
            .unwrap();
        let e3 = elist
            .canonify_expression(&getter, penum_expression_t("c"))
            .unwrap();
        let cases1 = [
            (e1.clone(), Vec::new()),
            (e2.clone(), Vec::new()),
            (e3.clone(), Vec::new()),
        ];
        assert_eq!(
            elist
                .evaluate(&getter, &cases1[..], Token::from("test1"))
                .len(),
            0
        );
        let cases2 = [(e2.clone(), Vec::new()), (e3.clone(), Vec::new())];
        assert_eq!(
            elist
                .evaluate(&getter, &cases2[..], Token::from("test2"))
                .len(),
            1
        );
        let cases3 = [
            (e1.clone(), Vec::new()),
            (e2.clone(), Vec::new()),
            (e3.clone(), Vec::new()),
            (e3.clone(), Vec::new()),
        ];
        assert_eq!(
            elist
                .evaluate(&getter, &cases3[..], Token::from("test3"))
                .len(),
            1
        );
    }
}
