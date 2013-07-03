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
import com.normation.inventory.ldap.core.LDAPConstants.{A_OC, A_NAME}
import com.unboundid.ldap.sdk.{DN,Filter}
import com.normation.ldap.sdk._
import BuildFilter._
import com.normation.rudder.domain.{RudderDit,RudderLDAPConstants}
import RudderLDAPConstants._
import net.liftweb.common._
import com.normation.utils.Control.sequence
import com.normation.eventlog.EventActor
import com.normation.rudder.domain.eventlog.{
  DeleteDirective,AddDirective,ModifyDirective
}
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.cfclerk.domain.TechniqueId
import com.normation.utils.ScalaReadWriteLock
import com.normation.rudder.services.user.PersonIdentService
import com.normation.cfclerk.domain.Technique
import com.normation.eventlog.ModificationId
import com.normation.utils.Control._
import com.normation.ldap.ldif.LDIFNoopChangeRecord
import com.unboundid.ldap.sdk.LDAPException
import com.unboundid.ldap.sdk.ResultCode
import com.normation.cfclerk.domain.TechniqueName
import scala.collection.immutable.SortedMap
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.utils.StringUuidGenerator
import net.liftweb.json.Printer
import net.liftweb.json.JsonAST
import org.joda.time.DateTime
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueName


class RoLDAPDirectiveRepository(
    val rudderDit                     : RudderDit
  , val ldap                          : LDAPConnectionProvider[RoLDAPConnection]
  , val mapper                        : LDAPEntityMapper
  , val techniqueRepository           : TechniqueRepository
  , val userLibMutex                  : ScalaReadWriteLock //that's a scala-level mutex to have some kind of consistency with LDAP
) extends RoDirectiveRepository with Loggable {

  /**
   * Retrieve the directive entry for the given ID, with the given connection
   */
  def getDirectiveEntry(con:RoLDAPConnection, id:DirectiveId, attributes:String*) : Box[LDAPEntry] = {
    val piEntries = con.searchSub(rudderDit.ACTIVE_TECHNIQUES_LIB.dn,  EQ(A_DIRECTIVE_UUID, id.value), attributes:_*)
    piEntries.size match {
      case 0 => Empty
      case 1 => Full(piEntries(0))
      case _ => Failure("Error, the directory contains multiple occurrence of directive with id %s. DN: %s".format(id, piEntries.map( _.dn).mkString("; ")))
    }
  }


  private[this] def policyFilter(includeSystem:Boolean = false) = if(includeSystem) IS(OC_DIRECTIVE) else AND(IS(OC_DIRECTIVE), EQ(A_IS_SYSTEM,false.toLDAPString))


  /**
   * Try to find the directive with the given ID.
   * Empty: no directive with such ID
   * Full((parent,directive)) : found the directive (directive.id == directiveId) in given parent
   * Failure => an error happened.
   */
  override def getDirective(id:DirectiveId) : Box[Directive] = {
    for {
      locked    <- userLibMutex.readLock
      con       <- ldap
      piEntry   <- getDirectiveEntry(con, id) ?~! "Can not find directive with id %s".format(id)
      directive <- mapper.entry2Directive(piEntry) ?~! "Error when transforming LDAP entry into a directive for id %s. Entry: %s".format(id, piEntry)
    } yield {
      directive
    }
  }

  override def getDirectiveWithContext(directiveId:DirectiveId) : Box[(Technique, ActiveTechnique, Directive)] = {
    for {
      directive         <- this.getDirective(directiveId) ?~! "No user Directive with ID=%s.".format(directiveId)
      activeTechnique   <- this.getActiveTechnique(directiveId) ?~! "Can not find the Active Technique for Directive %s".format(directiveId)
      activeTechniqueId = TechniqueId(activeTechnique.techniqueName, directive.techniqueVersion)
      technique         <- Box(techniqueRepository.get(activeTechniqueId)) ?~! "No Technique with ID=%s found in reference library.".format(activeTechniqueId)
    } yield {
      (technique, activeTechnique, directive)
    }
  }


  override def getAll(includeSystem:Boolean = false) : Box[Seq[Directive]] = {
    for {
      locked     <- userLibMutex.readLock
      con        <- ldap
      //for each directive entry, map it. if one fails, all fails
      directives <- sequence(con.searchSub(rudderDit.ACTIVE_TECHNIQUES_LIB.dn,  policyFilter(includeSystem))) { piEntry =>
                      mapper.entry2Directive(piEntry) ?~! "Error when transforming LDAP entry into a directive. Entry: %s".format(piEntry)
                    }
    } yield {
      directives
    }
  }


  /**
   * Find the active technique for which the given policy
   * instance is an instance.
   *
   * Return empty if no such directive is known,
   * fails if no active technique match the directive.
   */
  override def getActiveTechnique(id:DirectiveId) : Box[ActiveTechnique] = {
    for {
      locked          <- userLibMutex.readLock
      con             <- ldap
      piEntry         <- getDirectiveEntry(con, id, "1.1") ?~! "Can not find directive with id %s".format(id)
      activeTechnique <- getActiveTechnique(mapper.dn2ActiveTechniqueId(piEntry.dn.getParent))
    } yield {
      activeTechnique
    }
  }

  /**
   * Find the active technique for which the given directive is an instance.
   *
   * Return empty if no such directive is known,
   * fails if no active technique match the directive.
   */
  override def getActiveTechniqueAndDirective(id:DirectiveId) : Box[(ActiveTechnique, Directive)] = {
    for {
      locked  <- userLibMutex.readLock
      con     <- ldap
      piEntry <- getDirectiveEntry(con, id) ?~! "Can not find directive with id %s".format(id)
      uptEntry        <- getUPTEntry(con, mapper.dn2ActiveTechniqueId(piEntry.dn.getParent), { id:ActiveTechniqueId => EQ(A_ACTIVE_TECHNIQUE_UUID, id.value) }) ?~! "Can not find Active Technique entry in LDAP"
      activeTechnique <- mapper.entry2ActiveTechnique(uptEntry) ?~! "Error when mapping active technique entry to its entity. Entry: %s".format(uptEntry)
      directive       <- mapper.entry2Directive(piEntry) ?~! "Error when transforming LDAP entry into a directive for id %s. Entry: %s".format(id, piEntry)
    } yield {
      (activeTechnique, directive)
    }
  }
  /**
   * Get directives for given technique.
   * A not known technique id is a failure.
   */
  override def getDirectives(activeTechniqueId:ActiveTechniqueId, includeSystem:Boolean = false) : Box[Seq[Directive]] = {
    for {
      locked     <- userLibMutex.readLock
      con        <- ldap
      ptEntry    <- getUPTEntry(con, activeTechniqueId, "1.1")
      directives <- sequence(con.searchOne(ptEntry.dn, policyFilter(includeSystem))) { piEntry =>
                      mapper.entry2Directive(piEntry) ?~! "Error when transforming LDAP entry into a directive. Entry: %s".format(piEntry)
                    }
    } yield {
      directives
    }
  }

  /**
   * Return true if at least one directive exists in this category (or a sub category
   * of this category)
   */
  def containsDirective(id: ActiveTechniqueCategoryId) : Boolean = {
    (for {
      con               <- ldap
      locked            <- userLibMutex.readLock
      category          <- getCategoryEntry(con, id) ?~! "Entry with ID '%s' was not found".format(id)
    } yield {
      val results = con.searchSub(category.dn, IS(OC_DIRECTIVE), Seq[String]():_*)
      results.map( x => mapper.entry2Directive(x) ).flatten.size > 0
    }) match {
      case Full(x) => x
      case _ => false
    }
  }

  /**
   * Root user categories
   */
  def getActiveTechniqueLibrary : Box[ActiveTechniqueCategory] = {
    for {
      con               <- ldap
      locked            <- userLibMutex.readLock
      rootCategoryEntry <- con.get(rudderDit.ACTIVE_TECHNIQUES_LIB.dn) ?~! "The root category of the user library of techniques seems to be missing in LDAP directory. Please check its content"
      // look for sub category and technique
      rootCategory      <- mapper.entry2ActiveTechniqueCategory(rootCategoryEntry) ?~! "Error when mapping from an LDAP entry to an active technique Category: %s".format(rootCategoryEntry)
    } yield {
      addSubEntries(rootCategory,rootCategoryEntry.dn, con)
    }
  }


  /**
   * Return all categories (lightweight version, with no children)
   * @return
   */
  def getAllActiveTechniqueCategories(includeSystem:Boolean = false) : Box[Seq[ActiveTechniqueCategory]] = {
    (for {
      con               <- ldap
      locked            <- userLibMutex.readLock
      rootCategoryEntry <- con.get(rudderDit.ACTIVE_TECHNIQUES_LIB.dn) ?~! "The root category of the user library of techniques seems to be missing in LDAP directory. Please check its content"
      filter            =  if(includeSystem) IS(OC_TECHNIQUE_CATEGORY) else AND(NOT(EQ(A_IS_SYSTEM, true.toLDAPString)),IS(OC_TECHNIQUE_CATEGORY))
      entries           =  con.searchSub(rudderDit.ACTIVE_TECHNIQUES_LIB.dn, filter) //double negation is mandatory, as false may not be present
      allEntries        =  entries :+ rootCategoryEntry
      categories        <- boxSequence(allEntries.map(entry => mapper.entry2ActiveTechniqueCategory(entry) ?~! "Error when transforming LDAP entry %s into an active technique category".format(entry) ))
    } yield {
      categories
    })
  }
  /**
   * Get an active technique by its ID
   */
  def getActiveTechniqueCategory(id:ActiveTechniqueCategoryId) : Box[ActiveTechniqueCategory] = {
    for {
      con           <- ldap
      locked        <- userLibMutex.readLock
      categoryEntry <- getCategoryEntry(con, id) ?~! "Entry with ID '%s' was not found".format(id)
      category      <- mapper.entry2ActiveTechniqueCategory(categoryEntry) ?~! "Error when transforming LDAP entry %s into an active technique category".format(categoryEntry)
    } yield {
      addSubEntries(category,categoryEntry.dn, con)
    }
  }

  /**
   * Find sub entries (children categories and active techniques for
   * the given category which MUST be mapped to an entry with the given
   * DN in the LDAP backend, accessible with the given connection.
   */
  def addSubEntries(category:ActiveTechniqueCategory, dn:DN, con:RoLDAPConnection) : ActiveTechniqueCategory = {
    val subEntries = con.searchOne(dn, OR(EQ(A_OC, OC_TECHNIQUE_CATEGORY),EQ(A_OC, OC_ACTIVE_TECHNIQUE)), "objectClass").partition(e => e.isA(OC_TECHNIQUE_CATEGORY))
    category.copy(
      children = subEntries._1.map(e => mapper.dn2ActiveTechniqueCategoryId(e.dn)).toList,
      items = subEntries._2.map(e => mapper.dn2ActiveTechniqueId(e.dn)).toList
    )
  }



  /**
   * Retrieve the category entry for the given ID, with the given connection
   */
  def getCategoryEntry(con:RoLDAPConnection, id:ActiveTechniqueCategoryId, attributes:String*) : Box[LDAPEntry] = {
    userLibMutex.readLock {
      val categoryEntries = con.searchSub(rudderDit.ACTIVE_TECHNIQUES_LIB.dn,  EQ(A_TECHNIQUE_CATEGORY_UUID, id.value), attributes:_*)
      categoryEntries.size match {
        case 0 => Empty
        case 1 => Full(categoryEntries(0))
        case _ => Failure("Error, the directory contains multiple occurrence of category with id %s. DN: %s".format(id, categoryEntries.map( _.dn).mkString("; ")))
      }
    }
  }

  /**
   * Get the direct parent of the given category.
   * Return empty for root of the hierarchy, fails if the category
   * is not in the repository
   */
  def getParentActiveTechniqueCategory(id:ActiveTechniqueCategoryId) : Box[ActiveTechniqueCategory] = {
    for {
      con                 <- ldap
      locked              <- userLibMutex.readLock
      categoryEntry       <- getCategoryEntry(con, id, "1.1") ?~! "Entry with ID '%s' was not found".format(id)
      parentCategoryEntry <- con.get(categoryEntry.dn.getParent)
      parentCategory      <- mapper.entry2ActiveTechniqueCategory(parentCategoryEntry) ?~! "Error when transforming LDAP entry %s into an active technique category".format(parentCategoryEntry)
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
  def getParentsForActiveTechniqueCategory(id:ActiveTechniqueCategoryId) : Box[List[ActiveTechniqueCategory]] = {
    userLibMutex.readLock {
      //TODO : LDAPify that, we can have the list of all DN from id to root at the begining (just dn.getParent until rudderDit.ACTIVE_TECHNIQUES_LIB.dn)
      getActiveTechniqueLibrary.flatMap { root =>
        if(id == root.id) Full(Nil)
        else getParentActiveTechniqueCategory(id) match {
          case Full(parent) => getParentsForActiveTechniqueCategory(parent.id).map(parents => parent :: parents)
          case e:EmptyBox => e
        }
      }
    }
  }

  def getParentsForActiveTechnique(id:ActiveTechniqueId) : Box[ActiveTechniqueCategory] = {
    userLibMutex.readLock { for {
      con <- ldap
      uptEntries = con.searchSub(rudderDit.ACTIVE_TECHNIQUES_LIB.dn, EQ(A_ACTIVE_TECHNIQUE_UUID, id.value))
      uptEntry <- uptEntries.size match {
        case 0 => Failure("Can not find active technique with id '%s'".format(id))
        case 1 => Full(uptEntries(0))
        case _ => Failure("Found more than one active technique with id '%s' : %s".format(id, uptEntries.map(_.dn).mkString("; ")))
      }
      category <- getActiveTechniqueCategory(mapper.dn2ActiveTechniqueCategoryId(uptEntry.dn.getParent))
    } yield {
      category
    } }
  }



  def getActiveTechniqueByCategory(includeSystem:Boolean = false) : Box[SortedMap[List[ActiveTechniqueCategoryId], CategoryWithActiveTechniques]] = {
    for {
      locked       <- userLibMutex.readLock
      allCats      <- getAllActiveTechniqueCategories(includeSystem)
      catsWithUPs  <- sequence(allCats) { ligthCat =>
                        for {
                          category <- getActiveTechniqueCategory(ligthCat.id)
                          parents  <- getParentsForActiveTechniqueCategory(category.id)
                          upts     <- sequence(category.items) { uactiveTechniqueId => getActiveTechnique(uactiveTechniqueId) }
                        } yield {
                          ( (category.id :: parents.map(_.id)).reverse, CategoryWithActiveTechniques(category, upts.toSet))
                        }
                      }
    } yield {
      implicit val ordering = ActiveTechniqueCategoryOrdering
      SortedMap[List[ActiveTechniqueCategoryId], CategoryWithActiveTechniques]() ++ catsWithUPs
    }
  }

  def getActiveTechnique(id: ActiveTechniqueId): Box[ActiveTechnique] = {
    userLibMutex.readLock {
      getActiveTechnique[ActiveTechniqueId](id, { id => EQ(A_ACTIVE_TECHNIQUE_UUID, id.value) } )
    }
  }

  def getActiveTechnique(name: TechniqueName): Box[ActiveTechnique] = {
    userLibMutex.readLock {
      this.getActiveTechnique[TechniqueName](name, { name => EQ(A_TECHNIQUE_UUID, name.value) } )
    }
  }

  /**
   * Add directives ids for the given active technique which must
   * be mapped to the given dn in LDAP directory accessible by con
   */
  private[this] def addDirectives(activeTechnique:ActiveTechnique, dn:DN, con:RoLDAPConnection) : ActiveTechnique = {
    val piEntries = con.searchOne(dn, EQ(A_OC, OC_DIRECTIVE), "objectClass")
    activeTechnique.copy(
      directives = piEntries.map(e => mapper.dn2LDAPRuleID(e.dn)).toList
    )
  }

  private[this] def getActiveTechnique[ID](id: ID, filter: ID => Filter): Box[ActiveTechnique] = {
    for {
      con <- ldap
      uptEntry <- getUPTEntry(con, id, filter) ?~! "Can not find user policy entry in LDAP based on filter %s".format(filter(id))
      activeTechnique <- mapper.entry2ActiveTechnique(uptEntry) ?~! "Error when mapping active technique entry to its entity. Entry: %s".format(uptEntry)
    } yield {
      addDirectives(activeTechnique,uptEntry.dn,con)
    }
  }

  def getUPTEntry(con:RoLDAPConnection, id:ActiveTechniqueId, attributes:String*) : Box[LDAPEntry] = {
    userLibMutex.readLock {
      getUPTEntry[ActiveTechniqueId](con, id, { id => EQ(A_ACTIVE_TECHNIQUE_UUID, id.value) }, attributes:_*)
    }
  }

  /**
   * Look in the subtree with root=active technique library
   * for and entry with the given id.
   * We expect at most one result, more is a Failure
   */
  def getUPTEntry[ID](
      con:RoLDAPConnection,
      id:ID,
      filter: ID => Filter,
      attributes:String*) : Box[LDAPEntry] = {
    val uptEntries = con.searchSub(rudderDit.ACTIVE_TECHNIQUES_LIB.dn, filter(id), attributes:_*)
    uptEntries.size match {
      case 0 => Empty
      case 1 => Full(uptEntries(0))
      case _ => Failure("Error, the directory contains multiple occurrence of active technique with ID %s. DNs involved: %s".format(id, uptEntries.map( _.dn).mkString("; ")))
    }
  }

  def activeTechniqueBreadCrump(id: ActiveTechniqueId): Box[List[ActiveTechniqueCategory]] = {
    //find the active technique entry for that id, and from that, build the parent bread crump
    userLibMutex.readLock { for {
      cat  <- getParentsForActiveTechnique(id)
      cats <- getParentsForActiveTechniqueCategory(cat.id)
    } yield {
      cat :: cats
    } }
  }




  def getFullDirectiveLibrary() : Box[FullActiveTechniqueCategory] = {
    //data structure to holds all relation between objects
    case class AllMaps(
        categories: Map[ActiveTechniqueCategoryId, ActiveTechniqueCategory]
      , activeTechiques: Map[ActiveTechniqueId, ActiveTechnique]
      , categoriesByCategory: Map[ActiveTechniqueCategoryId, List[ActiveTechniqueCategoryId]]
      , activeTechniquesByCategory: Map[ActiveTechniqueCategoryId, List[ActiveTechniqueId]]
      , directivesByActiveTechnique: Map[ActiveTechniqueId, List[Directive]]
    )

    //here, active technique is expected to have an empty list of children
    def fromActiveTechnique(at: ActiveTechnique, maps:AllMaps) = {
      FullActiveTechnique(
          id = at.id
        , techniqueName = at.techniqueName
        , techniques = techniqueRepository.getByName(at.techniqueName)
        , acceptationDatetimes = at.acceptationDatetimes
        , directives = maps.directivesByActiveTechnique.getOrElse(at.id, Nil)
        , isEnabled = at.isEnabled
        , isSystem = at.isSystem
      )
    }

    //here, subcaterories and active technique are expexcted to be empty
    def fromCategory(atcId:ActiveTechniqueCategoryId, maps:AllMaps, activeTechniques:Map[ActiveTechniqueId, FullActiveTechnique]): FullActiveTechniqueCategory = {
      def getCat(id:ActiveTechniqueCategoryId) = maps.categories.getOrElse(id, throw new IllegalArgumentException(s"Missing categories with id ${atcId} in the list of available categories"))
      val atc = getCat(atcId)

      FullActiveTechniqueCategory(
          id = atc.id
        , name = atc.name
        , description = atc.description
        , subCategories = maps.categoriesByCategory.getOrElse(atc.id, Nil) .map { id =>
            fromCategory(id, maps, activeTechniques)
          }
        , activeTechniques = maps.activeTechniquesByCategory.getOrElse(atc.id, Nil).map { atId =>
            activeTechniques.getOrElse(atId, throw new IllegalArgumentException(s"Missing active technique with id ${atId} in the list of available active techniques"))
          }
        , isSystem = atc.isSystem
      )
    }

    /*
     * strategy: load the full subtree from the root category id,
     * then process entrie mapping them to their light version,
     * then call fromCategory on the root (we know its id).
     */


    val emptyAll = AllMaps(Map(), Map(), Map(), Map(), Map())
    import rudderDit.ACTIVE_TECHNIQUES_LIB._

    def mappingError(current:AllMaps, e:LDAPEntry, eb:EmptyBox) : AllMaps = {
      val error = eb ?~! s"Error when mapping entry with DN '${e.dn.toString}' from directive library"
      logger.warn(error.messageChain)
      current
    }

    for {
      con     <- ldap
      entries <- userLibMutex.readLock( con.getTree(rudderDit.ACTIVE_TECHNIQUES_LIB.dn) ) ?~! "The root category of the user library of techniques seems to be missing in LDAP directory. Please check its content"
    } yield {
      val allMaps =  (emptyAll /: entries.toSeq) { case (current, e) =>
         if(isACategory(e)) {
           mapper.entry2ActiveTechniqueCategory(e) match {
             case Full(category) =>
               //for categories other than root, add it in the list
               //of its parent subcategories
               val updatedSubCats = if(e.dn == rudderDit.ACTIVE_TECHNIQUES_LIB.dn) {
                 current.categoriesByCategory
               } else {
                 val catId = mapper.dn2ActiveTechniqueCategoryId(e.dn.getParent)
                 val subCats = category.id :: current.categoriesByCategory.getOrElse(catId, Nil)
                 current.categoriesByCategory + (catId -> subCats)
               }
               current.copy(
                   categories = current.categories + (category.id -> category)
                 , categoriesByCategory =  updatedSubCats
               )
             case eb:EmptyBox => mappingError(current, e, eb)
           }
         } else if(isAnActiveTechnique(e)) {
           mapper.entry2ActiveTechnique(e) match {
             case Full(at) =>
               val catId = mapper.dn2ActiveTechniqueCategoryId(e.dn.getParent)
               val atsForCatId = at.id :: current.activeTechniquesByCategory.getOrElse(catId, Nil)
               current.copy(
                   activeTechiques = current.activeTechiques + (at.id -> at)
                 , activeTechniquesByCategory = current.activeTechniquesByCategory + (catId -> atsForCatId)
               )
             case eb:EmptyBox => mappingError(current, e, eb)
           }
         } else if(isADirective(e)) {
           mapper.entry2Directive(e) match {
             case Full(dir) =>
               val atId = mapper.dn2ActiveTechniqueId(e.dn.getParent)
               val dirsForAt = dir :: current.directivesByActiveTechnique.getOrElse(atId, Nil)
               current.copy(
                   directivesByActiveTechnique = current.directivesByActiveTechnique + (atId -> dirsForAt)
               )
             case eb:EmptyBox => mappingError(current, e, eb)
           }
         } else {
           //log error, continue
           logger.warn(s"Entry with DN '${e.dn}' was ignored because it is of an unknow type. Known types are categories, active techniques, directives")
           current
         }
      }

      val fullActiveTechniques = allMaps.activeTechiques.map{ case (id,at) => (id -> fromActiveTechnique(at, allMaps)) }.toMap

      fromCategory(ActiveTechniqueCategoryId(rudderDit.ACTIVE_TECHNIQUES_LIB.rdnValue._1), allMaps, fullActiveTechniques)
    }
  }


}

class WoLDAPDirectiveRepository(
    roDirectiveRepos              : RoLDAPDirectiveRepository
  , ldap                          : LDAPConnectionProvider[RwLDAPConnection]
  , diffMapper                    : LDAPDiffMapper
  , actionLogger                  : EventLogRepository
  , uuidGen                       : StringUuidGenerator
  , gitPiArchiver                 : GitDirectiveArchiver
  , gitATArchiver                 : GitActiveTechniqueArchiver
  , gitCatArchiver                : GitActiveTechniqueCategoryArchiver
  , personIdentService            : PersonIdentService
  , autoExportOnModify            : Boolean
) extends WoDirectiveRepository with Loggable {


  import roDirectiveRepos.{ ldap => roLdap, _ }

  import scala.collection.mutable.{Map => MutMap}
  import scala.xml.Text


  /**
   * Save the given directive into given active technique
   * If the directive is already present in the system but not
   * in the given category, raise an error.
   * If the directive is already in the given technique,
   * update the directive.
   * If the directive is not in the system, add it.
   *
   * Returned the saved WBUserDirective
   */
  private[this] def internalSaveDirective(inActiveTechniqueId:ActiveTechniqueId,directive:Directive, modId: ModificationId, actor:EventActor, reason:Option[String], systemCall:Boolean) : Box[Option[DirectiveSaveDiff]] = {
    for {
      con         <- ldap
      uptEntry    <- getUPTEntry(con, inActiveTechniqueId, "1.1") ?~! "Can not find the User Policy Entry with id %s to add directive %s".format(inActiveTechniqueId, directive.id)
      canAdd      <- { //check if the directive already exists elsewhere
                        getDirectiveEntry(con, directive.id) match {
                          case f:Failure => f
                          case Empty => Full(None)
                          case Full(otherPi) =>
                            if(otherPi.dn.getParent == uptEntry.dn) Full(Some(otherPi))
                            if(otherPi.dn.getParent == uptEntry.dn) {
                              mapper.entry2Directive(otherPi).flatMap { x =>
                                (x.isSystem, systemCall) match {
                                  case (true, false) => Failure("System directive '%s' (%s) can't be updated".format(x.name, x.id.value))
                                  case (false, true) => Failure("Non-system directive can not be updated with that method")
                                  case _ => Full(Some(otherPi))
                                }
                              }
                            }

                            else Failure("An other directive with the id %s exists in an other category that the one with id %s : %s".format(directive.id, inActiveTechniqueId, otherPi.dn))
                        }
                     }
      // We have to keep the old rootSection to generate the event log
      oldRootSection     =  {
        getDirective(directive.id) match {
          case Full(oldDirective) => getActiveTechnique(directive.id) match {
            case Full(oldActiveTechnique) =>
              val oldTechniqueId     =  TechniqueId(oldActiveTechnique.techniqueName,oldDirective.techniqueVersion)
              techniqueRepository.get(oldTechniqueId).map(_.rootSection)
            case eb:EmptyBox =>
              // Directory did not exist before, this is a Rule addition. but this should not happen So reporting an error
              logger.error("The rule did not existe before")
              None
          }
          case eb:EmptyBox =>
            // Directory did not exist before, this is a Rule addition.
            None
        }
      }
      nameIsAvailable    <- if (directiveNameExists(con, directive.name, directive.id))
                              Failure("Cannot set directive with name \"%s\" : this name is already in use.".format(directive.name))
                            else
                              Full(Unit)
      piEntry            =  mapper.userDirective2Entry(directive, uptEntry.dn)
      result             <- userLibMutex.writeLock { con.save(piEntry, true) }
      //for log event - perhaps put that elsewhere ?
      activeTechnique    <- getActiveTechnique(inActiveTechniqueId) ?~! "Can not find the User Policy Entry with id %s to add directive %s".format(inActiveTechniqueId, directive.id)
      activeTechniqueId  =  TechniqueId(activeTechnique.techniqueName,directive.techniqueVersion)
      technique          <- Box(techniqueRepository.get(activeTechniqueId)) ?~! "Can not find the technique with ID '%s'".format(activeTechniqueId.toString)
      optDiff            <- diffMapper.modChangeRecords2DirectiveSaveDiff(
                                   technique.id.name
                                 , technique.rootSection
                                 , piEntry.dn
                                 , canAdd
                                 , result
                                 , oldRootSection
                               ) ?~! "Error when processing saved modification to log them"
      eventLogged <- optDiff match {
                       case None => Full("OK")
                       case Some(diff:AddDirectiveDiff) =>
                         actionLogger.saveAddDirective(
                             modId
                           , principal = actor
                           , addDiff = diff
                           , varsRootSectionSpec = technique.rootSection
                           , reason = reason
                         )
                       case Some(diff:ModifyDirectiveDiff) =>
                         actionLogger.saveModifyDirective(
                             modId
                           , principal = actor
                           , modifyDiff = diff
                           , reason = reason
                         )
                     }
      autoArchive <- if(autoExportOnModify && optDiff.isDefined && !directive.isSystem) {
                       for {
                         parents  <- activeTechniqueBreadCrump(activeTechnique.id)
                         commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                         archived <- gitPiArchiver.archiveDirective(directive, technique.id.name, parents.map( _.id), technique.rootSection, Some((modId,commiter, reason)))
                       } yield archived
                     } else Full("ok")
    } yield {
      optDiff
    }
  }

  override def saveDirective(inActiveTechniqueId:ActiveTechniqueId,directive:Directive, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[Option[DirectiveSaveDiff]] = {
    internalSaveDirective(inActiveTechniqueId, directive, modId, actor, reason, false)
  }

  override def saveSystemDirective(inActiveTechniqueId:ActiveTechniqueId,directive:Directive, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[Option[DirectiveSaveDiff]] = {
    internalSaveDirective(inActiveTechniqueId, directive, modId, actor, reason, true)
  }


  private[this] def directiveNameExists(con:RoLDAPConnection, name : String, id:DirectiveId) : Boolean = {
    val filter = AND(AND(IS(OC_DIRECTIVE), EQ(A_NAME,name), NOT(EQ(A_DIRECTIVE_UUID, id.value))))
    con.searchSub(rudderDit.ACTIVE_TECHNIQUES_LIB.dn, filter).size match {
      case 0 => false
      case 1 => true
      case _ => logger.error("More than one directive has %s name".format(name)); true
    }
  }


  /**
   * Delete a directive.
   * No dependency check are done, and so you will have to
   * delete dependent rule (or other items) by
   * hand if you want.
   */
  override def delete(id:DirectiveId, modId: ModificationId, actor:EventActor, reason:Option[String]) : Box[DeleteDirectiveDiff] = {
    for {
      con             <- ldap
      entry           <- getDirectiveEntry(con, id)
      //for logging, before deletion
      directive       <- mapper.entry2Directive(entry)
      activeTechnique <- getActiveTechnique(id) ?~! "Can not find the User Policy Temple Entry for directive %s".format(id)
      technique       <- techniqueRepository.get(TechniqueId(activeTechnique.techniqueName,directive.techniqueVersion))
      //delete
      deleted         <- userLibMutex.writeLock { con.delete(entry.dn) }
      diff            =  DeleteDirectiveDiff(technique.id.name, directive)
      loggedAction    <- actionLogger.saveDeleteDirective(
                          modId, principal = actor, deleteDiff = diff, varsRootSectionSpec = technique.rootSection, reason = reason
                      )
      autoArchive     <- if (autoExportOnModify && deleted.size > 0 && !directive.isSystem) {
                           for {
                             parents  <- activeTechniqueBreadCrump(activeTechnique.id)
                             commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                             archived <- gitPiArchiver.deleteDirective(directive.id, activeTechnique.techniqueName, parents.map( _.id), Some(modId,commiter, reason))
                           } yield archived
                         } else Full("ok")
    } yield {
      diff
    }
  }

  /**
   * Check if the given parent category has a child with the given name (exact) and
   * an id different from given id
   */
  private[this] def existsByName(con:RwLDAPConnection, parentDN:DN, subCategoryName:String, notId:String) : Boolean = {
    userLibMutex.readLock {
      con.searchOne(parentDN, AND(EQ(A_NAME,subCategoryName),NOT(EQ(A_TECHNIQUE_CATEGORY_UUID,notId))), "1.1" ).nonEmpty
    }
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
  def addActiveTechniqueCategory(
      that:ActiveTechniqueCategory
    , into:ActiveTechniqueCategory //parent category
    , modId : ModificationId
    , actor: EventActor
    , reason: Option[String]
  ) : Box[ActiveTechniqueCategory] = {
    for {
      con                 <- ldap
      parentCategoryEntry <- getCategoryEntry(con, into.id, "1.1") ?~! "The parent category '%s' was not found, can not add".format(into.id)
      categoryEntry       =  mapper.activeTechniqueCategory2ldap(that,parentCategoryEntry.dn)
      canAddByName        <- if(existsByName(con,parentCategoryEntry.dn, that.name, that.id.value)) {
                               Failure("A category with that name already exists in that category: category names must be unique for a given level")
                             } else {
                               Full("Can add, no sub categorie with that name")
                             }
      result              <- userLibMutex.writeLock { con.save(categoryEntry, removeMissingAttributes = true) }
      autoArchive         <- if(autoExportOnModify && !result.isInstanceOf[LDIFNoopChangeRecord] && !that.isSystem) {
                               for {
                                 parents  <- getParentsForActiveTechniqueCategory(that.id)
                                 commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                                 archive  <- gitCatArchiver.archiveActiveTechniqueCategory(that,parents.map( _.id), Some(modId,commiter, reason))
                               } yield archive
                             } else Full("ok")
    } yield {
      addSubEntries(into, parentCategoryEntry.dn, con)
    }
  }

  /**
   * Update an existing technique category
   * Return the updated policy category
   */
  def saveActiveTechniqueCategory(category:ActiveTechniqueCategory, modId : ModificationId, actor: EventActor, reason: Option[String]) : Box[ActiveTechniqueCategory] = {
    for {
      con              <- ldap
      oldCategoryEntry <- getCategoryEntry(con, category.id, "1.1") ?~! "Entry with ID '%s' was not found".format(category.id)
      categoryEntry    =  mapper.activeTechniqueCategory2ldap(category,oldCategoryEntry.dn.getParent)
      canAddByName     <- if(categoryEntry.dn != rudderDit.ACTIVE_TECHNIQUES_LIB.dn && existsByName(con,categoryEntry.dn.getParent, category.name, category.id.value)) {
                            Failure("A category with that name already exists in that category: category names must be unique for a given level")
                          } else {
                            Full("Can add, no sub categorie with that name")
                          }
      result           <- userLibMutex.writeLock { con.save(categoryEntry, removeMissingAttributes = true) }
      updated          <- getActiveTechniqueCategory(category.id)
      autoArchive      <- if(autoExportOnModify && !result.isInstanceOf[LDIFNoopChangeRecord] && !category.isSystem) {
                            for {
                              parents  <- getParentsForActiveTechniqueCategory(category.id)
                              commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                              archive  <- gitCatArchiver.archiveActiveTechniqueCategory(updated,parents.map( _.id), Some(modId,commiter, reason))
                            } yield archive
                          } else Full("ok")
    } yield {
      updated
    }
  }



  def delete(id:ActiveTechniqueCategoryId, modId : ModificationId, actor:EventActor, reason: Option[String], checkEmpty:Boolean = true) : Box[ActiveTechniqueCategoryId] = {
    for {
      con <-ldap
      deleted <- {
        getCategoryEntry(con, id, "1.1") match {
          case Full(entry) =>
            for {
              category    <- mapper.entry2ActiveTechniqueCategory(entry)
              parents     <- if(autoExportOnModify) {
                               getParentsForActiveTechniqueCategory(id)
                             } else Full(Nil)
              ok          <- try {
                               userLibMutex.writeLock { con.delete(entry.dn, recurse = !checkEmpty) ?~! "Error when trying to delete category with ID '%s'".format(id) }
                             } catch {
                               case e:LDAPException if(e.getResultCode == ResultCode.NOT_ALLOWED_ON_NONLEAF) => Failure("Can not delete a non empty category")
                               case e:Exception => Failure("Exception when trying to delete category with ID '%s'".format(id), Full(e), Empty)
                             }
              autoArchive <- (if(autoExportOnModify && ok.size > 0 && !category.isSystem) {
                               for {
                                 commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                                 archive  <- gitCatArchiver.deleteActiveTechniqueCategory(id,parents.map( _.id), Some(modId,commiter, reason))
                               } yield {
                                 archive
                               }
                             } else Full("ok") )  ?~! "Error when trying to archive automatically the category deletion"
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
  def move(categoryId:ActiveTechniqueCategoryId, intoParent:ActiveTechniqueCategoryId, modId : ModificationId, actor: EventActor, reason: Option[String]) : Box[ActiveTechniqueCategoryId] = {
      for {
        con            <- ldap
        oldParents     <- if(autoExportOnModify) {
                            getParentsForActiveTechniqueCategory(categoryId)
                          } else Full(Nil)
        categoryEntry  <- getCategoryEntry(con, categoryId, A_NAME)
        newParentEntry <- getCategoryEntry(con, intoParent, "1.1")
        moveAuthorised <- if(newParentEntry.dn.isDescendantOf(categoryEntry.dn, true)) {
                            Failure("Can not move a category to itself or one of its children")
                          } else Full("Succes")
        canAddByName   <- (categoryEntry(A_TECHNIQUE_CATEGORY_UUID) , categoryEntry(A_NAME)) match {
                            case (Some(id),Some(name)) =>
                              if(existsByName(con, newParentEntry.dn, name, id)) {
                                Failure("A category with that name already exists in that category: category names must be unique for a given level")
                              } else {
                                Full("Can add, no sub categorie with that name")
                              }
                            case _ => Failure("Can not find the category entry name for category with ID %s. Name is needed to check unicity of categories by level")
                          }
        result         <- userLibMutex.writeLock { con.move(categoryEntry.dn, newParentEntry.dn) }
        category       <- getActiveTechniqueCategory(categoryId)
        autoArchive    <- (if(autoExportOnModify && !result.isInstanceOf[LDIFNoopChangeRecord] && !category.isSystem ) {
                            for {
                              newCat   <- getActiveTechniqueCategory(categoryId)
                              parents  <- getParentsForActiveTechniqueCategory(categoryId)
                              commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                              moved    <- gitCatArchiver.moveActiveTechniqueCategory(newCat, oldParents.map( _.id), parents.map( _.id), Some(modId,commiter, reason))
                            } yield {
                              moved
                            }
                          } else Full("ok") ) ?~! "Error when trying to archive automatically the category move"
      } yield {
        categoryId
      }
  }



  def addTechniqueInUserLibrary(
      categoryId   : ActiveTechniqueCategoryId
    , techniqueName: TechniqueName
    , versions     : Seq[TechniqueVersion]
    , modId        : ModificationId
    , actor        : EventActor
    , reason: Option[String]
  ): Box[ActiveTechnique] = {
    //check if the technique is already in user lib, and if the category exists
    for {
      con                <- ldap
      noActiveTechnique  <- { //check that there is not already defined activeTechnique with such ref id
                              getUPTEntry[TechniqueName](
                                con, techniqueName,
                                { name => EQ(A_TECHNIQUE_UUID, name.value) },
                                "1.1") match {
                                  case Empty => Full("ok")
                                  case Full(uptEntry) => Failure("Can not add a technique with id %s in user library. active technique %s is already defined with such a reference technique.".format(techniqueName,uptEntry.dn))
                                  case f:Failure => f
                              }
                            }
      categoryEntry      <- getCategoryEntry(con, categoryId, "1.1") ?~! "Category entry with ID '%s' was not found".format(categoryId)
      newActiveTechnique =  ActiveTechnique(ActiveTechniqueId(uuidGen.newUuid),techniqueName, versions.map(x => x -> DateTime.now()).toMap)
      uptEntry           =  mapper.activeTechnique2Entry(newActiveTechnique,categoryEntry.dn)
      result             <- userLibMutex.writeLock { con.save(uptEntry, true) }
      // a new active technique is never system, see constructor call, using defvault value,
      // maybe we should check in its caller is the technique is system or not
      autoArchive        <- if(autoExportOnModify && !result.isInstanceOf[LDIFNoopChangeRecord]) {
                              for {
                                parents  <- activeTechniqueBreadCrump(newActiveTechnique.id)
                                commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                                archive  <- gitATArchiver.archiveActiveTechnique(newActiveTechnique, parents.map( _.id), Some(modId,commiter, reason))
                              } yield archive
                            } else Full("ok")
    } yield {
      newActiveTechnique
    }
  }


  /**
   * Move a technique to a new category.
   * Failure if the given technique or category
   * does not exists.
   *
   */
  def move(uactiveTechniqueId:ActiveTechniqueId, newCategoryId:ActiveTechniqueCategoryId, modId: ModificationId, actor: EventActor, reason: Option[String]) : Box[ActiveTechniqueId] = {
     for {
      con                  <- ldap
      oldParents           <- if(autoExportOnModify) {
                                activeTechniqueBreadCrump(uactiveTechniqueId)
                              } else Full(Nil)
      activeTechnique      <- getUPTEntry(con, uactiveTechniqueId, "1.1") ?~! "Can not move non existing template in use library with ID %s".format(uactiveTechniqueId)
      newCategory          <- getCategoryEntry(con, newCategoryId, "1.1") ?~! "Can not move template with ID %s into non existing category of user library %s".format(uactiveTechniqueId, newCategoryId)
      moved                <- userLibMutex.writeLock { con.move(activeTechnique.dn, newCategory.dn) ?~! "Error when moving technique %s to category %s".format(uactiveTechniqueId, newCategoryId) }
      movedActiveTechnique <- getActiveTechnique(uactiveTechniqueId)
      autoArchive          <- ( if(autoExportOnModify && !moved.isInstanceOf[LDIFNoopChangeRecord] && !movedActiveTechnique.isSystem) {
                                for {
                                  parents  <- activeTechniqueBreadCrump(uactiveTechniqueId)
                                  commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                                  moved    <- gitATArchiver.moveActiveTechnique(movedActiveTechnique, oldParents.map( _.id), parents.map( _.id), Some(modId, commiter, reason))
                                } yield {
                                  moved
                                }
                              } else Full("ok") ) ?~! "Error when trying to archive automatically the technique move"
    } yield {
      uactiveTechniqueId
    }
  }

  /**
   * Set the status of the technique to the new value
   */
  def changeStatus(uactiveTechniqueId:ActiveTechniqueId, status:Boolean, modId: ModificationId, actor: EventActor, reason: Option[String]) : Box[ActiveTechniqueId] = {
    for {
      con             <- ldap
      oldTechnique    <- getUPTEntry(con, uactiveTechniqueId)
      activeTechnique <- getUPTEntry(con, uactiveTechniqueId)
      saved           <- {
                           activeTechnique +=! (A_IS_ENABLED, status.toLDAPString)
                           userLibMutex.writeLock { con.save(activeTechnique) }
                         }
      optDiff         <- diffMapper.modChangeRecords2TechniqueDiff(oldTechnique, saved) ?~!
                           "Error when mapping technique '%s' update to an diff: %s".
                             format(uactiveTechniqueId.value, saved)
      loggedAction    <- optDiff match {
                           case None => Full("OK")
                           case Some(diff) => actionLogger.saveModifyTechnique(modId, principal = actor, modifyDiff = diff, reason = reason)
                         }
      newactiveTechnique <- getActiveTechnique(uactiveTechniqueId)
      autoArchive     <- if(autoExportOnModify && !saved.isInstanceOf[LDIFNoopChangeRecord] && ! newactiveTechnique.isSystem) {
                           for {
                             parents  <- activeTechniqueBreadCrump(uactiveTechniqueId)
                             commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                             archive  <- gitATArchiver.archiveActiveTechnique(newactiveTechnique, parents.map( _.id), Some(modId, commiter, reason))
                           } yield archive
                         } else Full("ok")
    } yield {
      uactiveTechniqueId
    }
  }

  def setAcceptationDatetimes(uactiveTechniqueId:ActiveTechniqueId, datetimes: Map[TechniqueVersion,DateTime], modId: ModificationId, actor: EventActor, reason: Option[String]) : Box[ActiveTechniqueId] = {
    for {
      con             <- ldap
      activeTechnique <- getUPTEntry(con, uactiveTechniqueId, A_ACCEPTATION_DATETIME)
      saved           <- {
                           val oldAcceptations = mapper.unserializeAcceptations(activeTechnique(A_ACCEPTATION_DATETIME).getOrElse(""))
                           val json = Printer.compact(JsonAST.render(mapper.serializeAcceptations(oldAcceptations ++ datetimes)))
                           activeTechnique.+=!(A_ACCEPTATION_DATETIME, json)
                           userLibMutex.writeLock { con.save(activeTechnique) }
                         }
      newActiveTechnique  <- getActiveTechnique(uactiveTechniqueId)
      autoArchive         <- if(autoExportOnModify && !saved.isInstanceOf[LDIFNoopChangeRecord] && !newActiveTechnique.isSystem) {
                               for {
                                 parents <- activeTechniqueBreadCrump(uactiveTechniqueId)
                                 commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                                 archive <- gitATArchiver.archiveActiveTechnique(newActiveTechnique, parents.map( _.id), Some(modId,commiter, reason))
                               } yield archive
                             } else Full("ok")
    } yield {
      uactiveTechniqueId
    }
  }


  /**
   * Delete the technique in user library.
   * If no such element exists, it is a success.
   */
  def delete(uactiveTechniqueId:ActiveTechniqueId, modId: ModificationId, actor: EventActor, reason: Option[String]) : Box[ActiveTechniqueId] = {
     for {
      con                <- ldap
      oldParents         <- if(autoExportOnModify) {
                              activeTechniqueBreadCrump(uactiveTechniqueId)
                            } else Full(Nil)
      activeTechnique    <- getUPTEntry(con, uactiveTechniqueId, A_TECHNIQUE_UUID)
      ldapEntryTechnique <- getUPTEntry(con, uactiveTechniqueId)
      oldTechnique       <- mapper.entry2ActiveTechnique(ldapEntryTechnique)
      deleted            <- userLibMutex.writeLock { con.delete(activeTechnique.dn, false) }
      diff               =  DeleteTechniqueDiff(oldTechnique)
      loggedAction       <- actionLogger.saveDeleteTechnique(modId, principal = actor, deleteDiff = diff, reason = reason)
      autoArchive        <- (if(autoExportOnModify && deleted.size > 0 && !oldTechnique.isSystem) {
                               for {
                                 ptName   <- Box(activeTechnique(A_TECHNIQUE_UUID)) ?~! "Missing required reference technique name"
                                 commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                                 res      <- gitATArchiver.deleteActiveTechnique(TechniqueName(ptName),oldParents.map( _.id), Some(modId,commiter, reason))
                               } yield res
                              } else Full("ok") )  ?~! "Error when trying to archive automatically the category deletion"
    } yield {
      uactiveTechniqueId
    }
  }

}