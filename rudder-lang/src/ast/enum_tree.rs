// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use super::value::Value;
use crate::{
    ast::resource::create_metadata,
    error::*,
    parser::{PEnum, PMetadata, PSubEnum, Token},
};
use std::{
    collections::{HashMap, HashSet},
    fmt,
};

/// This item type is internal, because First and Last cannot be constructed from an enum declaration or from and enum expression
#[derive(Debug, Hash, PartialEq, Eq, Clone)]
pub enum EnumItem<'src> {
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

/// An enum tree is a structure containing the whole definition of an enum
#[derive(Debug)]
pub struct EnumTree<'src> {
    // global tree items cannot be used as a variable name
    // a global variable is automatically created with the same name as a global enum
    pub global: bool,
    // tree metadata
    pub metadata: HashMap<Token<'src>, Value<'src>>,
    // item metadata
    pub item_metadata: HashMap<Token<'src>, HashMap<Token<'src>, Value<'src>>>,
    // Tree name = root element
    name: Token<'src>,
    // parent -> ordered children
    children: HashMap<Token<'src>, Vec<EnumItem<'src>>>,
    // child -> parent
    parents: HashMap<EnumItem<'src>, Token<'src>>,
    // Alias -> item
    aliases: HashMap<Token<'src>, Token<'src>>,
}
impl<'src> EnumTree<'src> {
    /// Insert items to this tree
    fn insert_items(
        &mut self,
        father: Token<'src>,
        items: Vec<(Vec<PMetadata<'src>>, Token<'src>)>,
    ) -> Result<()> {
        // detect incompleteness
        let mut incomplete = false;
        let mut children = Vec::new();
        let mut errors = Vec::new();
        for (meta, item) in items.into_iter() {
            if *item == "*" {
                incomplete = true;
                continue;
            }
            self.parents.insert(EnumItem::Item(item), father);
            children.push(EnumItem::Item(item));
            let (mut err, metadata) = create_metadata(meta);
            errors.append(&mut err);
            self.item_metadata.insert(item, metadata);
        }
        if incomplete {
            self.parents.insert(EnumItem::First(father), father);
            self.parents.insert(EnumItem::Last(father), father);
            children.insert(0, EnumItem::First(father));
            children.push(EnumItem::Last(father));
        };
        self.children.insert(father, children);
        if !errors.is_empty() {
            Err(Error::from_vec(errors))
        } else {
            Ok(())
        }
    }

