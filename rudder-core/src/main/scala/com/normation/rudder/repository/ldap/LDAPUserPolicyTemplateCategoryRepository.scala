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

import com.normation.rudder.domain.policies._
import com.normation.ldap.sdk._
import com.unboundid.ldap.sdk.{DN, LDAPException,ResultCode}
import com.normation.ldap.sdk.{LDAPConnectionProvider,LDAPConnection,LDAPEntry,BuildFilter, LDAPBoolean}
import BuildFilter._
import LDAPBoolean._
import com.normation.inventory.ldap.core.LDAPConstants.{A_OC,A_NAME,A_DESCRIPTION}
import com.normation.rudder.domain.{RudderDit,RudderLDAPConstants}
import RudderLDAPConstants.{OC_CATEGORY, OC_USER_POLICY_TEMPLATE, A_CATEGORY_UUID, A_USER_POLICY_TEMPLATE_UUID, A_IS_SYSTEM}
import net.liftweb.common._
import com.normation.utils.Control._
import Box._

/**
 * Category for User Template library in the LDAP
 *
 */
class LDAPUserPolicyTemplateCategoryRepository(
  rudderDit: RudderDit, 
  ldap:LDAPConnectionProvider,
  mapper:LDAPEntityMapper
) extends UserPolicyTemplateCategoryRepository {
 
  repo =>

  /**
   * Find sub entries (children categories and user policy templates for 
   * the given category which MUST be mapped to an entry with the given
   * DN in the LDAP backend, accessible with the given connection. 
   */
  private[this] def addSubEntries(category:UserPolicyTemplateCategory, dn:DN, con:LDAPConnection) : UserPolicyTemplateCategory = {
    val subEntries = con.searchOne(dn, OR(EQ(A_OC, OC_CATEGORY),EQ(A_OC, OC_USER_POLICY_TEMPLATE)), "objectClass").partition(e => e.isA(OC_CATEGORY))
    category.copy(
      children = subEntries._1.map(e => mapper.dn2UserPolicyTemplateCategoryId(e.dn)).toList,
      items = subEntries._2.map(e => mapper.dn2UserPolicyTemplateId(e.dn)).toList
    )
  }
   
  /**
   * Retrieve the category entry for the given ID, with the given connection
   */
  def getCategoryEntry(con:LDAPConnection, id:UserPolicyTemplateCategoryId, attributes:String*) : Box[LDAPEntry] = {
    val categoryEntries = con.searchSub(rudderDit.POLICY_TEMPLATE_LIB.dn,  EQ(A_CATEGORY_UUID, id.value), attributes:_*)
    categoryEntries.size match {
      case 0 => Empty
      case 1 => Full(categoryEntries(0))
      case _ => Failure("Error, the directory contains multiple occurrence of category with id %s. DN: %s".format(id, categoryEntries.map( _.dn).mkString("; ")))
    } 
  }
  
  /**
   * Root user categories
   */
  def getUserPolicyTemplateLibrary : UserPolicyTemplateCategory = {
    (for {
      con <- ldap
      rootCategoryEntry <- con.get(rudderDit.POLICY_TEMPLATE_LIB.dn) ?~! "The root category of the user library of policy templates seems to be missing in LDAP directory. Please check its content"
      // look for sub category and policy template
      rootCategory <- mapper.entry2UserPolicyTemplateCategory(rootCategoryEntry) ?~! "Error when mapping from an LDAP entry to a User Policy Template Category: %s".format(rootCategoryEntry)
    } yield {
      addSubEntries(rootCategory,rootCategoryEntry.dn, con)
    }) match {
      case Full(root) => root
      case e:EmptyBox => throw new RuntimeException(e.toString)
    }
  }


  /**
   * Return all categories (lightweight version, with no children)
   * @return
   */
  def getAllUserPolicyTemplateCategories() : Box[Seq[UserPolicyTemplateCategory]] = {
    (for {
      con <- ldap
      rootCategoryEntry <- con.get(rudderDit.POLICY_TEMPLATE_LIB.dn) ?~! "The root category of the user library of policy templates seems to be missing in LDAP directory. Please check its content"
      entries = con.searchSub(rudderDit.POLICY_TEMPLATE_LIB.dn, AND(NOT(EQ(A_IS_SYSTEM, true.toLDAPString)),IS(OC_CATEGORY)))
      allEntries = entries :+ rootCategoryEntry
      categories <- boxSequence(allEntries.map(entry => mapper.entry2UserPolicyTemplateCategory(entry) ?~! "Error when transforming LDAP entry %s into a user policy template category".format(entry) ))
    } yield {
      categories
    })
  }
  /**
   * Get an user policy template by its ID
   */
  def getUserPolicyTemplateCategory(id:UserPolicyTemplateCategoryId) : Box[UserPolicyTemplateCategory] = {
    for {
      con <- ldap
      categoryEntry <- getCategoryEntry(con, id) ?~! "Entry with ID '%s' was not found".format(id)
      category <- mapper.entry2UserPolicyTemplateCategory(categoryEntry) ?~! "Error when transforming LDAP entry %s into a user policy template category".format(categoryEntry)
    } yield {
      addSubEntries(category,categoryEntry.dn, con)
    }
  }
  
  
  /**
   * Check if the given parent category has a child with the given name (exact) and
   * an id different from given id
   */
  private[this] def existsByName(con:LDAPConnection, parentDN:DN, subCategoryName:String, notId:String) : Boolean = {
    con.searchOne(parentDN, AND(EQ(A_NAME,subCategoryName),NOT(EQ(A_CATEGORY_UUID,notId))), "1.1" ).nonEmpty
  }
  
  /**
   * Add the given category into the given parent category in the
   * user library. 
   * Fails if the parent category does not exists in user lib or
   * if it already contains that category, or a category of the
   * same name (name must be unique for a given level)
   * 
   * return the modified parent category. 
   */
  def addUserPolicyTemplateCategory(
      that:UserPolicyTemplateCategory,
      into:UserPolicyTemplateCategory //parent category
  ) : Box[UserPolicyTemplateCategory] = {
    repo.synchronized { for {
      con <- ldap 
      parentCategoryEntry <- getCategoryEntry(con, into.id, "1.1") ?~! "The parent category '%s' was not found, can not add".format(into.id)
      categoryEntry = mapper.userPolicyTemplateCategory2ldap(that,parentCategoryEntry.dn)
      canAddByName <- {
        if(existsByName(con,parentCategoryEntry.dn, that.name, that.id.value)) {
          Failure("A category with that name already exists in that category: category names must be unique for a given level")
        } else {
          Full("Can add, no sub categorie with that name")
        }
      }
      result <- con.save(categoryEntry, removeMissingAttributes = true)
    } yield {
      addSubEntries(into, parentCategoryEntry.dn, con)
    } }
  }
  
  /**
   * Update an existing policy template category
   * Return the updated policy category
   */
  def saveUserPolicyTemplateCategory(category:UserPolicyTemplateCategory) : Box[UserPolicyTemplateCategory] = {
    repo.synchronized { for {
      con <- ldap 
      oldCategoryEntry <- getCategoryEntry(con, category.id, "1.1") ?~! "Entry with ID '%s' was not found".format(category.id)
      categoryEntry = mapper.userPolicyTemplateCategory2ldap(category,oldCategoryEntry.dn.getParent)
      canAddByName <- {
        if(categoryEntry.dn != rudderDit.POLICY_TEMPLATE_LIB.dn && existsByName(con,categoryEntry.dn.getParent, category.name, category.id.value)) {
          Failure("A category with that name already exists in that category: category names must be unique for a given level")
        } else {
          Full("Can add, no sub categorie with that name")
        }
      }
      result <- con.save(categoryEntry, removeMissingAttributes = true)
      updated <- getUserPolicyTemplateCategory(category.id)
    } yield {
      updated
    } }
  }
  
  /**
   * Get the direct parent of the given category.
   * Return empty for root of the hierarchy, fails if the category
   * is not in the repository
   */
  def getParentUserPolicyTemplateCategory(id:UserPolicyTemplateCategoryId) : Box[UserPolicyTemplateCategory] = {
    for {
      con <- ldap
      categoryEntry <- getCategoryEntry(con, id, "1.1") ?~! "Entry with ID '%s' was not found".format(id)
      parentCategoryEntry <- con.get(categoryEntry.dn.getParent)
      parentCategory <- mapper.entry2UserPolicyTemplateCategory(parentCategoryEntry) ?~! "Error when transforming LDAP entry %s into a user policy template category".format(parentCategoryEntry)
    } yield {
      addSubEntries(parentCategory, parentCategoryEntry.dn, con)
    }
  }
  
  /**
   * Return the list of parents for that category, the nearest parent
   * first, until the root of the library.
   * The the last parent is not the root of the library, return a Failure.
   * Also return a failure if the path to top is broken in any way.
   */
  def getParents_UserPolicyTemplateCategory(id:UserPolicyTemplateCategoryId) : Box[List[UserPolicyTemplateCategory]] = {
    //TODO : LDAPify that, we can have the list of all DN from id to root at the begining (just dn.getParent until rudderDit.POLICY_TEMPLATE_LIB.dn)
    if(id == getUserPolicyTemplateLibrary.id) Full(Nil)
    else getParentUserPolicyTemplateCategory(id) match {
      case Full(parent) => getParents_UserPolicyTemplateCategory(parent.id).map(parents => parent :: parents)
      case e:EmptyBox => e
    }     
  }
  
  def getParentUserPolicyTemplateCategory_forTemplate(id:UserPolicyTemplateId) : Box[UserPolicyTemplateCategory] = {
    for {
      con <- ldap
      uptEntries = con.searchSub(rudderDit.POLICY_TEMPLATE_LIB.dn, EQ(A_USER_POLICY_TEMPLATE_UUID, id.value))
      uptEntry <- uptEntries.size match {
        case 0 => Failure("Can not find user policy template with id '%s'".format(id))
        case 1 => Full(uptEntries(0))
        case _ => Failure("Found more than one user policy template with id '%s' : %s".format(id, uptEntries.map(_.dn).mkString("; ")))
      }
      category <- getUserPolicyTemplateCategory(mapper.dn2UserPolicyTemplateCategoryId(uptEntry.dn.getParent))
    } yield {
      category
    }
  }
  
  def delete(id:UserPolicyTemplateCategoryId, checkEmpty:Boolean = true) : Box[UserPolicyTemplateCategoryId] = {
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
  
  /**
   * Move an existing category into a new one.
   * Both category to move and destination have to exists, else it is a failure.
   * The destination category can not be a child of the category to move. 
   */
  def move(categoryId:UserPolicyTemplateCategoryId, intoParent:UserPolicyTemplateCategoryId) : Box[UserPolicyTemplateCategoryId] = {
      for {
        con <- ldap
        categoryEntry <- getCategoryEntry(con, categoryId, A_NAME)
        newParentEntry <- getCategoryEntry(con, intoParent, "1.1")
        moveAuthorised <- {
          if(newParentEntry.dn.isDescendantOf(categoryEntry.dn, true)) {
            Failure("Can not move a category to itself or one of its children")
          } else Full("Succes")
        }
        canAddByName <- (categoryEntry(A_CATEGORY_UUID) , categoryEntry(A_NAME)) match {
          case (Some(id),Some(name)) => 
            if(existsByName(con, newParentEntry.dn, name, id)) {
              Failure("A category with that name already exists in that category: category names must be unique for a given level")
            } else {
              Full("Can add, no sub categorie with that name")
            }
          case _ => Failure("Can not find the category entry name for category with ID %s. Name is needed to check unicity of categories by level")
        }
        result <- con.move(categoryEntry.dn, newParentEntry.dn)
      } yield {
        categoryId
      }
  }
 
}