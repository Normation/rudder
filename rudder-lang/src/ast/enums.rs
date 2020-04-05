// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use super::context::VarKind;
use super::resource::Statement;
use super::enum_tree::{EnumItem, EnumTree};
use super::value::Value;
use crate::error::*;
use crate::parser::{PEnum, PEnumAlias, PEnumExpression, PMetadata, PSubEnum, Token};
use std::collections::{HashMap, HashSet};

// TODO named item tests
// TODO aliases tests

/// EnumList Is a singleton containing all enum definitions
/// It also has a direct pointer from an item name to its enum type
#[derive(Debug)]
pub struct EnumList<'src> {
    // item -> tree name, this list doesn't contain First and Last items of incomplete enums
    // it only contains items of global enums
    global_items: HashMap<Token<'src>, Token<'src>>,
    // tree name -> tree
    enums: HashMap<Token<'src>, EnumTree<'src>>,
}

impl<'src> EnumList<'src> {
    /// Create an empty list
    pub fn new() -> EnumList<'static> {
        EnumList {
            global_items: HashMap::new(),
            enums: HashMap::new(),
        }
    }

    /// Returns true if the given enum is global and None if the enum doesn't exist
    pub fn enum_is_global(&self, e: Token<'src>) -> Option<bool> {
        self.enums.get(&e).map(|e| e.global)
    }

    /// Returns the metadata of a given enum
    pub fn enum_metadata(&self, e: Token<'src>) -> Option<&HashMap<Token<'src>, Value<'src>>> {
        self.enums.get(&e).map(|e| &e.metadata)
    }

    /// Returns the metadata of a given enum item
    pub fn enum_item_metadata(&self, e: Token<'src>, i: Token<'src>) -> Option<&HashMap<Token<'src>, Value<'src>>> {
        self.enums.get(&e).and_then(|e| e.item_metadata.get(&i))
    }

    /// Returns the item if it is global and None otherwise
    pub fn global_enum(&self, e: Token<'src>) -> Option<&Token<'src>> {
        self.global_items.get(&e)
    }

    /// Iterate over enum names
    pub fn enum_iter(&self) -> impl Iterator<Item = &Token<'src>> {
        self.enums.iter().map(|(k, _)| k)
    }

    /// Iterate over global enum values only
    pub fn global_item_iter(&self) -> impl Iterator<Item = &Token<'src>> {
        self.global_items.iter().map(|(k, _)| k)
    }

    /// Iterate over item values in a given enum
    pub fn enum_item_iter<'a>(&'a self, tree_name: Token<'src>) -> Box<dyn Iterator<Item = &Token<'src>>+'a> {
        self.enums.get(&tree_name)
            .map(|tree| tree.item_iter())
            .unwrap_or_else(|| Box::new(std::iter::empty::<&Token>()))
    }

    /// Return true if item is in the range between first and last (inclusive) None means first/last sibling
    fn is_in_range(&self, item: &EnumItem<'src>, tree_name: Token<'src>, first: &Option<Token<'src>>, last: &Option<Token<'src>>) -> bool {
        let first_item = first.map(EnumItem::Item);
        let last_item = last.map(EnumItem::Item);
        self.enums[&tree_name].is_in_range(item, &first_item, &last_item)
    }

    /// check for item duplicates and insert reference into global list if needed
    fn add_to_global(global_items: &mut HashMap<Token<'src>, Token<'src>>, global: bool, name: Token<'src>, items: &Vec<(Vec<PMetadata<'src>>,Token<'src>)>) -> Result<()> {
        // check that enum is not empty
        if items.is_empty() {
            fail!(name, "Enum {} is empty", name);
        }
        // local list, if this is not a global enum
        let mut local_item_list = HashMap::new();
        let item_list = if global { global_items } else { &mut local_item_list };
        // check for key name duplicate and insert reference
        for (_,i) in items.iter() {
            // default value is not a real item
            if i.fragment() == "*" { continue }
            if item_list.contains_key(i) {
                fail!(
                        i,
                        "Enum Item {}:{} is already defined by {}",
                        name,
                        i,
                        item_list.entry(*i).key()
                    );
            }
            item_list.insert(*i, name);
        }
        Ok(())
    }

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
        Self::add_to_global(&mut self.global_items, e.global, e.name, &e.items)?;
        let name = e.name;
        let tree = EnumTree::new(e)?;
        self.enums.insert(name, tree);
        Ok(())
    }

    /// Extend an existing enum with "items in" from the parser
    /// return the structure back if the parent doesn't exist (yet)
    pub fn extend_enum(&mut self, e: PSubEnum<'src>) -> Result<Option<PSubEnum<'src>>> {
        // find tree name
        let tree_name = match e.enum_name {
            None => match self.global_items.get(&e.name) { // implicit (must be global)
                None => return Ok(Some(e)), // return because this can be because the parent hasn't been defined yet
                Some(t) => *t
            },
            Some(t) => t // explicit
        };

        // find tree object
        if let Some(tree) = self.enums.get_mut(&tree_name) {
            // check that we do not extend twice the same entry
            if tree.is_item_defined(&e.name) {
                fail!(e.name, "Sub enum {} is already defined", e.name);
            }
            // check for key name duplicate and insert reference
            Self::add_to_global(&mut self.global_items, tree.global, tree_name, &e.items)?;
            // insert keys
            tree.extend(e)?;
        } else {
            fail!(e.name, "Enum {} doesn't exist for {}", tree_name, e.name);
        }
        Ok(None)
    }

    /// Add an alias to an existing enum item
    pub fn add_alias(&mut self, alias: PEnumAlias<'src>) -> Result<()> {
        // find tree
        let tree_name = match alias.enum_name {
            // implicit tree name (must be global)
            None => match self.global_items.get(&alias.item) {
                None => fail!(alias.name, "Alias {} points to a non existent global enum", alias.name),
                Some(t) => *t
            },
            // explicit tree name
            Some(t) => if !self.enums.contains_key(&t) {
                fail!(alias.name, "Alias {} points to a non existent enum {}", alias.name, t);
            } else {
                t
            }
        };

        // alias must point to a existing item
        if !self.enums[&tree_name].has_item(alias.item) {
            fail!(alias.name, "Alias {} points to a non existent item {}", alias.name, alias.item);
        }

        // refuse duplicate names
        if self.enums[&tree_name].has_item(alias.name) {
            fail!(alias.name, "Alias {} duplicates existing item in {}", alias.name, tree_name);
        }

        // Ok
        if self.enums[&tree_name].global {
            self.global_items.insert(alias.name, tree_name);
        }
        self.enums.get_mut(&tree_name).unwrap().add_alias(alias.name, alias.item);
        Ok(())
    }

    /// Get variable enum type, if the variable doesn't exist or isn't an enum, return an error
    fn get_var_enum<VG>(&self, getter: &VG, variable: Token<'src>) -> Result<Token<'src>>
    where
        VG: Fn(Token<'src>) -> Option<VarKind<'src>>,
    {
        match getter(variable) {
            None => fail!(variable, "The variable {} doesn't exist", variable),
            Some(VarKind::Enum(e, _)) => Ok(e),
            Some(_) => fail!(variable, "The variable {} doesn't exist", variable),
        }
    }

    /// Return the canonical item in an enum, resolving aliases
    /// If the enum or the item don't exist, return an error
    fn item_in_enum(&self, tree_name: Token<'src>, item: Token<'src>) -> Result<Token<'src>> {
        match self.enums.get(&tree_name) {
            None => fail!(tree_name, "Enum {} doesn't exist", tree_name),
            Some(tree) => if tree.has_item(item) {
                Ok(tree.unalias(item))
            } else {
                fail!(item, "Item {} doesn't exist in enum {}", item, tree_name)
            }
        }
    }

    /// Validate a comparison between a variable and an enum item
    /// if the comparison is valid, return the fully qualified variable and enum item
    fn validate_comparison<VG>(&self, getter: &VG,
                           variable: Option<Token<'src>>,
                           tree_name: Option<Token<'src>>,
                           value: Token<'src>,
                           my_be_boolean: bool,
    ) -> Result<(Token<'src>, Token<'src>, Token<'src>)>
    where
        VG: Fn(Token<'src>) -> Option<VarKind<'src>>,
    {
        match (variable,tree_name) {
            (None, None) => {
                if let Some(tree_name) = self.global_items.get(&value) {
                    // - value exist and tree is global => var = treename = value global name
                    let val = self.item_in_enum(*tree_name, value)?;
                    Ok((*tree_name, *tree_name, val))
                    // tree_name
                    // var = tree_name
                } else {
                    if my_be_boolean {
                        // - value !exist => var = value, treename = boolean, value = true // var must exist and of type boolean
                        let t = self.get_var_enum(getter, value)?;
                        if t.fragment() == "boolean" { // TODO store these strings/token somewhere else ?
                            Ok((value, "boolean".into(), "true".into()))
                        } else {
                            fail!(value, "{} is not an enum item nor a boolean variable", value);
                        }
                    } else {
                        fail!(value, "{} is not a global enum item", value);
                    }
                }
            },
            (None, Some(t)) => {
                let tree = match self.enums.get(&t) {
                    None => fail!(t, "Enum {} doesn't exist or is not global", t),
                    Some(tree) => tree
                };
                if !tree.global {
                    fail!(t, "Enum {} must be global when not compared with a variable", t);
                }
                let val = self.item_in_enum(t, value)?;
                Ok((t, t, val))
            },
            (Some(v), None) => {
                // tree name = var type / value must exist and be in tree
                let t = self.get_var_enum(getter,v)?;
                let val = self.item_in_enum(t, value)?;
                Ok((v, t, val))
            },
            (Some(v), Some(t)) => {
                // var type must equal tree name, value must exist and be in tree
                let var_tree_name = self.get_var_enum(getter,v)?;
                if var_tree_name != t {
                    fail!(v, "Variable {} should have the type {}", v, t);
                }
                let val = self.item_in_enum(t, value)?;
                Ok((v, t, val))
            }
        }
    }

    /// Transforms a parsed enum expression into its final form using all enum definitions
    /// It needs a variable context (local and global) to check for proper variable existence
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
            PEnumExpression::Compare(var, tree_name, value) => {
                let (var_name, tree_name, value) = self.validate_comparison(getter, var, tree_name, value, true)?;
                Ok(EnumExpression::Compare(var_name, tree_name, value))
            },
            PEnumExpression::RangeCompare(var, tree_name, left, right, position) => {
                // left and right must not be both None
                let value = if let Some(item) = left {
                    item
                } else if let Some(item) = right {
                    item
                } else {
                    fail!(position, "Empty range is forbidden in enum expression")
                };
                let (var_name, tree_name, value) = self.validate_comparison(getter, var, tree_name, value, false)?;
                // check tat left and right are right type
                let new_left = match left {
                    None => None,
                    Some(item) => Some(self.item_in_enum(tree_name, item)?)
                };
                let new_right = match right {
                    None => None,
                    Some(item) => Some(self.item_in_enum(tree_name, item)?)
                };
                // check that left and right are siblings
                if let (Some(item1),Some(item2)) =  (new_left,new_right) {
                    if ! self.enums[&tree_name].are_siblings(item1, item2) {
                        fail!(value, "{} and {} are not siblings", left.unwrap(), right.unwrap());
                    }
                }
                Ok(EnumExpression::RangeCompare(var_name, tree_name, new_left, new_right))
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
            EnumExpression::RangeCompare(var, tree_name, first, last) => self.is_in_range(&values[&var], *tree_name, first, last),
        }
    }

    /// Evaluates a set of expressions possible outcome to check for missing cases and redundancy
    /// Variable context is here to guess variable type and to properly evaluate variables that are constant
    pub fn evaluate(
        &self,
        cases: &[(EnumExpression<'src>, Vec<Statement<'src>>)],
        case_name: Token<'src>,
    ) -> Vec<Error> {
        // keep only cases that are not default (default must be the last case and cases must not be empty)
        let (match_cases, default, nodefault) = match cases.last().unwrap().0 {
            EnumExpression::Default(_) => (cases.split_last().unwrap().1, true, false),
            EnumExpression::NoDefault(_) => (cases.split_last().unwrap().1, false, true),
            _ => (cases, false, false),
        };
        // list variables used in this expression
        let mut variables = HashMap::new();
        for case in cases.iter() {
            case.0.list_variables_tree(&mut variables);
        }
        // list terminators (list of items for each variables that must be used for iteration)
        // terminators include fake First and Last Item for incomplete enum branch so that we can check proper usage of open range and default
        // These are not included in case of nodefault because nodefault treats incomplete enums as complete
        let var_items: Vec<(Token<'src>, HashSet<EnumItem<'src>>)> = variables
            .into_iter()
            .map(|((variable, tree_name), items)| {
                let tree = &self.enums[&tree_name];
                (variable, tree.terminators(nodefault, items))
            })
            .collect();
        // create a cross product iterator for var1_terminators x var2_terminators x ...
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
                    "case {} is handled at least twice",
                    format_case(&context)
                ));
            }
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
// We need an item reference because we have a reference to the item list
// we cannot store the list in the same structure as its iterator
// so let the caller store it and just take reference
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
/// This is a cross product of variable iterators
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
    fn test_enums() {
        let mut elist = EnumList::new();
        assert_eq!(elist.add_enum(penum_t("global enum T { a, b, c }")), Ok(()) );
        assert!(elist.add_enum(penum_t("enum T { a, b, c }")).is_err());
        assert_eq!(elist.add_enum(penum_t("enum U { c, d, e }")), Ok(()));
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
        let getter = |_| Some(VarKind::Enum("T".into(), None));
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
                .canonify_expression(&getter, penum_expression_t("var=~d"))
                .unwrap(),
            EnumExpression::Compare("var".into(), "T".into(), "d".into())
        );
        assert!(elist
            .canonify_expression(&getter, penum_expression_t("d|a&!b"))
            .is_ok());
    }

    #[test]
    fn test_listvars() {
        let mut elist = EnumList::new();
        let getter = |_| Some(VarKind::Enum("T".into(), None));
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
            .canonify_expression(&getter, penum_expression_t("var=~a"))
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
            elist.evaluate(&cases1[..], Token::from("test1")),
            Vec::new()
        );
        let cases2 = [(e2.clone(), Vec::new()), (e3.clone(), Vec::new())];
        assert_eq!(
            elist
                .evaluate(&cases2[..], Token::from("test2"))
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
                .evaluate(&cases3[..], Token::from("test3"))
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
            elist.evaluate(&cases4[..], Token::from("test4")),
            Vec::new()
        );
    }
}