    /// create a new enum tree with a single level of children
    pub fn new(penum: PEnum<'src>) -> Result<EnumTree<'src>> {
        let PEnum {
            name,
            items,
            global,
            metadata,
        } = penum;
        let (errors, metadata) = create_metadata(metadata);
        if !errors.is_empty() {
            return Err(Error::from_vec(errors));
        }
        let mut myself = EnumTree {
            global,
            metadata,
            name,
            children: HashMap::new(),
            parents: HashMap::new(),
            aliases: HashMap::new(),
            item_metadata: HashMap::new(),
        };
        // item uniqueness has already been checked
        myself.insert_items(name, items)?;
        Ok(myself)
    }

    /// add a new level to an existing tree
    pub fn extend(&mut self, psub_enum: PSubEnum<'src>) -> Result<()> {
        let PSubEnum {
            name,
            enum_name,
            items,
        } = psub_enum;
        let father = EnumItem::Item(name);
        if !self.parents.contains_key(&father) {
            panic!("Subtree's parent must exist !")
        }
        // item uniqueness has already been checked
        self.insert_items(name, items)
    }

    /// Add an alias to an existing item
    pub fn add_alias(&mut self, alias: Token<'src>, item: Token<'src>) {
        self.aliases.insert(alias, item);
    }

    /// return true if this item exists
    pub fn has_item(&self, item: Token<'src>) -> bool {
        self.parents.contains_key(&EnumItem::Item(item)) || self.aliases.contains_key(&item)
    }

    /// return the item from a name (item or alias)
    pub fn unalias(&self, name: Token<'src>) -> Token<'src> {
        match self.aliases.get(&name) {
            None => name,
            Some(i) => *i,
        }
    }

    /// Iterate over all items of this tree (excluding First and Last)
    pub fn item_iter<'a>(&'a self) -> Box<dyn Iterator<Item = &Token<'src>> + 'a> {
        Box::new(
            self.parents
                .iter()
                .filter(|(k, _)| match k {
                    EnumItem::Item(_) => true,
                    _ => false,
                })
                .map(move |(k, _)| match k {
                    EnumItem::Item(i) => i,
                    _ => &self.name, // never happens
                }),
        )
    }

    /// iterate over ascendants (root, ... great father, father )
    pub fn get_ascendants(&self, item: EnumItem<'src>) -> Vec<Token<'src>> {
        if self.parents.contains_key(&item) {
            let mut ascendants = self.get_ascendants(EnumItem::Item(self.parents[&item]));
            ascendants.push(self.parents[&item]);
            ascendants
        } else {
            Vec::new()
        }
    }

    /// return true if the item is defined in this tree
    pub fn is_item_defined(&self, item: &Token<'src>) -> bool {
        self.children.contains_key(item)
    }

    /// Return true if item is in the range between first and last (inclusive) None means last sibling
    pub fn is_in_range(
        &self,
        item: &EnumItem<'src>,
        first: &Option<EnumItem<'src>>,
        last: &Option<EnumItem<'src>>,
    ) -> bool {
        // 3 cases : item is a sibling, item is a descendant, item is somewhere else

        // find siblings
        let item_list = if let Some(i) = first {
            &self.children[&self.parents[&i]]
        } else if let Some(i) = last {
            &self.children[&self.parents[&i]]
        } else {
            panic!("Empty range")
        }; // else None,None is imposible

        // if item is a descendant, find its ancestor that is a sibling
        let test_item = if item_list.contains(item) {
            // Item is a sibling
            item.clone()
        } else {
            match self
                .get_ascendants(item.clone())
                .iter()
                .find(|i| item_list.contains(&EnumItem::Item(**i)))
            {
                Some(p) => EnumItem::Item(*p), // p is an ascendant of item that is in the list
                None => return false,          // item is not in this subtree
            }
        };
        // find each item position
        let item_position = item_list.iter().position(|x| x == &test_item).unwrap(); // item is necessary in the list
        let first_position = match first {
            None => 0,
            Some(i) => item_list.iter().position(|x| x == i).unwrap(), // first and last are necessary in the list
        };
        let last_position = match last {
            None => item_list.len() - 1,
            Some(i) => item_list.iter().position(|x| x == i).unwrap(), // first and last are necessary in the list
        };
        item_position >= first_position && item_position <= last_position
    }

    /// return true if left and right have same parent
    pub fn are_siblings(&self, left: Token<'src>, right: Token<'src>) -> bool {
        self.parents.get(&EnumItem::Item(left)) == self.parents.get(&EnumItem::Item(right))
    }

    /// Given a list of nodes, find the subtree that includes all those nodes and their siblings
    /// This subtree is the minimal enum tree used by an expression
    /// Terminators returns the leaves of this subtree
    pub fn terminators(
        &self,
        nodefault: bool,
        items: HashSet<Token<'src>>,
    ) -> HashSet<EnumItem<'src>> {
        let mut terminators = HashSet::new();
        // insert root elements of tree
        terminators.insert(EnumItem::Item(self.name));
        // invariant: terminators is the leaves list of a subtree of 'tree'
        for item in items.iter() {
            // from top to bottom ascendant, if ascendant in list, remove it and add its children
            for ascendant in self.get_ascendants(EnumItem::Item(*item)).iter() {
                let ascendant_item = EnumItem::Item(*ascendant);
                if terminators.contains(&ascendant_item) {
                    terminators.remove(&ascendant_item);
                    for i in self.children[ascendant].iter() {
                        if nodefault {
                            match i {
                                EnumItem::First(_) | EnumItem::Last(_) => {}
                                _ => {
                                    terminators.insert(i.clone());
                                }
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::parser::tests::{penum_t, psub_enum_t};
    use maplit::hashset;

    fn item(name: &str) -> EnumItem {
        EnumItem::Item(name.into())
    }

    #[test]
    fn test_tree() {
        let mut tree = EnumTree::new(penum_t("global enum T { a, b, c }")).expect("Valid enum 1");
        assert_eq!(tree.extend(psub_enum_t("items in a { d, e, f }")), Ok(()));
        assert_eq!(tree.extend(psub_enum_t("items in e { g, h, * }")), Ok(()));
        // ascendants
        assert_eq!(tree.get_ascendants(item("a")), vec!["T".into()]);
        assert_eq!(
            tree.get_ascendants(item("g")),
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
}
