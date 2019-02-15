/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This file is part of Rudder.
*
* Rudder is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU General Public License version 3, the copyright holders add
* the following Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
* Public License version 3, when you create a Related Module, this
* Related Module is not considered as a part of the work and may be
* distributed under the license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* Rudder is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

*
*************************************************************************************
*/

package com.normation.rudder.repository.ldap

import cats.implicits._
import com.normation.cfclerk.domain.SectionSpec
import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.ldap.core.LDAPConstants.A_NAME
import com.normation.inventory.ldap.core.LDAPConstants.A_OC
import com.normation.ldap.ldif.LDIFNoopChangeRecord
import com.normation.ldap.sdk.BuildFilter._
import com.normation.ldap.sdk.LdapResult._
import com.normation.ldap.sdk._
import com.normation.rudder.domain.RudderLDAPConstants._
import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.RudderLDAPConstants
import com.normation.rudder.repository.ActiveTechniqueCategoryOrdering
import com.normation.rudder.repository.CategoryWithActiveTechniques
import com.normation.rudder.repository.EventLogRepository
import com.normation.rudder.repository.FullActiveTechnique
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.repository.GitActiveTechniqueArchiver
import com.normation.rudder.repository.GitActiveTechniqueCategoryArchiver
import com.normation.rudder.repository.GitDirectiveArchiver
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.WoDirectiveRepository
import com.normation.rudder.services.user.PersonIdentService
import com.normation.utils.Control.sequence
import com.normation.utils.StringUuidGenerator
import com.unboundid.ldap.sdk.DN
import com.unboundid.ldap.sdk.Filter
import net.liftweb.common._
import net.liftweb.json.JsonAST
import org.joda.time.DateTime

