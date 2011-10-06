/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.rudder.repository
package ldap

import com.normation.rudder.domain.nodes._
import com.normation.utils.Control._
import net.liftweb.common._

import com.normation.ldap.sdk._
import com.unboundid.ldap.sdk.{DN, LDAPException,ResultCode}
import com.normation.ldap.sdk.{LDAPConnectionProvider,LDAPConnection,LDAPEntry,BuildFilter}
import BuildFilter.{OR,EQ,IS,AND,NOT}
import com.normation.inventory.ldap.core.LDAPConstants.{A_OC,A_NAME,A_DESCRIPTION}
import com.normation.rudder.domain.{RudderDit,RudderLDAPConstants}
import RudderLDAPConstants._
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.PolicyInstanceTarget


class LDAPGroupCategoryRepository(
  rudderDit: RudderDit, 
  ldap:LDAPConnectionProvider,
  mapper:LDAPEntityMapper) extends GroupCategoryRepository with Loggable {
	
	repo =>
	

	/**
   * Find sub entries (children group categories and server groups for 
   * the given category which MUST be mapped to an entry with the given
   * DN in the LDAP backend, accessible with the given connection. 
   */
  private[this] def addSubEntries(category:NodeGroupCategory, dn:DN, con:LDAPConnection) : NodeGroupCategory = {
    val subEntries = con.searchOne(dn, OR(IS(OC_GROUP_CATEGORY),IS(OC_RUDDER_NODE_GROUP),IS(OC_SPECIAL_TARGET)), 
        A_OC, A_NODE_GROUP_UUID, A_NAME, A_POLICY_TARGET, A_DESCRIPTION, A_IS_ACTIVATED, A_IS_SYSTEM).partition(e => e.isA(OC_GROUP_CATEGORY))
    category.copy(
      children = subEntries._1.sortBy(e => e(A_NAME)).map(e => mapper.dn2NodeGroupCategoryId(e.dn)).toList,
      items = subEntries._2.sortBy(e => e(A_NAME)).flatMap(entry => mapper.entry2PolicyInstanceTargetInfo(entry) match {
        case Full(targetInfo) => Some(targetInfo)
        case e:EmptyBox => 
          logger.error((e ?~! "Error when trying to get the child of group category '%s' with DN '%s'".format(category.id, entry.dn)).messageChain)
          None
      }).toList
    )
  }

  /**
   * Check if a group category exist with the given name
   */
  private[this] def categoryExists(con:LDAPConnection, name : String, parentDn : DN) : Boolean = {
    con.searchOne(parentDn, AND(IS(OC_GROUP_CATEGORY), EQ(A_NAME, name)), A_GROUP_CATEGORY_UUID).size match {
      case 0 => false
      case 1 => true
      case _ => logger.error("More than one nodeCategory has %s name under %s".format(name, parentDn)); true
    }
  }

    /**
   * Check if a group category exist with the given name
   */
  private[this] def categoryExists(con:LDAPConnection, name : String, parentDn : DN, currentId : NodeGroupCategoryId) : Boolean = {
    con.searchOne(parentDn, AND(NOT(EQ(A_GROUP_CATEGORY_UUID, currentId.value)), AND(IS(OC_GROUP_CATEGORY), EQ(A_NAME, name))), A_GROUP_CATEGORY_UUID).size match {
      case 0 => false
      case 1 => true
      case _ => logger.error("More than one nodeCategory has %s name under %s".format(name, parentDn)); true
    }
  }

  /**
   * Retrieve the category entry for the given ID, with the given connection
   */
  def getCategoryEntry(con:LDAPConnection, id:NodeGroupCategoryId, attributes:String*) : Box[LDAPEntry] = {
    val categoryEntries = con.searchSub(rudderDit.GROUP.dn,  EQ(A_GROUP_CATEGORY_UUID, id.value), attributes:_*)
    categoryEntries.size match {
      case 0 => Empty
      case 1 => Full(categoryEntries(0))
      case _ => Failure("Error, the directory contains multiple occurrence of group category with id %s. DN: %s".format(id, categoryEntries.map( _.dn).mkString("; ")))
    } 
  }
  
  def getAllGroupCategories() : Box[List[NodeGroupCategory]] = {
  	val list = for {
  		con <- ldap
  		categoryEntries = con.searchSub(rudderDit.GROUP.dn, IS(OC_GROUP_CATEGORY))
  		ids = categoryEntries.map(e => mapper.dn2NodeGroupCategoryId(e.dn))
  		result = ids.map(getGroupCategory _)
  	} yield {
  		result
  	}
  	
  	list match {
  		case Full(entries) => var result : Box[List[NodeGroupCategory]] = Full(Nil)
												  	for (entry <- entries) {
												  		entry match {
												  			case Full(x) => result = Full(x :: result.open_!)
												  			case Empty => return Empty
												  			case x : Failure => return x
												  		}
												  	}
												    result 
  		case Empty => Empty
  		case x : Failure => x
  	}
  }
  
  /**
   * Root group category
   */
  def getRootCategory(): NodeGroupCategory = { 
  	(for {
      con <- ldap
      rootCategoryEntry <- con.get(rudderDit.GROUP.dn) ?~! "The root category of the server group category seems to be missing in LDAP directory. Please check its content"
      // look for sub category and policy template
      rootCategory <- mapper.entry2NodeGroupCategory(rootCategoryEntry) ?~! "Error when mapping from an LDAP entry to a Server Group Category: %s".format(rootCategoryEntry)
    } yield {
      addSubEntries(rootCategory,rootCategoryEntry.dn, con)
    }) match {
      case Full(root) => root
      case e:EmptyBox => throw new RuntimeException(e.toString)
    }  	
  }

  /**
   * retrieve the hierarchy of group category/group containing the selected node
   */
  def findGroupHierarchy(categoryId : NodeGroupCategoryId, targets : Seq[PolicyInstanceTarget])  : Box[NodeGroupCategory] = {
     getGroupCategory(categoryId) match {
       case e : EmptyBox => return e
       case Full(category) =>
         val newCategory = new NodeGroupCategory(
            category.id,
            category.name,
            category.description,
            category.children.filter( x => findGroupHierarchy(x, targets) match {
               case Full(x) if x.isSystem == false => true
               case _ => false
            }),
            category.items.filter( p => targets.contains(p.target)),
            category.isSystem
         )



         if ((newCategory.items.size == 0) && (newCategory.children.size==0))
            return Empty
         else {
            return Full(newCategory)
         }
     }
  }



  /**
   * Get a group category by its id
   * */
  def getGroupCategory(id: NodeGroupCategoryId): Box[NodeGroupCategory] = { 
  	for {
      con <- ldap
      categoryEntry <- getCategoryEntry(con, id) ?~! "Entry with ID '%s' was not found".format(id)
      category <- mapper.entry2NodeGroupCategory(categoryEntry) ?~! "Error when transforming LDAP entry %s into a server group category".format(categoryEntry)
    } yield {
      addSubEntries(category,categoryEntry.dn, con)
    }
  }

  /**
   * Add that group categoy into the given parent category
   * Fails if the parent category does not exists or
   * if it already contains that category. 
   * 
   * return the new category.
   */
  def addGroupCategorytoCategory(that: NodeGroupCategory, 
  		into: NodeGroupCategoryId): Box[NodeGroupCategory] = {
  	repo.synchronized { for {
      con <- ldap 
      parentCategoryEntry <- getCategoryEntry(con, into, "1.1") ?~! "The parent category '%s' was not found, can not add".format(into)
      exists <- if (categoryExists(con, that.name, parentCategoryEntry.dn)) Failure("Cannot create the Node Group Category with name %s : a category with the same name exists at the same level".format(that.name))
              else Full(Unit)
      categoryEntry = mapper.nodeGroupCategory2ldap(that,parentCategoryEntry.dn)
      result <- con.save(categoryEntry, removeMissingAttributes = true)
      newCategory <- getGroupCategory(that.id) ?~! "The newly created category '%s' was not found".format(that.id.value)
    } yield {
      newCategory
    } }
  }

  /**
   * Update an existing group category
   */
  def saveGroupCategory(category: NodeGroupCategory): Box[NodeGroupCategory] = { 
  	repo.synchronized { for {
      con <- ldap 
      oldCategoryEntry <- getCategoryEntry(con, category.id, "1.1") ?~! "Entry with ID '%s' was not found".format(category.id)
      categoryEntry = mapper.nodeGroupCategory2ldap(category,oldCategoryEntry.dn.getParent)
      exists <- if (categoryExists(con, category.name, oldCategoryEntry.dn.getParent, category.id)) Failure("Cannot update the Node Group Category with name %s : a category with the same name exists at the same level".format(category.name))
              else Full(Unit)
      result <- con.save(categoryEntry, removeMissingAttributes = true)
      updated <- getGroupCategory(category.id)
    } yield {
      updated
    } }
  }

   /**
   * Update/move an existing group category
   */
  def saveGroupCategory(category: NodeGroupCategory, containerId : NodeGroupCategoryId): Box[NodeGroupCategory] = {
  	repo.synchronized { for {
      con <- ldap
      oldCategoryEntry <- getCategoryEntry(con, category.id, "1.1") ?~! "Entry with ID '%s' was not found".format(category.id)
      newParent <- getCategoryEntry(con, containerId, "1.1") ?~! "Parent entry with ID '%s' was not found".format(containerId)
      exists <- if (categoryExists(con, category.name, newParent.dn, category.id)) Failure("Cannot update the Node Group Category with name %s : a category with the same name exists at the same level".format(category.name))
              else Full(Unit)
      categoryEntry = mapper.nodeGroupCategory2ldap(category,newParent.dn)
      moved = if (newParent.dn == oldCategoryEntry.dn.getParent) {  } else { con.move(oldCategoryEntry.dn, newParent.dn) }
      result <- con.save(categoryEntry, removeMissingAttributes = true)
      updated <- getGroupCategory(category.id)
    } yield {
      updated
    } }
  }

  /**
   * Get the direct parent of the given category.
   * Return empty for root of the hierarchy, fails if the category
   * is not in the repository
   */
  def getParentGroupCategory(id: NodeGroupCategoryId): Box[NodeGroupCategory] = { 
  	for {
      con <- ldap
      categoryEntry <- getCategoryEntry(con, id, "1.1") ?~! "Entry with ID '%s' was not found".format(id)
      parentCategoryEntry <- con.get(categoryEntry.dn.getParent)
      parentCategory <- mapper.entry2NodeGroupCategory(parentCategoryEntry) ?~! "Error when transforming LDAP entry %s into a user policy template category".format(parentCategoryEntry)
    } yield {
      addSubEntries(parentCategory, parentCategoryEntry.dn, con)
    }  	
  }

 /**
   * Returns all non system categories + the root category
   * Caution, they are "lightweight" group categories (no children)
   */
  def getAllNonSystemCategories(): Box[Seq[NodeGroupCategory]] = {
    for {
       con <- ldap
       rootCategoryEntry <- con.get(rudderDit.GROUP.dn) ?~! "The root category of the server group category seems to be missing in LDAP directory. Please check its content"
       categoryEntries = con.searchSub(rudderDit.GROUP.dn, AND(NOT(EQ(A_IS_SYSTEM, true.toLDAPString)),IS(OC_GROUP_CATEGORY)))
       allEntries = categoryEntries :+ rootCategoryEntry
       entries <- boxSequence(allEntries.map(x => mapper.entry2NodeGroupCategory(x)))
    } yield {
      entries
    }
  }
  
  
  /**
   * Delete the category with the given id.
   * If no category with such id exists, it is a success.
   * If checkEmtpy is set to true, the deletion may be done only if
   * the category is empty (else, category and children are deleted).
   * @param id
   * @param checkEmtpy
   * @return 
   *  - Full(category id) for a success
   *  - Failure(with error message) iif an error happened. 
   */
  def delete(id:NodeGroupCategoryId, checkEmpty:Boolean = true) : Box[NodeGroupCategoryId] = {
    for {
      con <-ldap
      deleted <- {
        getCategoryEntry(con, id, "1.1") match {
          case Full(entry) => 
            for {
              ok <- 
                try {
                  con.delete(entry.dn, recurse = !checkEmpty) ?~! "Error when trying to delete category with ID '%s'".format(id)
                } catch {
                  case e:LDAPException if(e.getResultCode == ResultCode.NOT_ALLOWED_ON_NONLEAF) => Failure("Can not delete a non empty category")
                  case e => Failure("Exception when trying to delete category with ID '%s'".format(id), Full(e), Empty)
                }
            } yield {
              id
            }
          case Empty => Full(id)
          case f:Failure => f
        }
      }
    } yield {
      deleted
    }
  }
  

}