// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use super::context::VarKind;
use super::resource::Statement;
use crate::error::*;
use crate::parser::{PEnum, PEnumExpression, PSubEnum, Token};
use std::collections::{HashMap, HashSet};
use std::fmt;


// TODO
// - vérifier que tous les check d'erreur sont fait
// - supprimer enum_old
// - clippy
// - supprimer la syntaxe T:
// - Ecrire les os dans stdlib
// - trouver les todo
// - supprimer la comparaison de type d'une variable ?

/// This item type is internal, because First and Fast cannot be constructed from an enum declaration or from and enum expression
#[derive(Debug,Hash,PartialEq,Eq,Clone)]
enum EnumItem<'src> {
    First(Token<'src>), // token is the parent item
    Item(Token<'src>),
    Last(Token<'src>), // token is the parent item
}

/// Format the full token for compiler debug info
impl<'src> fmt::Display for EnumItem<'src> {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        match self {
            EnumItem::First(p) => write!(f, "'First item of {}'", p),
            EnumItem::Item(i) => write!(f, "{}", i),
            EnumItem::Last(p) => write!(f, "'Last item of {}'", p),
        }
    }
}

/// Convert a Vec of tokns into a vec of EnumItems
fn from_item_vec<'src>(father: Token<'src>, items: Vec<Token<'src>>) -> Vec<EnumItem<'src>> {
        // detect incompleteness
        let incomplete = items.contains(&Token::from("*"));
        if incomplete {
            let mut item_list = items.into_iter().filter(|x| *x != Token::from("*")).map(|i| EnumItem::Item(i)).collect::<Vec<EnumItem>>();
            item_list.insert(0, EnumItem::First(father));
            item_list.push(EnumItem::Last(father));
            item_list
        } else {
            items.into_iter().map(|i| EnumItem::Item(i)).collect()   
        }
}

// TODO Should a non global enum item be permitted as a variable name and must it be unique ?
/// An enum tree is a structure containing the whole definition of an enum
#[derive(Debug)]
struct EnumTree<'src> {
    // a global tree has an automatic variable of its name and its items cannot be used as a variable name
    global: bool,
    // Tree name = root element
    name: Token<'src>,
    // parent -> ordered children
    children: HashMap<Token<'src>, Vec<EnumItem<'src>>>,
    // child -> parent
    parents: HashMap<EnumItem<'src>, Token<'src>>,
}
impl<'src> EnumTree<'src> {
    /// create a new enumtree with a single level of children
    fn new(
        name: Token<'src>,
        items: Vec<Token<'src>>,
        global: bool,
    ) -> EnumTree<'src> {
        let mut myself = EnumTree {
            global,
            name,
            children: HashMap::new(),
            parents: HashMap::new(),
        };
        let item_list = from_item_vec(name, items);
        for i in item_list.iter() {
            myself.parents.insert(i.clone(), name);
        }
        myself.children.insert(name, item_list);
        myself
    }

    /// add a new level to an existing tree
    fn extend(&mut self, father: Token<'src>, items: Vec<Token<'src>>) {
        let father_item = EnumItem::Item(father);
        if !self.parents.contains_key(&father_item) {
            panic!("Subtree's parent must exist !")
        }
        let item_list = from_item_vec(father, items);
        for i in item_list.iter() {
            self.parents.insert(i.clone(), father);
        }
        self.children.insert(father, item_list);
    }

    /// iterate over ascendance (root, ... great father, father )
    fn get_ascendance(&self, item: EnumItem<'src>) -> Vec<Token<'src>> {
        if self.parents.contains_key(&item) {
            let mut ascendance = self.get_ascendance(EnumItem::Item(self.parents[&item]));
            ascendance.push(self.parents[&item]);
            ascendance
        } else {
            Vec::new()
        }
    }

    /// Return true if item is in the range between first and last (inclusive) None means last sibling
    fn is_in_range(&self, item: &EnumItem<'src>, first: &Option<EnumItem<'src>>, last: &Option<EnumItem<'src>>) -> bool {
        // 3 cases : item is a sibling, item is a descendant, item is womewhere else

        // find siblings
        let item_list = if let Some(i) = first {
            &self.children[&self.parents[&i]]
        } else if let Some(i) = last {
            &self.children[&self.parents[&i]]
        } else { panic!("Empt range") }; // else None,None is imposible
        
        // if item is a descendant, find its ancestor that is a sibling
        let test_item = if item_list.contains(item) { // Item is a sibling
            item.clone()
        } else {
            match self.get_ascendance(item.clone()).iter().find(|i| item_list.contains(&EnumItem::Item(**i))) {
                Some(p) => EnumItem::Item(*p), // p is an ascendant of item that is in the list
                None => return false // item is not in this subtree
            }
        };
        // find each item position
        let item_position = item_list.iter().position(|x| x == &test_item ).unwrap(); // item is necessary in the list
        let first_position = match first {
            None => 0,
            Some(i) => item_list.iter().position(|x| x == i ).unwrap(), // first and last are necessary in the list
        };
        let last_position = match last {
            None => item_list.len()-1,
            Some(i) => item_list.iter().position(|x| x == i ).unwrap(), // first and last are necessary in the list
        };
        item_position >= first_position && item_position <= last_position
    }

    /// return true if left and right have same parent
    fn are_siblings(&self, left: Token<'src>, right: Token<'src>) -> bool {
        self.parents.get(&EnumItem::Item(left)) == self.parents.get(&EnumItem::Item(right))
    }

    /// Given a list of nodes, find the subtree that includes all those nodes and their siblings
    /// This subtree is the minimal enum tree use by an expression
    /// Terminators returns the leaves of this subtree
    fn terminators(&self, nodefault: bool, items: HashSet<Token<'src>>) -> HashSet<EnumItem<'src>> {
        let mut terminators = HashSet::new();
        // insert root elements of tree
        terminators.insert(EnumItem::Item(self.name));
        // invariant: terminators is the leaves list of a subtree of 'tree'
        for item in items.iter() {
            // from top to bottom ascendant, if ascendant in list, remove it and add its children
            for ascendant in self.get_ascendance(EnumItem::Item(*item)).iter() {
                let ascendant_item = EnumItem::Item(*ascendant);
                if terminators.contains(&ascendant_item) {
                    terminators.remove(&ascendant_item);
                    for i in self.children[ascendant].iter() {
                        if nodefault {
                            match i {
                                EnumItem::First(_)|EnumItem::Last(_) => {},
                                _ => {terminators.insert(i.clone());},
                            }
                        } else {
                            terminators.insert(i.clone());
                        }
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
    // element -> treename, this list doesn't contain First and Last items of incomplete enums
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

    /// Return true if item is in the range between first and last (inclusive) None means first/last sibling
    fn is_in_range(&self, item: &EnumItem<'src>, first: &Option<Token<'src>>, last: &Option<Token<'src>>) -> bool {
        // find tree then call is_in_range
        let name = match item {
            EnumItem::First(i) => i,
            EnumItem::Item(i) => i,
            EnumItem::Last(i) => i,
        };
        let first_item = first.map(|i| EnumItem::Item(i));
        let last_item = last.map(|i| EnumItem::Item(i));
        let tree = self.elements[&name];
        self.enums[&tree].is_in_range(item, &first_item, &last_item)
    }

    // TODO Update when parser will be modified
    /// Add an enum definition from the parser
    pub fn add_enum(&mut self, e: PEnum<'src>) -> Result<()> {
        // check for tree name duplicate
        if self.enums.contains_key(&e.name) {
            fail!(
                e.name,
                "Enum {} duplicates {}",
                e.name,
                self.enums.entry(e.name).key()
            );
        }
        // check that enum is not empty
        if e.items.is_empty() {
            fail!(e.name, "Enum {} is empty", e.name);
        }
        // check for key name duplicate and insert reference
        for i in e.items.iter() {
            // default value is not a real item
            if i.fragment() == "*" { continue }
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
        let tree = EnumTree::new(e.name, e.items, e.global);
        self.enums.insert(e.name, tree);
        Ok(())
    }

    // TODO Update when parser will be modified
    /// Extend an existing enum with "enum in" from the parser
    /// return the structue back if the parent doesn't exist (yet)
    pub fn extend_enum(&mut self, e: PSubEnum<'src>) -> Result<Option<PSubEnum<'src>>> {
        // find tree name
        let treename = match self.elements.get(&e.name) {
            None => return Ok(Some(e)),
            Some(t) => *t,
        };
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
                // default value is not a real item
                if i.fragment() == "*" { continue }
                if self.elements.contains_key(i) {
                    fail!(
                        i,
                        "Enum Item {}:{} is already defined at {}",
                        e.name,
                        i,
                        self.elements.entry(*i).key()
                    );
                }
                self.elements.insert(*i, treename);
            }
            // insert keys
            tree.extend(e.name, e.items);
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
            PEnumExpression::NoDefault(t) => Ok(EnumExpression::NoDefault(t)),
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
            },
            PEnumExpression::And(e1, e2) => {
                let r1 = self.canonify_expression(getter, *e1);
                let r2 = self.canonify_expression(getter, *e2);
                match (r1, r2) {
                    (Err(er), Ok(_)) => Err(er),
                    (Ok(_), Err(er)) => Err(er),
                    (Err(er1), Err(er2)) => Err(er1.append(er2)),
                    (Ok(ex1), Ok(ex2)) => Ok(EnumExpression::And(Box::new(ex1), Box::new(ex2))),
                }
            },
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
            },
            PEnumExpression::RangeCompare(var, enum1, left, right, position) => {
                // left and right must not be both None
                let value = if let Some(item) = left {
                    item
                } else if let Some(item) = right {
                    item
                } else {
                    fail!(position, "Empty range is forbidden")
                };
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
                // check tat left and right are right type
                if left.is_some() {
                    let item = &left.unwrap();
                    if Some(&treename) != self.elements.get(item) {
                        fail!(item, "{} is unknown", item);
                    }
                }
                if right.is_some() {
                    let item = &right.unwrap();
                    if Some(&treename) != self.elements.get(item) {
                        fail!(item, "{} is unknown", item);
                    }
                }
                // check that left and right are siblings
                match (left,right) {
                    (Some(item1),Some(item2)) => if ! self.enums[&treename].are_siblings(item1,item2) {
                        fail!(item1, "{} and {} are not siblings", item1, item2);
                    }
                    _ => {}
                }
                // TODO check that var exists and has the right type
                Ok(EnumExpression::RangeCompare(varname, treename, left, right))
            },
        }
    }

    /// Evaluate a boolean expression (final version) given a set of variable=value
    fn eval_case(
        &self,
        values: &HashMap<Token<'src>, EnumItem<'src>>,
        expr: &EnumExpression<'src>,
    ) -> bool {
        match expr {
            EnumExpression::Default(_) => true, // never happens
            EnumExpression::NoDefault(_) => true, // never happens
            EnumExpression::Not(e) => !self.eval_case(values, &e),
            EnumExpression::Or(e1, e2) => {
                self.eval_case(values, &e1) || self.eval_case(values, &e2)
            }
            EnumExpression::And(e1, e2) => {
                self.eval_case(values, &e1) && self.eval_case(values, &e2)
            }
            EnumExpression::Compare(var, _, value) => values[&var] == EnumItem::Item(*value),
            EnumExpression::RangeCompare(var, _, first, last) => self.is_in_range(&values[&var], first, last),
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
        // keep only cases that are not default (default must be the last case and cases must not be empty)
        let (match_cases, default, nodefault) = match cases.last().unwrap().0 {
            EnumExpression::Default(_) => (cases.split_last().unwrap().1, true, false),
            EnumExpression::NoDefault(_) => (cases.split_last().unwrap().1, false, true),
            _ => (cases, false, false),
        };
        // list variables
        let mut variables = HashMap::new();
        for case in cases.iter() {
            case.0.list_variables_tree(&mut variables);
        }
        // list terminators (highest nodes for each variables that can be used for iteration)
        let var_items: Vec<(Token<'src>, HashSet<EnumItem<'src>>)> = variables
            .into_iter()
            .map(|((variable, treename), items)| {
                let tree = &self.enums[&treename];
                (variable, tree.terminators(nodefault, items))
            })
            .collect();
        // create a cross product iterator var1_terminators x var2_terminators x ...
        let mut errors = Vec::new();
        for context in CrossIterator::new(&var_items) {
            // evaluate each case for context and count matching cases
            let count = match_cases.iter().fold(0, |c, (exp, _)| {
                if self.eval_case(&context, exp) {
                    c + 1
                } else {
                    c
                }
            });
            // Now check for errors
            if count == 0 && !default {
                errors.push(err!(case_name, "case {} is ignored", format_case(&context)));
            }
            if count > 1 {
                errors.push(err!(
                    case_name,
                    "case {} is handles at least twice",
                    format_case(&context)
                ));
            }
            // TODO also check for default and open ranges
        }
        // Future improvement aggregate errors for better error message
        errors
    }
}

/// Just format a list of var=value
fn format_case(context: &HashMap<Token, EnumItem>) -> String {
    context
        .iter()
        .map(|(k, v)| format!("{}=~{}", k, v))
        .collect::<Vec<String>>()
        .join(" & ")
}

/// This is the basis of the cross iterator, it iterates over a single list
/// of items in an enum
// we need an item reference because ve we a pointer to the item list
// we cannot store the list in the same structure as its iterator
// so let the caller store it and juste take reference
struct VariableIterator<'it, 'src> {
    variable: Token<'src>,
    current: EnumItem<'src>,
    items: &'it HashSet<EnumItem<'src>>,
    iterator: std::collections::hash_set::Iter<'it, EnumItem<'src>>,
}

impl<'it, 'src> VariableIterator<'it, 'src> {
    fn new(variable: Token<'src>, items: &'it HashSet<EnumItem<'src>>) -> VariableIterator<'it, 'src> {
        let mut iterator = items.iter();
        VariableIterator {
            variable,
            current: iterator.next().unwrap().clone(),
            items,
            iterator,
        }
    }
    fn next(&mut self) -> Option<EnumItem<'src>> {
        match self.iterator.next() {
            // end of iterator, signal it then loop again
            None => {
                self.iterator = self.items.iter();
                None
            }
            Some(c) => {
                self.current = c.clone();
                Some(c.clone())
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
    fn new(variables: &'it [(Token<'src>, HashSet<EnumItem<'src>>)]) -> CrossIterator<'it, 'src> {
        CrossIterator {
            var_iterators: variables
                .iter()
                .map(|(v, i)| VariableIterator::new(*v, i))
                .collect(),
            init: true,
        }
    }
}

impl<'it, 'src> Iterator for CrossIterator<'it, 'src> {
    //                  variable  -> value
    type Item = HashMap<Token<'src>, EnumItem<'src>>;
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
                    .map(|vi| (vi.variable, vi.current.clone()))
                    .collect(),
            );
        }
        for v in &mut self.var_iterators {
            if v.next().is_none() {
                v.next(); // reset iterator then increment next one
            } else {
                return Some(
                    self.var_iterators
                        .iter()
                        .map(|vi| (vi.variable, vi.current.clone()))
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
    RangeCompare(
        Token<'src>,
        Token<'src>,
        Option<Token<'src>>,
        Option<Token<'src>>,
    ),
    And(Box<EnumExpression<'src>>, Box<EnumExpression<'src>>),
    Or(Box<EnumExpression<'src>>, Box<EnumExpression<'src>>),
    Not(Box<EnumExpression<'src>>),
    Default(Token<'src>), // token for position handling only
    NoDefault(Token<'src>), // token for position handling only
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
            EnumExpression::NoDefault(_) => (),
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
                let list = variables.entry((*var, *tree)).or_insert_with(HashSet::new);
                list.insert(*item);
            }
            EnumExpression::RangeCompare(var, tree, left, right) => {
                let list = variables.entry((*var, *tree)).or_insert_with(HashSet::new);
                // we only need one variable for its siblings
                // a range must be withing siblings
                // -> pushing only one item is sufficient
                if let Some(item) = left {
                    list.insert(*item);
                } else if let Some(item) = right {
                    list.insert(*item);
                } // else no item at all is forbidden
            }
        }
    }

    /// return true if this is just the expression 'default'
    pub fn is_default(&self) -> bool {
        match self {
            EnumExpression::Default(_) => true,
            EnumExpression::NoDefault(_) => true,
            _ => false,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::parser::tests::*;
    use maplit::{hashmap, hashset};

    fn item(name: &str) -> EnumItem {
        EnumItem::Item(name.into())
    }

    #[test]
    fn test_tree() {
        let mut tree = EnumTree::new(
            "T".into(),
            vec!["a".into(), "b".into(), "c".into()],
            true,
        );
        tree.extend("a".into(), vec!["d".into(), "e".into(), "f".into()]);
        tree.extend("e".into(), vec!["g".into(), "h".into(), "*".into()]);
        // ascendance
        assert_eq!(tree.get_ascendance(item("a")), vec!["T".into()]);
        assert_eq!(
            tree.get_ascendance(item("g")),
            vec!["T".into(), "a".into(), "e".into()]
        );
        // terminators
        assert_eq!(
            tree.terminators(false, hashset! {"a".into(), "b".into()}),
            hashset! {item("a"), item("b"), item("c")}
        );
        assert_eq!(
            tree.terminators(false, hashset! {"a".into(), "a".into(), "e".into()}),
            hashset! {item("b"), item("c"), item("d"), item("e"), item("f")}
        );
        assert_eq!(
            tree.terminators(false, hashset! {"a".into(), "g".into()}),
            hashset! {item("b"), item("c"), item("d"), item("f"), item("g"), item("h"), EnumItem::First("e".into()), EnumItem::Last("e".into())}
        );
    }

    #[test]
    fn test_enums() {
        let mut elist = EnumList::new();
        assert_eq!(elist.add_enum(penum_t("global enum T { a, b, c }")), Ok(()) );
        assert!(elist.add_enum(penum_t("enum T { a, b, c }")).is_err());
        assert!(elist.add_enum(penum_t("enum U { c, d, e }")).is_err());
        assert_eq!(elist.add_enum(penum_t("enum V { f, g, h }")), Ok(()));
        assert_eq!(elist
            .extend_enum(psub_enum_t("items in a { aa, ab, ac }")),
            Ok(None));
        assert!(elist
            .extend_enum(psub_enum_t("items in a { ba, bb, bc }"))
            .is_err());
        assert!(elist
            .extend_enum(psub_enum_t("items in b { aa, ab, ac }"))
            .is_err());
        assert_eq!(elist
            .extend_enum(psub_enum_t("items in aa { ba, bb, bc }")),
            Ok(None));
        assert_eq!(elist
            .extend_enum(psub_enum_t("items in ab { aba, abb, * }")),
            Ok(None));
        assert_eq!(elist
            .extend_enum(psub_enum_t("items in abb { xba, xbb, * }")),
            Ok(None));
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
            .extend_enum(psub_enum_t("items in g { j, k, * }"))
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
            hashmap! { ("T".into(), "T".into()) => hashset!{ "d".into(), "h".into() } }
        );
    }

    #[test]
    fn test_iterator() {
        let a = hashset! {item("a1"), item("a2")};
        let b = hashset! {item("b1")};
        let c = hashset! {item("c1"), item("c2"), item("c3")};
        let varlist = vec![("a".into(), a), ("b".into(), b), ("c".into(), c)];
        let it = CrossIterator::new(&varlist);
        // hard to test since hash(set/map) iterator don't have a fixed order
        let result = it.collect::<Vec<HashMap<Token, EnumItem>>>();
        assert_eq!(result.len(), 6);
        assert!(result.contains(&hashmap!{ Token::from("a") => item("a1"), Token::from("b") => item("b1"), Token::from("c") => item("c1")}));
        assert!(result.contains(&hashmap!{ Token::from("a") => item("a1"), Token::from("b") => item("b1"), Token::from("c") => item("c2")}));
        assert!(result.contains(&hashmap!{ Token::from("a") => item("a1"), Token::from("b") => item("b1"), Token::from("c") => item("c3")}));
        assert!(result.contains(&hashmap!{ Token::from("a") => item("a2"), Token::from("b") => item("b1"), Token::from("c") => item("c1")}));
        assert!(result.contains(&hashmap!{ Token::from("a") => item("a2"), Token::from("b") => item("b1"), Token::from("c") => item("c2")}));
        assert!(result.contains(&hashmap!{ Token::from("a") => item("a2"), Token::from("b") => item("b1"), Token::from("c") => item("c3")}));
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
            .extend_enum(psub_enum_t("items in b { g, h, i, * }"))
            .unwrap();
        elist
            .extend_enum(psub_enum_t("items in h { j, k, l, * }"))
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
        let e4 = elist
            .canonify_expression(&getter, penum_expression_t("..g|i.."))
            .unwrap();
        let e5 = elist
            .canonify_expression(&getter, penum_expression_t("l.."))
            .unwrap();
        let e6 = elist
            .canonify_expression(&getter, penum_expression_t("..k"))
            .unwrap();
        let cases1 = [
            (e1.clone(), Vec::new()),
            (e2.clone(), Vec::new()),
            (e3.clone(), Vec::new()),
        ];
        assert_eq!(
            elist.evaluate(&getter, &cases1[..], Token::from("test1")),
            Vec::new()
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
        let cases4 = [
            (e1.clone(), Vec::new()),
            (e3.clone(), Vec::new()),
            (e4.clone(), Vec::new()),
            (e5.clone(), Vec::new()),
            (e6.clone(), Vec::new()),
        ];
        assert_eq!(
            elist.evaluate(&getter, &cases4[..], Token::from("test4")),
            Vec::new()
        );
    }
}