import scala.collection.immutable.SortedMap

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
  def getDirectiveEntry(con:RoLDAPConnection, id:DirectiveId, attributes:String*) : LdapResult[Option[LDAPEntry]] = {
    con.searchSub(rudderDit.ACTIVE_TECHNIQUES_LIB.dn,  EQ(A_DIRECTIVE_UUID, id.value), attributes:_*).flatMap(piEntries =>
      piEntries.size match {
        case 0 => None.success
        case 1 => Some(piEntries(0)).success
        case _ => LdapResultError.Consistancy(s"Error, the directory contains multiple occurrence of directive with id '${id.value}'. DN: ${piEntries.map( _.dn).mkString("; ")}").failure
      }
    )
  }

  private[this] def policyFilter(includeSystem: Boolean) = if(includeSystem) IS(OC_DIRECTIVE) else AND(IS(OC_DIRECTIVE), EQ(A_IS_SYSTEM,false.toLDAPString))

  /**
   * Try to find the directive with the given ID.
   * Empty: no directive with such ID
   * Full((parent,directive)) : found the directive (directive.id == directiveId) in given parent
   * Failure => an error happened.
   */
  override def getDirective(id:DirectiveId) : Box[Directive] = {
    (for {
      locked    <- userLibMutex.readLock
      con       <- ldap
      piEntry   <- getDirectiveEntry(con, id).notOptional(s"Can not find directive with id '${id.value}'")
      directive <- mapper.entry2Directive(piEntry).toLdapResult ?~! "Error when transforming LDAP entry into a directive for id %s. Entry: %s".format(id, piEntry)
    } yield {
      directive
    }).toBox
  }

  override def getDirectiveWithContext(directiveId:DirectiveId) : Box[(Technique, ActiveTechnique, Directive)] = {
    for {
      (activeTechnique
           , directive     ) <- this.getActiveTechniqueAndDirective(directiveId) ?~! s"Error when retrieving directive with ID ${directiveId.value}''"
      activeTechniqueId = TechniqueId(activeTechnique.techniqueName, directive.techniqueVersion)
      technique         <- Box(techniqueRepository.get(activeTechniqueId)) ?~! "No Technique with ID=%s found in reference library.".format(activeTechniqueId)
    } yield {
      (technique, activeTechnique, directive)
    }
  }

  def getActiveTechniqueAndDirectiveEntries(id:DirectiveId): Box[(LDAPEntry, LDAPEntry)] = {
    (for {
      locked   <- userLibMutex.readLock
      con      <- ldap
      piEntry  <- getDirectiveEntry(con, id).notOptional(s"Can not find directive with id '${id.value}'")
      uptEntry <- getUPTEntry(con, mapper.dn2ActiveTechniqueId(piEntry.dn.getParent), { id:ActiveTechniqueId => EQ(A_ACTIVE_TECHNIQUE_UUID, id.value) }).notOptional(
                    "Can not find Active Technique entry in LDAP")
    } yield {
      (uptEntry, piEntry)
    }).toBox
  }

  /**
   * Find the active technique for which the given directive is an instance.
   *
   * Return empty if no such directive is known,
   * fails if no active technique match the directive.
   */
  override def getActiveTechniqueAndDirective(id:DirectiveId) : Box[(ActiveTechnique, Directive)] = {
    for {
      (uptEntry, piEntry) <- getActiveTechniqueAndDirectiveEntries(id) ?~! "Can not find Active Technique entry in LDAP"
      activeTechnique     <- mapper.entry2ActiveTechnique(uptEntry) ?~! "Error when mapping active technique entry to its entity. Entry: %s".format(uptEntry)
      directive           <- mapper.entry2Directive(piEntry) ?~! "Error when transforming LDAP entry into a directive for id %s. Entry: %s".format(id, piEntry)
    } yield {
      (activeTechnique, directive)
    }
  }
  /**
   * Get directives for given technique.
   * A not known technique id is a failure.
   */
  override def getDirectives(activeTechniqueId:ActiveTechniqueId, includeSystem:Boolean = false) : Box[Seq[Directive]] = {
    (for {
      locked     <- userLibMutex.readLock
      con        <- ldap
      entries    <- getUPTEntry(con, activeTechniqueId, "1.1").flatMap {
                      case None    => Seq().success
                      case Some(e) => con.searchOne(e.dn, policyFilter(includeSystem))
                    }
      directives <- entries.toList.traverse { piEntry =>
                      (mapper.entry2Directive(piEntry) ?~! s"Error when transforming LDAP entry into a directive. Entry: ${piEntry}").toLdapResult
                    }
    } yield {
      directives
    }).toBox
  }

  /**
   * Return true if at least one directive exists in this category (or a sub category
   * of this category)
   */
  def containsDirective(id: ActiveTechniqueCategoryId) : Boolean = {
    (for {
      con      <- ldap
      locked   <- userLibMutex.readLock
      category <- getCategoryEntry(con, id).notOptional("Entry with ID '%s' was not found".format(id))
      results  <- con.searchSub(category.dn, IS(OC_DIRECTIVE), Seq[String]():_*)
    } yield {
      results.map( x => mapper.entry2Directive(x) ).flatten.size > 0
    }).fold(_ => false, identity)
  }

  /**
   * Root user categories
   */
  def getActiveTechniqueLibrary : Box[ActiveTechniqueCategory] = {
    (for {
      con               <- ldap
      locked            <- userLibMutex.readLock
      rootCategoryEntry <- con.get(rudderDit.ACTIVE_TECHNIQUES_LIB.dn) .notOptional("The root category of the user library of techniques seems " +
                                                                        "to be missing in LDAP directory. Please check its content")
      // look for sub category and technique
      rootCategory      <- (mapper.entry2ActiveTechniqueCategory(rootCategoryEntry) ?~! "Error when mapping from an LDAP entry to an active technique Category: %s".format(rootCategoryEntry)).toLdapResult
    } yield {
      addSubEntries(rootCategory,rootCategoryEntry.dn, con)
    }).toBox
  }


  /**
   * Return all categories (lightweight version, with no children)
   * @return
   */
  def getAllActiveTechniqueCategories(includeSystem:Boolean = false) : Box[Seq[ActiveTechniqueCategory]] = {
    (for {
      con               <- ldap
      locked            <- userLibMutex.readLock
      rootCategoryEntry <- con.get(rudderDit.ACTIVE_TECHNIQUES_LIB.dn).notOptional(
                             "The root category of the user library of techniques seems to be missing in LDAP directory. Please check its content"
                           )
      filter            =  if(includeSystem) IS(OC_TECHNIQUE_CATEGORY) else AND(NOT(EQ(A_IS_SYSTEM, true.toLDAPString)),IS(OC_TECHNIQUE_CATEGORY))
      entries           <- con.searchSub(rudderDit.ACTIVE_TECHNIQUES_LIB.dn, filter) //double negation is mandatory, as false may not be present
      allEntries        =  entries :+ rootCategoryEntry
      categories        <- allEntries.toList.traverse(entry => (mapper.entry2ActiveTechniqueCategory(entry) ?~! s"Error when transforming LDAP entry '${entry}' into an active technique category").toLdapResult)
    } yield {
      categories
    }).toBox
  }
  /**
   * Get an active technique by its ID
   */
  def getActiveTechniqueCategory(id:ActiveTechniqueCategoryId) : Box[ActiveTechniqueCategory] = {
    (for {
      con           <- ldap
      locked        <- userLibMutex.readLock
      categoryEntry <- getCategoryEntry(con, id).notOptional(s"Entry with ID '${id.value}' was not found")
      category      <- (mapper.entry2ActiveTechniqueCategory(categoryEntry) ?~! "Error when transforming LDAP entry %s into an active technique category".format(categoryEntry)).toLdapResult
    } yield {
      addSubEntries(category,categoryEntry.dn, con)
    }).toBox
  }

  /**
   * Find sub entries (children categories and active techniques for
   * the given category which MUST be mapped to an entry with the given
   * DN in the LDAP backend, accessible with the given connection.
   */
  def addSubEntries(category:ActiveTechniqueCategory, dn:DN, con:RoLDAPConnection) : ActiveTechniqueCategory = {
    val subEntries = (for {
      subEntries <- con.searchOne(dn, OR(EQ(A_OC, OC_TECHNIQUE_CATEGORY),EQ(A_OC, OC_ACTIVE_TECHNIQUE)), "objectClass")
    } yield {
      subEntries.partition(e => e.isA(OC_TECHNIQUE_CATEGORY))
    }).getOrElse((Seq(),Seq())) // ignore errors
    category.copy(
      children = subEntries._1.map(e => mapper.dn2ActiveTechniqueCategoryId(e.dn)).toList,
      items = subEntries._2.map(e => mapper.dn2ActiveTechniqueId(e.dn)).toList
    )
  }



  /**
   * Retrieve the category entry for the given ID, with the given connection
   */
  def getCategoryEntry(con:RoLDAPConnection, id:ActiveTechniqueCategoryId, attributes:String*) : LdapResult[Option[LDAPEntry]] = {
    for {
      _               <- userLibMutex.readLock
      categoryEntries <- con.searchSub(rudderDit.ACTIVE_TECHNIQUES_LIB.dn,  EQ(A_TECHNIQUE_CATEGORY_UUID, id.value), attributes:_*)
      entry           <- categoryEntries.size match {
                           case 0 => None.success
                           case 1 => Some(categoryEntries(0)).success
                           case _ => s"Error, the directory contains multiple occurrence of category with id '${id.value}}'. DN: ${categoryEntries.map( _.dn).mkString("; ")}".failure
                         }
    } yield {
      entry
    }
  }

  /**
   * Get the direct parent of the given category.
   * Return empty for root of the hierarchy, fails if the category
   * is not in the repository
   */
  def getParentActiveTechniqueCategory(id:ActiveTechniqueCategoryId) : Box[ActiveTechniqueCategory] = {
    (for {
      con                 <- ldap
      locked              <- userLibMutex.readLock
      categoryEntry       <- getCategoryEntry(con, id, "1.1").notOptional(s"Entry with ID '${id.value}' was not found")
      parentCategoryEntry <- con.get(categoryEntry.dn.getParent).notOptional(s"Entry with DN '${categoryEntry.dn.getParent}' was not found")
      parentCategory      <- (mapper.entry2ActiveTechniqueCategory(parentCategoryEntry) ?~! s"Error when transforming LDAP entry '${parentCategoryEntry}' into an active technique category").toLdapResult
    } yield {
      addSubEntries(parentCategory, parentCategoryEntry.dn, con)
    }).toBox
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
    (userLibMutex.readLock { for {
      con <- ldap
      uptEntries <- con.searchSub(rudderDit.ACTIVE_TECHNIQUES_LIB.dn, EQ(A_ACTIVE_TECHNIQUE_UUID, id.value))
      uptEntry <- uptEntries.size match {
        case 0 => s"Can not find active technique with id '${id.value}}'".failure
        case 1 => uptEntries(0).success
        case _ => s"Found more than one active technique with id '${id.value}' : ${uptEntries.map(_.dn).mkString("; ")}".failure
      }
      category <- getActiveTechniqueCategory(mapper.dn2ActiveTechniqueCategoryId(uptEntry.dn.getParent)).toLdapResult
    } yield {
      category
    } }).toBox
  }



  def getActiveTechniqueByCategory(includeSystem:Boolean = false) : Box[SortedMap[List[ActiveTechniqueCategoryId], CategoryWithActiveTechniques]] = {
    for {
      locked       <- userLibMutex.readLock
      allCats      <- getAllActiveTechniqueCategories(includeSystem)
      catsWithUPs  <- sequence(allCats) { ligthCat =>
                        for {
                          category <- getActiveTechniqueCategory(ligthCat.id)
                          parents  <- getParentsForActiveTechniqueCategory(category.id)
                          upts     <- sequence(category.items) { uactiveTechniqueId => getActiveTechnique(uactiveTechniqueId).flatMap { Box(_) } }
                        } yield {
                          ( (category.id :: parents.map(_.id)).reverse, CategoryWithActiveTechniques(category, upts.toSet))
                        }
                      }
    } yield {
      implicit val ordering = ActiveTechniqueCategoryOrdering
      SortedMap[List[ActiveTechniqueCategoryId], CategoryWithActiveTechniques]() ++ catsWithUPs
    }
  }

  def getActiveTechnique(id: ActiveTechniqueId): Box[Option[ActiveTechnique]] = {
    userLibMutex.readLock {
      getActiveTechnique[ActiveTechniqueId](id, { id => EQ(A_ACTIVE_TECHNIQUE_UUID, id.value) } )
    }
  }

  def getActiveTechnique(name: TechniqueName): Box[Option[ActiveTechnique]] = {
    userLibMutex.readLock {
      this.getActiveTechnique[TechniqueName](name, { name => EQ(A_TECHNIQUE_UUID, name.value) } )
    }
  }

  /**
   * Add directives ids for the given active technique which must
   * be mapped to the given dn in LDAP directory accessible by con
   */
  private[this] def addDirectives(activeTechnique:ActiveTechnique, dn:DN, con:RoLDAPConnection) : ActiveTechnique = {
    val piEntries = con.searchOne(dn, EQ(A_OC, OC_DIRECTIVE), "objectClass").getOrElse(Seq())
    activeTechnique.copy(
      directives = piEntries.map(e => mapper.dn2LDAPRuleID(e.dn)).toList
    )
  }

  private[this] def getActiveTechnique[ID](id: ID, filter: ID => Filter): Box[Option[ActiveTechnique]] = {
    (for {
      con        <- ldap
      uptEntries <- con.searchSub(rudderDit.ACTIVE_TECHNIQUES_LIB.dn, filter(id))
      res        <- uptEntries.size match {
                      case 0 => None.success
                      case 1 =>
                        (for {
                          activeTechnique <- mapper.entry2ActiveTechnique(uptEntries(0)) ?~! "Error when mapping active technique entry to its entity. Entry: %s".format(uptEntries(0))
                        } yield {
                          Some(addDirectives(activeTechnique,uptEntries(0).dn,con))
                        }).toLdapResult
                      case _ => s"Error, the directory contains multiple occurrence of active technique with ID '${id}'. DNs involved: ${uptEntries.map( _.dn).mkString("; ")}".failure
                    }
    } yield {
      res
    }).toBox
  }

  def getUPTEntry(con:RoLDAPConnection, id:ActiveTechniqueId, attributes:String*) : LdapResult[Option[LDAPEntry]] = {
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
      attributes:String*
  ) : LdapResult[Option[LDAPEntry]] = {
    for {
      uptEntries <- con.searchSub(rudderDit.ACTIVE_TECHNIQUES_LIB.dn, filter(id), attributes:_*)
      entry      <- uptEntries.size match {
                      case 0 => None.success
                      case 1 => Some(uptEntries(0)).success
                      case _ => LdapResultError.Consistancy(s"Error, the directory contains multiple occurrence of active " +
                                                            s"technique with ID '${id}'. DNs involved: ${uptEntries.map( _.dn).mkString("; ")}").failure
                    }
    } yield {
      entry
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
        , techniques = SortedMap( techniqueRepository.getByName(at.techniqueName).toSeq:_* )
        , acceptationDatetimes = SortedMap( at.acceptationDatetimes.toSeq:_* )
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

    (for {
      con     <- ldap
      entries <- userLibMutex.readLock( con.getTree(rudderDit.ACTIVE_TECHNIQUES_LIB.dn).notOptional("The root category of the user library of techniques seems to be missing in LDAP directory. Please check its content") )
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

      fromCategory(ActiveTechniqueCategoryId(rudderDit.ACTIVE_TECHNIQUES_LIB.rdnValue._1), allMaps, fullActiveTechniques.toMap)
    }).toBox
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


  import roDirectiveRepos._



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
    (for {
      con         <- ldap
      uptEntry    <- getUPTEntry(con, inActiveTechniqueId, "1.1").notOptional(s"Can not find the User Policy Entry with id '${inActiveTechniqueId.value}' to add directive '${directive.id.value}'")
      canAdd      <- getDirectiveEntry(con, directive.id).flatMap { //check if the directive already exists elsewhere
                       case None          => None.success
                       case Some(otherPi) =>
                            if(otherPi.dn.getParent == uptEntry.dn) {
                              (mapper.entry2Directive(otherPi).flatMap { x =>
                                (x.isSystem, systemCall) match {
                                  case (true, false) => Failure("System directive '%s' (%s) can't be updated".format(x.name, x.id.value))
                                  case (false, true) => Failure("Non-system directive can not be updated with that method")
                                  case _ => Full(Some(otherPi))
                                }
                              }).toLdapResult
                            } else s"An other directive with the id '${directive.id.value}' exists in an other category that the one with id '${inActiveTechniqueId.value}}': ${otherPi.dn}}".failure
                     }
      // We have to keep the old rootSection to generate the event log
      oldRootSection     =  {
        getActiveTechniqueAndDirective(directive.id) match {
          case Full((oldActiveTechnique, oldDirective)) =>
              val oldTechniqueId =  TechniqueId(oldActiveTechnique.techniqueName, oldDirective.techniqueVersion)
              techniqueRepository.get(oldTechniqueId).map(_.rootSection)
          case eb:EmptyBox =>
            // Directory did not exist before, this is a Rule addition.
            None
        }
      }
      nameIsAvailable    <- if (directiveNameExists(con, directive.name, directive.id))
                              s"Cannot set directive with name '${directive.name}}' : this name is already in use.".failure
                            else
                              Unit.success
      piEntry            =  mapper.userDirective2Entry(directive, uptEntry.dn)
      result             <- userLibMutex.writeLock { con.save(piEntry, true) }
      //for log event - perhaps put that elsewhere ?
      activeTechnique    <- getActiveTechnique(inActiveTechniqueId).toLdapResult.notOptional(s"Can not find the User Policy Entry with id '${inActiveTechniqueId.value}' to add directive '${directive.id.value}'")
      activeTechniqueId  =  TechniqueId(activeTechnique.techniqueName,directive.techniqueVersion)
      technique          <- techniqueRepository.get(activeTechniqueId).success.notOptional(s"Can not find the technique with ID '${activeTechniqueId.toString}'")
      optDiff            <- (diffMapper.modChangeRecords2DirectiveSaveDiff(
                                   technique.id.name
                                 , technique.rootSection
                                 , piEntry.dn
                                 , canAdd
                                 , result
                                 , oldRootSection
                               ) ?~! "Error when processing saved modification to log them").toLdapResult
      eventLogged <- (optDiff match {
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
                     }).toLdapResult
      autoArchive <- (if(autoExportOnModify && optDiff.isDefined && !directive.isSystem) {
                       for {
                         parents  <- activeTechniqueBreadCrump(activeTechnique.id)
                         commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                         archived <- gitPiArchiver.archiveDirective(directive, technique.id.name, parents.map( _.id), technique.rootSection, Some((modId, commiter, reason)))
                       } yield archived
                     } else Full("ok")).toLdapResult
    } yield {
      optDiff
    }).toBox
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
    (for {
      con             <- ldap
      //for logging, before deletion
      atd             <- getActiveTechniqueAndDirectiveEntries(id).toLdapResult
      (uptEntry
      , entry  )      =  atd
      activeTechnique <- (mapper.entry2ActiveTechnique(uptEntry) ?~! s"Error when mapping active technique entry to its entity. Entry: ${uptEntry}").toLdapResult
      directive       <- (mapper.entry2Directive(entry) ?~! s"Error when transforming LDAP entry into a directive for id '${id.value}'. Entry: ${entry}").toLdapResult
      okNotSystem     <- if(directive.isSystem) {
                           s"Error: system directive (like '${directive.name} [id: ${directive.id.value}])' can't be deleted".failure
                         } else {
                           "ok".success
                         }
      technique       <- techniqueRepository.get(TechniqueId(activeTechnique.techniqueName,directive.techniqueVersion)).success
      //delete
      deleted         <- userLibMutex.writeLock { con.delete(entry.dn) }
      diff            =  DeleteDirectiveDiff(activeTechnique.techniqueName, directive)
      loggedAction    <- { //we can have a missing technique if the technique was deleted but not its directive. In that case, make a fake root section
                           val rootSection = technique.map( _.rootSection).getOrElse(SectionSpec("Missing technique information"))
                           actionLogger.saveDeleteDirective(
                             modId, principal = actor, deleteDiff = diff, varsRootSectionSpec = rootSection, reason = reason
                           ).toLdapResult
                         }
      autoArchive     <- (if (autoExportOnModify && deleted.size > 0 && !directive.isSystem) {
                           for {
                             parents  <- activeTechniqueBreadCrump(activeTechnique.id)
                             commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                             archived <- gitPiArchiver.deleteDirective(directive.id, activeTechnique.techniqueName, parents.map( _.id), Some((modId, commiter, reason)))
                           } yield archived
                         } else Full("ok")).toLdapResult
    } yield {
      diff
    }).toBox
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
   * Fails if the parent category does not exist in user lib or
   * if it already contains that category, or a category of the
   * same name (name must be unique for a given level)
   *
   * return the modified parent category.
   */
  def addActiveTechniqueCategory(
      that:ActiveTechniqueCategory
    , into:ActiveTechniqueCategoryId //parent category
    , modId : ModificationId
    , actor: EventActor
    , reason: Option[String]
  ) : Box[ActiveTechniqueCategory] = {
    (for {
      con                 <- ldap
      parentCategoryEntry <- getCategoryEntry(con, into, "1.1").notOptional(s"The parent category '${into.value}' was not found, can not add")
      categoryEntry       =  mapper.activeTechniqueCategory2ldap(that,parentCategoryEntry.dn)
      canAddByName        <- if(existsByName(con,parentCategoryEntry.dn, that.name, that.id.value)) {
                               "A category with that name already exists in that category: category names must be unique for a given level".failure
                             } else {
                               "Can add, no sub categorie with that name".success
                             }
      result              <- userLibMutex.writeLock { con.save(categoryEntry, removeMissingAttributes = true) }
      autoArchive         <- (if(autoExportOnModify && !result.isInstanceOf[LDIFNoopChangeRecord] && !that.isSystem) {
                               for {
                                 parents  <- getParentsForActiveTechniqueCategory(that.id)
                                 commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                                 archive  <- gitCatArchiver.archiveActiveTechniqueCategory(that,parents.map( _.id), Some((modId, commiter, reason)))
                               } yield archive
                             } else Full("ok")).toLdapResult
      parentEntry         <- getCategoryEntry(con, into) ?~! "Entry with ID '%s' was not found".format(into)
      updatedParent       <- (mapper.entry2ActiveTechniqueCategory(categoryEntry) ?~! s"Error when transforming LDAP entry '${categoryEntry}' into an active technique category").toLdapResult
    } yield {
      updatedParent
    }).toBox
  }

  /**
   * Update an existing technique category
   * Return the updated policy category
   */
  def saveActiveTechniqueCategory(category:ActiveTechniqueCategory, modId : ModificationId, actor: EventActor, reason: Option[String]) : Box[ActiveTechniqueCategory] = {
    (for {
      con              <- ldap
      oldCategoryEntry <- getCategoryEntry(con, category.id, "1.1").notOptional(s"Entry with ID '${category.id.value}' was not found")
      categoryEntry    =  mapper.activeTechniqueCategory2ldap(category,oldCategoryEntry.dn.getParent)
      canAddByName     <- if(categoryEntry.dn != rudderDit.ACTIVE_TECHNIQUES_LIB.dn && existsByName(con,categoryEntry.dn.getParent, category.name, category.id.value)) {
                            "A category with that name already exists in that category: category names must be unique for a given level".failure
                          } else {
                            "Can add, no sub categorie with that name".success
                          }
      result           <- userLibMutex.writeLock { con.save(categoryEntry, removeMissingAttributes = true) }
      updated          <- getActiveTechniqueCategory(category.id).toLdapResult
      autoArchive      <- (if(autoExportOnModify && !result.isInstanceOf[LDIFNoopChangeRecord] && !category.isSystem) {
                            for {
                              parents  <- getParentsForActiveTechniqueCategory(category.id)
                              commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                              archive  <- gitCatArchiver.archiveActiveTechniqueCategory(updated,parents.map( _.id), Some((modId, commiter, reason)))
                            } yield archive
                          } else Full("ok")).toLdapResult
    } yield {
      updated
    }).toBox
  }



  def delete(id:ActiveTechniqueCategoryId, modId : ModificationId, actor:EventActor, reason: Option[String], checkEmpty:Boolean = true) : Box[ActiveTechniqueCategoryId] = {
    (for {
      con     <-ldap
      deleted <- getCategoryEntry(con, id).flatMap {
          case Some(entry) =>
            for {
              category    <- mapper.entry2ActiveTechniqueCategory(entry).toLdapResult
              parents     <- if(autoExportOnModify) {
                               getParentsForActiveTechniqueCategory(id).toLdapResult
                             } else Nil.success
              ok          <- userLibMutex.writeLock { con.delete(entry.dn, recurse = !checkEmpty) ?~! s"Error when trying to delete category with ID '${id.value}'" }
              autoArchive <- ((if(autoExportOnModify && ok.size > 0 && !category.isSystem) {
                               for {
                                 commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                                 archive  <- gitCatArchiver.deleteActiveTechniqueCategory(id,parents.map( _.id), Some((modId, commiter, reason)))
                               } yield {
                                 archive
                               }
                             } else Full("ok") )  ?~! "Error when trying to archive automatically the category deletion").toLdapResult
            } yield {
              id
            }
          case None => id.success
      }
    } yield {
      deleted
    }).toBox
  }

  /**
   * Move an existing category into a new one.
   * Both category to move and destination have to exists, else it is a failure.
   * The destination category can not be a child of the category to move.
   */
  def move(categoryId:ActiveTechniqueCategoryId, intoParent:ActiveTechniqueCategoryId, modId : ModificationId, actor: EventActor, reason: Option[String]) : Box[ActiveTechniqueCategoryId] = {
    (for {
      con            <- ldap
      oldParents     <- if(autoExportOnModify) {
                          getParentsForActiveTechniqueCategory(categoryId).toLdapResult
                        } else Nil.success
      categoryEntry  <- getCategoryEntry(con, categoryId, A_NAME).notOptional(s"Category was not found")
      newParentEntry <- getCategoryEntry(con, intoParent, "1.1").notOptional(s"New destination category '${intoParent.value}' was not found")
      moveAuthorised <- if(newParentEntry.dn.isDescendantOf(categoryEntry.dn, true)) {
                          "Can not move a category to itself or one of its children".failure
                        } else "Succes".success
      canAddByName   <- (categoryEntry(A_TECHNIQUE_CATEGORY_UUID) , categoryEntry(A_NAME)) match {
                          case (Some(id),Some(name)) =>
                            if(existsByName(con, newParentEntry.dn, name, id)) {
                              "A category with that name already exists in that category: category names must be unique for a given level".failure
                            } else {
                              "Can add, no sub categorie with that name".success
                            }
                          case _ => "Can not find the category entry name for category with ID %s. Name is needed to check unicity of categories by level".failure
                        }
      result         <- userLibMutex.writeLock { con.move(categoryEntry.dn, newParentEntry.dn) }
      category       <- getActiveTechniqueCategory(categoryId).toLdapResult
      autoArchive    <- ((if(autoExportOnModify && !result.isInstanceOf[LDIFNoopChangeRecord] && !category.isSystem ) {
                          for {
                            newCat   <- getActiveTechniqueCategory(categoryId)
                            parents  <- getParentsForActiveTechniqueCategory(categoryId)
                            commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                            moved    <- gitCatArchiver.moveActiveTechniqueCategory(newCat, oldParents.map( _.id), parents.map( _.id), Some((modId, commiter, reason)))
                          } yield {
                            moved
                          }
                        } else Full("ok") ) ?~! "Error when trying to archive automatically the category move").toLdapResult
    } yield {
      categoryId
    }).toBox
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
    (for {
      con                <- ldap
      noActiveTechnique  <- { //check that there is not already defined activeTechnique with such ref id
                              getUPTEntry[TechniqueName](
                                con, techniqueName,
                                { name => EQ(A_TECHNIQUE_UUID, name.value) },
                                "1.1") flatMap {
                                  case None => "ok".success
                                  case Some(uptEntry) => s"Can not add a technique with id '${techniqueName.toString}' in user library. active technique '${uptEntry.dn}}' is already defined with such a reference technique.".failure
                              }
                            }
      categoryEntry      <- getCategoryEntry(con, categoryId, "1.1").notOptional(s"Category entry with ID '${categoryId.value}' was not found")
      newActiveTechnique =  ActiveTechnique(ActiveTechniqueId(uuidGen.newUuid),techniqueName, versions.map(x => x -> DateTime.now()).toMap)
      uptEntry           =  mapper.activeTechnique2Entry(newActiveTechnique,categoryEntry.dn)
      result             <- userLibMutex.writeLock { con.save(uptEntry, true) }
      // a new active technique is never system, see constructor call, using defvault value,
      // maybe we should check in its caller is the technique is system or not
      autoArchive        <- (if(autoExportOnModify && !result.isInstanceOf[LDIFNoopChangeRecord]) {
                              for {
                                parents  <- activeTechniqueBreadCrump(newActiveTechnique.id)
                                commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                                archive  <- gitATArchiver.archiveActiveTechnique(newActiveTechnique, parents.map( _.id), Some((modId, commiter, reason)))
                              } yield archive
                            } else Full("ok")).toLdapResult
    } yield {
      newActiveTechnique
    }).toBox
  }


  /**
   * Move a technique to a new category.
   * Failure if the given technique or category
   * does not exist.
   *
   */
  def move(uactiveTechniqueId:ActiveTechniqueId, newCategoryId:ActiveTechniqueCategoryId, modId: ModificationId, actor: EventActor, reason: Option[String]) : Box[ActiveTechniqueId] = {
    (for {
      con                  <- ldap
      oldParents           <- if(autoExportOnModify) {
                                activeTechniqueBreadCrump(uactiveTechniqueId).toLdapResult
                              } else Nil.success
      activeTechnique      <- getUPTEntry(con, uactiveTechniqueId, "1.1").notOptional(s"Can not move non existing template in use library with ID '${uactiveTechniqueId.value}")
      newCategory          <- getCategoryEntry(con, newCategoryId, "1.1").notOptional(s"Can not move template with ID '${uactiveTechniqueId.value}' into non existing category of user library ${newCategoryId.value}")
      moved                <- userLibMutex.writeLock { con.move(activeTechnique.dn, newCategory.dn) ?~! s"Error when moving technique '${uactiveTechniqueId.value}' to category ${newCategoryId.value}"}
      movedActiveTechnique <- getActiveTechnique(uactiveTechniqueId).toLdapResult.notOptional(s"The technique was not found in new category")
      autoArchive          <- (( if(autoExportOnModify && !moved.isInstanceOf[LDIFNoopChangeRecord] && !movedActiveTechnique.isSystem) {
                                for {
                                  parents  <- activeTechniqueBreadCrump(uactiveTechniqueId)
                                  commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                                  moved    <- gitATArchiver.moveActiveTechnique(movedActiveTechnique, oldParents.map( _.id), parents.map( _.id), Some((modId, commiter, reason)))
                                } yield {
                                  moved
                                }
                              } else Full("ok") ) ?~! "Error when trying to archive automatically the technique move").toLdapResult
    } yield {
      uactiveTechniqueId
    }).toBox
  }

  /**
   * Set the status of the technique to the new value
   */
  def changeStatus(uactiveTechniqueId:ActiveTechniqueId, status:Boolean, modId: ModificationId, actor: EventActor, reason: Option[String]) : Box[ActiveTechniqueId] = {
    (for {
      con             <- ldap
      oldTechnique    <- getUPTEntry(con, uactiveTechniqueId).notOptional(s"Technique with id '${uactiveTechniqueId.value}' was not found")
      activeTechnique =  LDAPEntry(oldTechnique.backed)
      saved           <- {
                           activeTechnique +=! (A_IS_ENABLED, status.toLDAPString)
                           userLibMutex.writeLock { con.save(activeTechnique) }
                         }
      optDiff         <- (diffMapper.modChangeRecords2TechniqueDiff(oldTechnique, saved) ?~!
                           s"Error when mapping technique '${uactiveTechniqueId.value}' update to an diff: ${saved}").toLdapResult
      loggedAction    <- optDiff match {
                           case None       => "OK".success
                           case Some(diff) => actionLogger.saveModifyTechnique(modId, principal = actor, modifyDiff = diff, reason = reason).toLdapResult
                         }
      newactiveTechnique <- getActiveTechnique(uactiveTechniqueId).toLdapResult.notOptional(s"Technique with id '${uactiveTechniqueId.value}' can't be find back after status change")
      autoArchive     <- (if(autoExportOnModify && !saved.isInstanceOf[LDIFNoopChangeRecord] && ! newactiveTechnique.isSystem) {
                           for {
                             parents  <- activeTechniqueBreadCrump(uactiveTechniqueId)
                             commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                             archive  <- gitATArchiver.archiveActiveTechnique(newactiveTechnique, parents.map( _.id), Some((modId, commiter, reason)))
                           } yield archive
                         } else Full("ok")).toLdapResult
    } yield {
      uactiveTechniqueId
    }).toBox
  }

  def setAcceptationDatetimes(uactiveTechniqueId:ActiveTechniqueId, datetimes: Map[TechniqueVersion,DateTime], modId: ModificationId, actor: EventActor, reason: Option[String]) : Box[ActiveTechniqueId] = {
    (for {
      con             <- ldap
      activeTechnique <- getUPTEntry(con, uactiveTechniqueId, A_ACCEPTATION_DATETIME).notOptional(s"Active technique with id '${uactiveTechniqueId.value}' was not found")
      saved           <- {
                           val oldAcceptations = mapper.unserializeAcceptations(activeTechnique(A_ACCEPTATION_DATETIME).getOrElse(""))
                           val json = JsonAST.compactRender(mapper.serializeAcceptations(oldAcceptations ++ datetimes))
                           activeTechnique.+=!(A_ACCEPTATION_DATETIME, json)
                           userLibMutex.writeLock { con.save(activeTechnique) }
                         }
      newActiveTechnique  <- getActiveTechnique(uactiveTechniqueId).toLdapResult.notOptional(s"Active technique with id '${uactiveTechniqueId.value}' was not found after acceptation datetime update")
      autoArchive         <- (if(autoExportOnModify && !saved.isInstanceOf[LDIFNoopChangeRecord] && !newActiveTechnique.isSystem) {
                               for {
                                 parents <- activeTechniqueBreadCrump(uactiveTechniqueId)
                                 commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                                 archive <- gitATArchiver.archiveActiveTechnique(newActiveTechnique, parents.map( _.id), Some((modId, commiter, reason)))
                               } yield archive
                             } else Full("ok")).toLdapResult
    } yield {
      uactiveTechniqueId
    }).toBox
  }


  /**
   * Delete the technique in user library.
   * If no such element exists, it is a success.
   */
  def delete(uactiveTechniqueId:ActiveTechniqueId, modId: ModificationId, actor: EventActor, reason: Option[String]) : Box[ActiveTechniqueId] = {
    (for {
      con                <- ldap
      oldParents         <- if(autoExportOnModify) {
                              activeTechniqueBreadCrump(uactiveTechniqueId).toLdapResult
                            } else Nil.success
      done               <- getUPTEntry(con, uactiveTechniqueId).flatMap {
        case None => "done".success
        case Some(activeTechnique) =>
          val ldapEntryTechnique = LDAPEntry(activeTechnique.backed)
          for {
            oldTechnique       <- mapper.entry2ActiveTechnique(ldapEntryTechnique).toLdapResult
            deleted            <- userLibMutex.writeLock { con.delete(activeTechnique.dn, false) }
            diff               =  DeleteTechniqueDiff(oldTechnique)
            loggedAction       <- actionLogger.saveDeleteTechnique(modId, principal = actor, deleteDiff = diff, reason = reason).toLdapResult
            autoArchive        <- ((if(autoExportOnModify && deleted.size > 0 && !oldTechnique.isSystem) {
                                     for {
                                       ptName   <- Box(activeTechnique(A_TECHNIQUE_UUID)) ?~! "Missing required reference technique name"
                                       commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                                       res      <- gitATArchiver.deleteActiveTechnique(TechniqueName(ptName),oldParents.map( _.id), Some((modId, commiter, reason)))
                                     } yield res
                                    } else Full("ok") )  ?~! "Error when trying to archive automatically the category deletion").toLdapResult
          } yield {
            "done"
          }
      }
    } yield {
      uactiveTechniqueId
    }).toBox
  }

}
