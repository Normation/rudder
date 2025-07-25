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

import cats.implicits.*
import com.normation.GitVersion
import com.normation.NamedZioLogger
import com.normation.cfclerk.domain.SectionSpec
import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.errors.*
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.ldap.core.LDAPConstants.A_NAME
import com.normation.inventory.ldap.core.LDAPConstants.A_OC
import com.normation.inventory.ldap.core.LDAPConstants.A_REV_ID
import com.normation.ldap.ldif.LDIFNoopChangeRecord
import com.normation.ldap.sdk.*
import com.normation.ldap.sdk.BuildFilter.*
import com.normation.ldap.sdk.LDAPIOResult.*
import com.normation.ldap.sdk.syntax.*
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.RudderLDAPConstants.*
import com.normation.rudder.domain.policies.*
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
import com.normation.utils.StringUuidGenerator
import com.unboundid.ldap.sdk.DN
import com.unboundid.ldap.sdk.Filter
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import scala.collection.immutable.SortedMap
import zio.*
import zio.json.*
import zio.syntax.*

class RoLDAPDirectiveRepository(
    val rudderDit:           RudderDit,
    val ldap:                LDAPConnectionProvider[RoLDAPConnection],
    val mapper:              LDAPEntityMapper,
    val techniqueRepository: TechniqueRepository,
    val userLibMutex:        ScalaReadWriteLock // that's a scala-level mutex to have some kind of consistency with LDAP
) extends RoDirectiveRepository with NamedZioLogger {

  override def loggerName: String = this.getClass.getName

  /**
   * Retrieve the directive entry for the given ID, with the given connection
   */
  def getDirectiveEntry(con: RoLDAPConnection, id: DirectiveId, attributes: String*): LDAPIOResult[Option[LDAPEntry]] = {
    val filter = id.rev match {
      case GitVersion.DEFAULT_REV => EQ(A_DIRECTIVE_UUID, id.uid.value)
      case r                      => AND(EQ(A_DIRECTIVE_UUID, id.uid.value), EQ(A_REV_ID, r.value))
    }
    con
      .searchSub(rudderDit.ACTIVE_TECHNIQUES_LIB.dn, filter, attributes*)
      .flatMap(piEntries => {
        piEntries.size match {
          case 0 => None.succeed
          case 1 => Some(piEntries(0)).succeed
          case _ =>
            LDAPRudderError
              .Consistency(
                s"Error, the directory contains multiple occurrence of directive with id '${id.debugString}'. DN: ${piEntries.map(_.dn).mkString("; ")}"
              )
              .fail
        }
      })
  }

  private def policyFilter(includeSystem: Boolean) =
    if (includeSystem) IS(OC_DIRECTIVE) else AND(IS(OC_DIRECTIVE), EQ(A_IS_SYSTEM, false.toLDAPString))

  /**
   * Try to find the directive with the given ID.
   * Empty: no directive with such ID
   * Full((parent,directive)) : found the directive (directive.id == directiveId) in given parent
   *.fail => an error happened.
   */
  override def getDirective(id: DirectiveUid): IOResult[Option[Directive]] = {
    userLibMutex.readLock(for {
      con       <- ldap
      optEntry  <- getDirectiveEntry(con, DirectiveId(id))
      directive <- optEntry match {
                     case Some(entry) =>
                       mapper
                         .entry2Directive(entry)
                         .map(e => Some(e))
                         .chainError("Error when transforming LDAP entry into a directive for id %s. Entry: %s".format(id, entry))
                         .toIO
                     case None        => None.succeed
                   }
    } yield {
      directive
    })
  }

  override def getDirectiveWithContext(id: DirectiveUid): IOResult[Option[(Technique, ActiveTechnique, Directive)]] = {
    this
      .getActiveTechniqueAndDirective(DirectiveId(id))
      .chainError(s"Error when retrieving directive with ID ${id.value}''")
      .flatMap {
        case None                               => None.succeed
        case Some((activeTechnique, directive)) =>
          val activeTechniqueId = TechniqueId(activeTechnique.techniqueName, directive.techniqueVersion)
          for {
            technique <- techniqueRepository
                           .get(activeTechniqueId)
                           .notOptional(s"No Technique with ID='${activeTechniqueId.debugString}' found in reference library.")
          } yield {
            Some((technique, activeTechnique, directive))
          }
      }
  }

  /*
   * For that method, we can optionnaly get a directive entry. But if we get one, not having a corresponding
   * technique is an error
   */
  def getActiveTechniqueAndDirectiveEntries(id: DirectiveId): IOResult[Option[(LDAPEntry, LDAPEntry)]] = {
    userLibMutex.readLock(for {
      con      <- ldap
      optEntry <- getDirectiveEntry(con, id)
      pair     <- optEntry match {
                    case None      => None.succeed
                    case Some(dir) =>
                      val atId = mapper.dn2ActiveTechniqueId(dir.dn.getParent)
                      getUPTEntry(con, atId, (id: ActiveTechniqueId) => EQ(A_ACTIVE_TECHNIQUE_UUID, id.value))
                        .notOptional(
                          s"Can not find Active Technique entry '${atId.value}' in LDAP but directive with DN '${dir.dn}' exists: this is likely a bug, please report it."
                        )
                        .map(at => Some((at, dir)))
                  }
    } yield {
      pair
    })
  }

  /**
   * Find the active technique for which the given directive is an instance.
   *
   * Return empty if no such directive is known,
   * fails if no active technique match the directive.
   */
  override def getActiveTechniqueAndDirective(id: DirectiveId): IOResult[Option[(ActiveTechnique, Directive)]] = {
    getActiveTechniqueAndDirectiveEntries(id).chainError("Can not find Active Technique entry in LDAP").flatMap {
      case None                      => None.succeed
      case Some((uptEntry, piEntry)) =>
        for {
          activeTechnique <- mapper
                               .entry2ActiveTechnique(uptEntry)
                               .toIO
                               .chainError(s"Error when mapping active technique entry to its entity. Entry: ${uptEntry}")
          directive       <-
            mapper
              .entry2Directive(piEntry)
              .toIO
              .chainError(s"Error when transforming LDAP entry into a directive for id '${id.debugString}'. Entry: ${piEntry}")
        } yield {
          Some((activeTechnique, directive))
        }
    }
  }

  /**
   * Get directives for given technique.
   * A not known technique id is a.fail.
   */
  override def getDirectives(activeTechniqueId: ActiveTechniqueId, includeSystem: Boolean = false): IOResult[Seq[Directive]] = {
    userLibMutex.readLock(for {
      con        <- ldap
      entries    <- getUPTEntry(con, activeTechniqueId, "1.1").flatMap {
                      case None    => Seq().succeed
                      case Some(e) => con.searchOne(e.dn, policyFilter(includeSystem))
                    }
      directives <- ZIO.foreach(entries) { piEntry =>
                      mapper
                        .entry2Directive(piEntry)
                        .toIO
                        .chainError(s"Error when transforming LDAP entry into a directive. Entry: ${piEntry}")
                    }
    } yield {
      directives
    })
  }

  /**
   * Return true if at least one directive exists in this category (or a sub category
   * of this category)
   */
  def containsDirective(id: ActiveTechniqueCategoryId): UIO[Boolean] = {
    userLibMutex
      .readLock(for {
        con      <- ldap
        category <- getCategoryEntry(con, id).notOptional(s"Entry with ID '${id.value}' was not found")
        entries  <- con.searchSub(category.dn, IS(OC_DIRECTIVE), Seq[String]()*)
        results  <- ZIO.foreach(entries)(x => mapper.entry2Directive(x).toIO)
      } yield {
        results.nonEmpty
      })
      .fold(_ => false, identity)
  }

  /**
   * Root user categories
   */
  def getActiveTechniqueLibrary: IOResult[ActiveTechniqueCategory] = {
    userLibMutex.readLock(for {
      con               <- ldap
      rootCategoryEntry <- con
                             .get(rudderDit.ACTIVE_TECHNIQUES_LIB.dn)
                             .notOptional(
                               "The root category of the user library of techniques seems " +
                               "to be missing in LDAP directory. Please check its content"
                             )
      // look for sub category and technique
      rootCategory      <-
        mapper
          .entry2ActiveTechniqueCategory(rootCategoryEntry)
          .toIO
          .chainError("Error when mapping from an LDAP entry to an active technique Category: %s".format(rootCategoryEntry))
      added             <- addSubEntries(rootCategory, rootCategoryEntry.dn, con)
    } yield {
      added
    })
  }

  /**
   * Return all categories (lightweight version, with no children)
   * @return
   */
  def getAllActiveTechniqueCategories(includeSystem: Boolean = false): IOResult[Seq[ActiveTechniqueCategory]] = {
    userLibMutex.readLock(for {
      con               <- ldap
      rootCategoryEntry <-
        con
          .get(rudderDit.ACTIVE_TECHNIQUES_LIB.dn)
          .notOptional(
            "The root category of the user library of techniques seems to be missing in LDAP directory. Please check its content"
          )
      filter             =
        if (includeSystem) IS(OC_TECHNIQUE_CATEGORY) else AND(NOT(EQ(A_IS_SYSTEM, true.toLDAPString)), IS(OC_TECHNIQUE_CATEGORY))
      entries           <-
        con.searchSub(rudderDit.ACTIVE_TECHNIQUES_LIB.dn, filter) // double negation is mandatory, as false may not be present
      allEntries         = entries :+ rootCategoryEntry
      categories        <- allEntries.toList
                             .traverse(entry => {
                               (mapper
                                 .entry2ActiveTechniqueCategory(entry)
                                 .chainError(s"Error when transforming LDAP entry '${entry}' into an active technique category"))
                             })
                             .toIO
    } yield {
      categories
    })
  }

  /**
   * Get an active technique by its ID
   */
  def getActiveTechniqueCategory(id: ActiveTechniqueCategoryId): IOResult[Option[ActiveTechniqueCategory]] = {
    userLibMutex.readLock(for {
      con      <- ldap
      optEntry <- getCategoryEntry(con, id)
      category <-
        optEntry match {
          case None                => None.succeed
          case Some(categoryEntry) =>
            for {
              category <-
                (mapper
                  .entry2ActiveTechniqueCategory(categoryEntry)
                  .chainError("Error when transforming LDAP entry %s into an active technique category".format(categoryEntry)))
                  .toIO
              added    <- addSubEntries(category, categoryEntry.dn, con)
            } yield {
              Some(added)
            }
        }
    } yield {
      category
    })
  }

  /**
   * Find sub entries (children categories and active techniques for
   * the given category which MUST be mapped to an entry with the given
   * DN in the LDAP backend, accessible with the given connection.
   */
  def addSubEntries(category: ActiveTechniqueCategory, dn: DN, con: RoLDAPConnection): IOResult[ActiveTechniqueCategory] = {
    (for {
      subEntries <- con.searchOne(dn, OR(EQ(A_OC, OC_TECHNIQUE_CATEGORY), EQ(A_OC, OC_ACTIVE_TECHNIQUE)), "objectClass")
    } yield {
      subEntries.partition(e => e.isA(OC_TECHNIQUE_CATEGORY))
    }).fold(
      err => (Seq(), Seq()), // ignore errors

      x => x
    ).map(subEntries => {
      category.copy(
        children = subEntries._1.map(e => mapper.dn2ActiveTechniqueCategoryId(e.dn)).toList,
        items = subEntries._2.map(e => mapper.dn2ActiveTechniqueId(e.dn)).toList
      )
    })
  }

  /**
   * Retrieve the category entry for the given ID, with the given connection
   */
  def getCategoryEntry(con: RoLDAPConnection, id: ActiveTechniqueCategoryId, attributes: String*): IOResult[Option[LDAPEntry]] = {
    userLibMutex.readLock(for {
      categoryEntries <-
        con.searchSub(rudderDit.ACTIVE_TECHNIQUES_LIB.dn, EQ(A_TECHNIQUE_CATEGORY_UUID, id.value), attributes*)
      entry           <-
        categoryEntries.size match {
          case 0 => None.succeed
          case 1 => Some(categoryEntries(0)).succeed
          case _ =>
            s"Error, the directory contains multiple occurrence of category with id '${id.value}}'. DN: ${categoryEntries.map(_.dn).mkString("; ")}".fail
        }
    } yield {
      entry
    })
  }

  /**
   * Get the direct parent of the given category.
   * Return empty for root of the hierarchy, fails if the category
   * is not in the repository
   */
  def getParentActiveTechniqueCategory(id: ActiveTechniqueCategoryId): IOResult[ActiveTechniqueCategory] = {
    userLibMutex.readLock(for {
      con                 <- ldap
      categoryEntry       <- getCategoryEntry(con, id, "1.1").notOptional(s"Entry with ID '${id.value}' was not found")
      parentCategoryEntry <-
        con.get(categoryEntry.dn.getParent).notOptional(s"Entry with DN '${categoryEntry.dn.getParent}' was not found")
      parentCategory      <-
        mapper
          .entry2ActiveTechniqueCategory(parentCategoryEntry)
          .toIO
          .chainError(s"Error when transforming LDAP entry '${parentCategoryEntry}' into an active technique category")
      added               <- addSubEntries(parentCategory, parentCategoryEntry.dn, con)
    } yield {
      added
    })
  }

  /**
   * Return the list of parents for that category, the nearest parent
   * first, until the root of the library.
   * The the last parent is not the root of the library, return a.fail.
   * Also return a.fail if the path to top is broken in any way.
   */
  def getParentsForActiveTechniqueCategory(id: ActiveTechniqueCategoryId): IOResult[List[ActiveTechniqueCategory]] = {
    userLibMutex.readLock(
      // TODO : LDAPify that, we can have the list of all DN from id to root at the begining (just dn.getParent until rudderDit.ACTIVE_TECHNIQUES_LIB.dn)
      getActiveTechniqueLibrary.flatMap { root =>
        if (id == root.id) Nil.succeed
        else {
          getParentActiveTechniqueCategory(id).flatMap(parent =>
            getParentsForActiveTechniqueCategory(parent.id).map(parents => parent :: parents)
          )
        }
      }
    )
  }

  def getParentsForActiveTechnique(id: ActiveTechniqueId): IOResult[ActiveTechniqueCategory] = {
    userLibMutex.readLock(for {
      con        <- ldap
      uptEntries <- con.searchSub(rudderDit.ACTIVE_TECHNIQUES_LIB.dn, EQ(A_ACTIVE_TECHNIQUE_UUID, id.value))
      uptEntry   <- uptEntries.size match {
                      case 0 => s"Can not find active technique with id '${id.value}'".fail
                      case 1 => uptEntries(0).succeed
                      case _ =>
                        s"Found more than one active technique with id '${id.value}' : ${uptEntries.map(_.dn).mkString("; ")}".fail
                    }
      category   <-
        getActiveTechniqueCategory(mapper.dn2ActiveTechniqueCategoryId(uptEntry.dn.getParent)).notOptional(
          s"Category '${id.toString}' but we can't find its parent. If it wasn't deleted, please report the problem to Rudder project."
        )
    } yield {
      category
    })
  }

  def getActiveTechniqueByCategory(
      includeSystem: Boolean = false
  ): IOResult[SortedMap[List[ActiveTechniqueCategoryId], CategoryWithActiveTechniques]] = {
    userLibMutex.readLock(for {
      allCats     <- getAllActiveTechniqueCategories(includeSystem)
      catsWithUPs <- ZIO.foreach(allCats) { lightCat =>
                       for {
                         category <- getActiveTechniqueCategory(lightCat.id).notOptional(
                                       s"Impossible to retrieve category '${lightCat.id.toString}' which was previously listed"
                                     )
                         parents  <- getParentsForActiveTechniqueCategory(category.id)
                         upts     <- ZIO.foreach(category.items) { uactiveTechniqueId =>
                                       getActiveTechniqueByActiveTechnique(uactiveTechniqueId).notOptional(
                                         s"Missing active technique ${uactiveTechniqueId.value}"
                                       )
                                     }
                       } yield {
                         ((category.id :: parents.map(_.id)).reverse, CategoryWithActiveTechniques(category, upts.toSet))
                       }
                     }
    } yield {
      implicit val ordering = ActiveTechniqueCategoryOrdering
      SortedMap[List[ActiveTechniqueCategoryId], CategoryWithActiveTechniques]() ++ catsWithUPs
    })
  }

  def getActiveTechniqueByActiveTechnique(id: ActiveTechniqueId): IOResult[Option[ActiveTechnique]] = {
    userLibMutex.readLock {
      getActiveTechnique[ActiveTechniqueId](id, id => EQ(A_ACTIVE_TECHNIQUE_UUID, id.value))
    }
  }

  def getActiveTechnique(name: TechniqueName): IOResult[Option[ActiveTechnique]] = {
    userLibMutex.readLock {
      this.getActiveTechnique[TechniqueName](name, name => EQ(A_TECHNIQUE_UUID, name.value))
    }
  }

  /**
   * Add directives ids for the given active technique which must
   * be mapped to the given dn in LDAP directory accessible by con
   */
  private def addDirectives(activeTechnique: ActiveTechnique, dn: DN, con: RoLDAPConnection): IOResult[ActiveTechnique] = {
    for {
      piEntries <- con.searchOne(dn, EQ(A_OC, OC_DIRECTIVE), "objectClass").fold(_ => Seq(), x => x)
    } yield {
      activeTechnique.copy(
        directives = piEntries.map(e => mapper.dn2LDAPDirectiveUid(e.dn)).toList
      )
    }
  }

  private def getActiveTechnique[ID](id: ID, filter: ID => Filter): IOResult[Option[ActiveTechnique]] = {
    userLibMutex.readLock(for {
      con        <- ldap
      uptEntries <- con.searchSub(rudderDit.ACTIVE_TECHNIQUES_LIB.dn, filter(id))
      res        <- uptEntries.size match {
                      case 0 => None.succeed
                      case 1 =>
                        for {
                          activeTechnique <-
                            mapper
                              .entry2ActiveTechnique(uptEntries(0))
                              .chainError("Error when mapping active technique entry to its entity. Entry: %s".format(uptEntries(0)))
                              .toIO
                          added           <- addDirectives(activeTechnique, uptEntries(0).dn, con)
                        } yield {
                          Some(added)
                        }
                      case _ =>
                        s"Error, the directory contains multiple occurrence of active technique with ID '${id}'. DNs involved: ${uptEntries
                            .map(_.dn)
                            .mkString("; ")}".fail
                    }
    } yield {
      res
    })
  }

  def getUPTEntry(con: RoLDAPConnection, id: ActiveTechniqueId, attributes: String*): IOResult[Option[LDAPEntry]] = {
    userLibMutex.readLock {
      getUPTEntry[ActiveTechniqueId](con, id, id => EQ(A_ACTIVE_TECHNIQUE_UUID, id.value), attributes*)
    }
  }

  /**
   * Look in the subtree with root=active technique library
   * for and entry with the given id.
   * We expect at most one result, more is a.fail
   */
  def getUPTEntry[ID](
      con:        RoLDAPConnection,
      id:         ID,
      filter:     ID => Filter,
      attributes: String*
  ): LDAPIOResult[Option[LDAPEntry]] = {
    for {
      uptEntries <- con.searchSub(rudderDit.ACTIVE_TECHNIQUES_LIB.dn, filter(id), attributes*)
      entry      <- uptEntries.size match {
                      case 0 => None.succeed
                      case 1 => Some(uptEntries(0)).succeed
                      case _ =>
                        LDAPRudderError
                          .Consistency(
                            s"Error, the directory contains multiple occurrence of active " +
                            s"technique with ID '${id}'. DNs involved: ${uptEntries.map(_.dn).mkString("; ")}"
                          )
                          .fail
                    }
    } yield {
      entry
    }
  }

  def activeTechniqueBreadCrump(id: ActiveTechniqueId): IOResult[List[ActiveTechniqueCategory]] = {
    // find the active technique entry for that id, and from that, build the parent bread crump
    userLibMutex.readLock {
      for {
        cat  <- getParentsForActiveTechnique(id)
        cats <- getParentsForActiveTechniqueCategory(cat.id)
      } yield {
        cat :: cats
      }
    }
  }

  def getFullDirectiveLibrary(): IOResult[FullActiveTechniqueCategory] = {
    // data structure to holds all relation between objects
    final case class AllMaps(
        categories:                  Map[ActiveTechniqueCategoryId, ActiveTechniqueCategory],
        activeTechiques:             Map[ActiveTechniqueId, ActiveTechnique],
        categoriesByCategory:        Map[ActiveTechniqueCategoryId, List[ActiveTechniqueCategoryId]],
        activeTechniquesByCategory:  Map[ActiveTechniqueCategoryId, List[ActiveTechniqueId]],
        directivesByActiveTechnique: Map[ActiveTechniqueId, List[Directive]]
    )

    // here, active technique is expected to have an empty list of children
    def fromActiveTechnique(at: ActiveTechnique, maps: AllMaps) = {
      FullActiveTechnique(
        id = at.id,
        techniqueName = at.techniqueName,
        techniques = SortedMap(techniqueRepository.getByName(at.techniqueName).toSeq*),
        acceptationDatetimes = SortedMap(at.acceptationDatetimes.versions.toSeq*),
        directives = maps.directivesByActiveTechnique.getOrElse(at.id, Nil),
        isEnabled = at.isEnabled,
        policyTypes = at.policyTypes
      )
    }

    // here, subcaterories and active technique are expexcted to be empty
    def fromCategory(
        atcId:            ActiveTechniqueCategoryId,
        maps:             AllMaps,
        activeTechniques: Map[ActiveTechniqueId, FullActiveTechnique]
    ): FullActiveTechniqueCategory = {
      def getCat(id: ActiveTechniqueCategoryId) = maps.categories.getOrElse(
        id,
        throw new IllegalArgumentException(s"Missing categories with id ${atcId} in the list of available categories")
      )
      val atc                                   = getCat(atcId)

      FullActiveTechniqueCategory(
        id = atc.id,
        name = atc.name,
        description = atc.description,
        subCategories = maps.categoriesByCategory.getOrElse(atc.id, Nil).map(id => fromCategory(id, maps, activeTechniques)),
        activeTechniques = maps.activeTechniquesByCategory.getOrElse(atc.id, Nil).map { atId =>
          activeTechniques.getOrElse(
            atId,
            throw new IllegalArgumentException(
              s"Missing active technique with id ${atId} in the list of available active techniques"
            )
          )
        },
        isSystem = atc.isSystem
      )
    }

    /*
     * strategy: load the full subtree from the root category id,
     * then process entrie mapping them to their light version,
     * then call fromCategory on the root (we know its id).
     */

    val emptyAll = AllMaps(Map(), Map(), Map(), Map(), Map())
    import rudderDit.ACTIVE_TECHNIQUES_LIB.*

    def mappingError(current: AllMaps, e: LDAPEntry, err: RudderError): AllMaps = {
      val error = Chained(s"Error when mapping entry with DN '${e.dn.toString}' from directive library", err)

      logEffect.warn(error.fullMsg)
      current
    }

    userLibMutex
      .readLock(
        for {
          con     <- ldap
          entries <-
            con
              .getTree(rudderDit.ACTIVE_TECHNIQUES_LIB.dn)
              .notOptional(
                "The root category of the user library of techniques seems to be missing in LDAP directory. Please check its content"
              )
        } yield entries
      )
      .map { entries =>
        val allMaps = entries.toSeq.foldLeft(emptyAll) {
          case (current, e) =>
            if (isACategory(e)) {
              mapper.entry2ActiveTechniqueCategory(e) match {
                case Right(category) =>
                  // for categories other than root, add it in the list
                  // of its parent subcategories
                  val updatedSubCats = if (e.dn == rudderDit.ACTIVE_TECHNIQUES_LIB.dn) {
                    current.categoriesByCategory
                  } else {
                    val catId   = mapper.dn2ActiveTechniqueCategoryId(e.dn.getParent)
                    val subCats = category.id :: current.categoriesByCategory.getOrElse(catId, Nil)
                    current.categoriesByCategory + (catId -> subCats)
                  }
                  current.copy(
                    categories = current.categories + (category.id -> category),
                    categoriesByCategory = updatedSubCats
                  )
                case Left(err)       => mappingError(current, e, err)
              }
            } else if (isAnActiveTechnique(e)) {
              mapper.entry2ActiveTechnique(e) match {
                case Right(at) =>
                  val catId       = mapper.dn2ActiveTechniqueCategoryId(e.dn.getParent)
                  val atsForCatId = at.id :: current.activeTechniquesByCategory.getOrElse(catId, Nil)
                  current.copy(
                    activeTechiques = current.activeTechiques + (at.id                       -> at),
                    activeTechniquesByCategory = current.activeTechniquesByCategory + (catId -> atsForCatId)
                  )
                case Left(err) => mappingError(current, e, err)
              }
            } else if (isADirective(e)) {
              mapper.entry2Directive(e) match {
                case Right(dir) =>
                  val atId      = mapper.dn2ActiveTechniqueId(e.dn.getParent)
                  val dirsForAt = dir :: current.directivesByActiveTechnique.getOrElse(atId, Nil)
                  current.copy(
                    directivesByActiveTechnique = current.directivesByActiveTechnique + (atId -> dirsForAt)
                  )
                case Left(err)  => mappingError(current, e, err)
              }
            } else {
              // log error, continue
              logEffect.warn(
                s"Entry with DN '${e.dn}' was ignored because it is of an unknow type. Known types are categories, active techniques, directives"
              )
              current
            }
        }

        val fullActiveTechniques = allMaps.activeTechiques.map { case (id, at) => (id -> fromActiveTechnique(at, allMaps)) }.toMap

        fromCategory(ActiveTechniqueCategoryId(rudderDit.ACTIVE_TECHNIQUES_LIB.rdnValue._1), allMaps, fullActiveTechniques.toMap)
      }
  }

}

class WoLDAPDirectiveRepository(
    roDirectiveRepos:   RoLDAPDirectiveRepository,
    ldap:               LDAPConnectionProvider[RwLDAPConnection],
    diffMapper:         LDAPDiffMapper,
    actionLogger:       EventLogRepository,
    uuidGen:            StringUuidGenerator,
    gitPiArchiver:      GitDirectiveArchiver,
    gitATArchiver:      GitActiveTechniqueArchiver,
    gitCatArchiver:     GitActiveTechniqueCategoryArchiver,
    personIdentService: PersonIdentService,
    autoExportOnModify: Boolean
) extends WoDirectiveRepository with NamedZioLogger {

  import roDirectiveRepos.*

  override def loggerName: String = this.getClass.getName

  /**
   * Save the given directive into given active technique
   * If the directive is already present in the system but not
   * in the given category, raise an error.
   * If the directive is already in the given technique,
   * update the directive.
   * If the directive is not in the system, add it.
   * If the directive is exactly the same, returns None diff.
   * Returned the saved Directive
   */
  private def internalSaveDirective(
      inActiveTechniqueId: ActiveTechniqueId,
      directive:           Directive,
      modId:               ModificationId,
      actor:               EventActor,
      reason:              Option[String],
      systemCall:          Boolean
  ): IOResult[Option[DirectiveSaveDiff]] = {
    userLibMutex.writeLock(for {
      con              <- ldap
      uptEntry         <-
        getUPTEntry(con, inActiveTechniqueId, "1.1").notOptional(
          s"Can not find the active technique with id '${inActiveTechniqueId.value}' to add directive '${directive.id.uid.value}'"
        )
      canAdd           <-
        getDirectiveEntry(con, directive.id).flatMap { // check if the directive already exists elsewhere
          case None          => None.succeed
          case Some(otherPi) =>
            if (otherPi.dn.getParent == uptEntry.dn) {
              mapper.entry2Directive(otherPi).toIO.flatMap { x =>
                (x.isSystem, systemCall) match {
                  case (true, false) => Unexpected(s"System directive '${x.name}' (${x.id.uid.value}) can't be updated").fail
                  case (false, true) => Inconsistency("Non-system directive can not be updated with that method").fail
                  case _             => Some(otherPi).succeed
                }
              }
            } else {
              Inconsistency(
                s"An other directive with the id '${directive.id.uid.value}' exists in an other category that the one with id '${inActiveTechniqueId.value}}': ${otherPi.dn}}"
              ).fail
            }
        }
      // We have to keep the old rootSection to generate the event log
      oldRootSection   <- getActiveTechniqueAndDirective(DirectiveId(directive.id.uid)).map {
                            // Directory did not exist before, this is a Rule addition.
                            case None                                     => None
                            case Some((oldActiveTechnique, oldDirective)) =>
                              val oldTechniqueId = TechniqueId(oldActiveTechnique.techniqueName, oldDirective.techniqueVersion)
                              techniqueRepository.get(oldTechniqueId).map(_.rootSection)
                          }
      exists           <- directiveNameExists(con, directive.name, directive.id.uid)
      nameIsAvailable  <- ZIO.when(exists) {
                            Inconsistency(
                              s"Cannot set directive with name '${directive.name}' : this name is already in use."
                            ).fail
                          }
      piEntry           = mapper.userDirective2Entry(directive, uptEntry.dn)
      result           <- con.save(piEntry, removeMissingAttributes = true)
      // for log event - perhaps put that elsewhere ?
      activeTechnique  <-
        getActiveTechniqueByActiveTechnique(inActiveTechniqueId).notOptional(
          s"Can not find active technique Entry with id '${inActiveTechniqueId.value}' to add directive '${directive.id.uid.value}'"
        )
      activeTechniqueId = TechniqueId(activeTechnique.techniqueName, directive.techniqueVersion)
      technique        <- techniqueRepository
                            .get(activeTechniqueId)
                            .notOptional(s"Can not find the technique with ID '${activeTechniqueId.debugString}'")
      optDiff          <- (diffMapper
                            .modChangeRecords2DirectiveSaveDiff(
                              technique.id.name,
                              technique.rootSection,
                              piEntry.dn,
                              canAdd,
                              result,
                              oldRootSection
                            )
                            .toIO
                            .chainError("Error when processing saved modification to log them"))
      eventLogged      <- (optDiff match {
                            case None                            => ZIO.unit
                            case Some(diff: AddDirectiveDiff)    =>
                              actionLogger.saveAddDirective(
                                modId,
                                principal = actor,
                                addDiff = diff,
                                varsRootSectionSpec = technique.rootSection,
                                reason = reason
                              )
                            case Some(diff: ModifyDirectiveDiff) =>
                              actionLogger.saveModifyDirective(
                                modId,
                                principal = actor,
                                modifyDiff = diff,
                                reason = reason
                              )
                          })
      autoArchive      <- ZIO.when(autoExportOnModify && optDiff.isDefined && !directive.isSystem) {
                            for {
                              parents  <- activeTechniqueBreadCrump(activeTechnique.id)
                              commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                              archived <- gitPiArchiver.archiveDirective(
                                            directive,
                                            technique.id.name,
                                            parents.map(_.id),
                                            technique.rootSection,
                                            Some((modId, commiter, reason))
                                          )
                            } yield archived
                          }
    } yield {
      optDiff
    })
  }

  override def saveDirective(
      inActiveTechniqueId: ActiveTechniqueId,
      directive:           Directive,
      modId:               ModificationId,
      actor:               EventActor,
      reason:              Option[String]
  ): IOResult[Option[DirectiveSaveDiff]] = {
    internalSaveDirective(inActiveTechniqueId, directive, modId, actor, reason, systemCall = false)
  }

  override def saveSystemDirective(
      inActiveTechniqueId: ActiveTechniqueId,
      directive:           Directive,
      modId:               ModificationId,
      actor:               EventActor,
      reason:              Option[String]
  ): IOResult[Option[DirectiveSaveDiff]] = {
    internalSaveDirective(inActiveTechniqueId, directive, modId, actor, reason, systemCall = true)
  }

  private def directiveNameExists(con: RoLDAPConnection, name: String, id: DirectiveUid): IOResult[Boolean] = {
    val filter = AND(AND(IS(OC_DIRECTIVE), EQ(A_NAME, name), NOT(EQ(A_DIRECTIVE_UUID, id.value))))
    con
      .searchSub(rudderDit.ACTIVE_TECHNIQUES_LIB.dn, filter)
      .flatMap(_.size match {
        case 0 => false.succeed
        case 1 => true.succeed
        case _ => logPure.error("More than one directive has %s name".format(name)) *> true.succeed
      })
  }

  /**
   * Delete a directive's system.
   * No dependency check are done, and so you will have to
   * delete dependent rule (or other items) by
   * hand if you want.
   *
   * If no directive has such id, return a success.
   */
  override def deleteSystemDirective(
      id:     DirectiveUid,
      modId:  ModificationId,
      actor:  EventActor,
      reason: Option[String]
  ): IOResult[Option[DeleteDirectiveDiff]] = {
    internalDeleteDirective(id, modId, actor, reason, callSystem = true)
  }

  /**
   * Delete a directive.
   * No dependency check are done, and so you will have to
   * delete dependent rule (or other items) by
   * hand if you want.
   */
  override def delete(
      id:     DirectiveUid,
      modId:  ModificationId,
      actor:  EventActor,
      reason: Option[String]
  ): IOResult[Option[DeleteDirectiveDiff]] = {
    internalDeleteDirective(id, modId, actor, reason, callSystem = false)
  }

  private def internalDeleteDirective(
      id:         DirectiveUid,
      modId:      ModificationId,
      actor:      EventActor,
      reason:     Option[String],
      callSystem: Boolean
  ): IOResult[Option[DeleteDirectiveDiff]] = {
    userLibMutex.writeLock(getActiveTechniqueAndDirectiveEntries(DirectiveId(id)).flatMap {
      case None                    => // directive already deleted, do nothing
        None.succeed
      case Some((uptEntry, entry)) =>
        for {
          // for logging, before deletion
          activeTechnique <- mapper
                               .entry2ActiveTechnique(uptEntry)
                               .chainError(s"Error when mapping active technique entry to its entity. Entry: ${uptEntry}")
                               .toIO
          directive       <- mapper
                               .entry2Directive(entry)
                               .chainError(s"Error when transforming LDAP entry into a directive for id '${id.value}'. Entry: ${entry}")
                               .toIO
          checkSystem     <- (directive.isSystem, callSystem) match {
                               case (true, false) => Unexpected(s"System directive '${id.value}' can't be deleted").fail
                               case (false, true) =>
                                 Inconsistency(s"Non-system directive '${id.value}' can not be deleted with that method").fail
                               case _             => directive.succeed
                             }
          technique       <- techniqueRepository.get(TechniqueId(activeTechnique.techniqueName, directive.techniqueVersion)).succeed
          // delete
          deleted         <- ldap.flatMap(_.delete(entry.dn))
          diff             = DeleteDirectiveDiff(activeTechnique.techniqueName, directive)
          loggedAction    <- { // we can have a missing technique if the technique was deleted but not its directive. In that case, make a fake root section
            val rootSection = technique.map(_.rootSection).getOrElse(SectionSpec("Missing technique information"))
            actionLogger.saveDeleteDirective(
              modId,
              principal = actor,
              deleteDiff = diff,
              varsRootSectionSpec = rootSection,
              reason = reason
            )
          }
          autoArchive     <- ZIO.when(autoExportOnModify && deleted.size > 0 && !directive.isSystem) {
                               for {
                                 parents  <- activeTechniqueBreadCrump(activeTechnique.id)
                                 commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                                 archived <- gitPiArchiver.deleteDirective(
                                               directive.id.uid,
                                               activeTechnique.techniqueName,
                                               parents.map(_.id),
                                               Some((modId, commiter, reason))
                                             )
                               } yield archived
                             }
        } yield {
          Some(diff)
        }
    })
  }

  /**
   * Check if the given parent category has a child with the given name (exact) and
   * an id different from given id
   */
  private def existsByName(
      con:             RwLDAPConnection,
      parentDN:        DN,
      subCategoryName: String,
      notId:           String
  ): IOResult[Boolean] = {
    userLibMutex.readLock {
      con.searchOne(parentDN, AND(EQ(A_NAME, subCategoryName), NOT(EQ(A_TECHNIQUE_CATEGORY_UUID, notId))), "1.1").map(_.nonEmpty)
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
      that: ActiveTechniqueCategory,
      into: ActiveTechniqueCategoryId, // parent category

      modId:  ModificationId,
      actor:  EventActor,
      reason: Option[String]
  ): IOResult[ActiveTechniqueCategory] = {
    userLibMutex.writeLock(for {
      con                 <- ldap
      parentCategoryEntry <-
        getCategoryEntry(con, into, A_NAME).notOptional(s"The parent category '${into.value}' was not found, can not add")
      categoryEntry        = mapper.activeTechniqueCategory2ldap(that, parentCategoryEntry.dn)
      exists              <- existsByName(con, parentCategoryEntry.dn, that.name, that.id.value)
      canAddByName        <- if (exists) {
                               s"A category with name '${that.name}' already exists in category '${parentCategoryEntry(A_NAME).getOrElse("UNKNOWN")}': category names must be unique for a given level".fail
                             } else {
                               "Can add, no sub categorie with that name".succeed
                             }
      result              <- con.save(categoryEntry, removeMissingAttributes = true)
      autoArchive         <- ZIO.when(autoExportOnModify && !result.isInstanceOf[LDIFNoopChangeRecord] && !that.isSystem) {
                               for {
                                 parents  <- getParentsForActiveTechniqueCategory(that.id)
                                 commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                                 archive  <-
                                   gitCatArchiver.archiveActiveTechniqueCategory(that, parents.map(_.id), Some((modId, commiter, reason)))
                               } yield archive
                             }
      parentEntry         <- getCategoryEntry(con, into).chainError(s"Entry with ID '${into.value}' was not found")
      updatedParent       <- mapper
                               .entry2ActiveTechniqueCategory(categoryEntry)
                               .chainError(s"Error when transforming LDAP entry '${categoryEntry}' into an active technique category")
                               .toIO
    } yield {
      updatedParent
    })
  }

  /**
   * Update an existing technique category
   * Return the updated policy category
   */
  def saveActiveTechniqueCategory(
      category: ActiveTechniqueCategory,
      modId:    ModificationId,
      actor:    EventActor,
      reason:   Option[String]
  ): IOResult[ActiveTechniqueCategory] = {
    userLibMutex.writeLock(for {
      con              <- ldap
      oldCategoryEntry <-
        getCategoryEntry(con, category.id, A_NAME).notOptional(s"Entry with ID '${category.id.value}' was not found")
      categoryEntry     = mapper.activeTechniqueCategory2ldap(category, oldCategoryEntry.dn.getParent)
      exists           <- existsByName(con, categoryEntry.dn.getParent, category.name, category.id.value)
      canAddByName     <- if (categoryEntry.dn != rudderDit.ACTIVE_TECHNIQUES_LIB.dn && exists) {
                            (s"You can't rename category ${oldCategoryEntry(A_NAME).getOrElse("UNKNOWN")} into '${category.name}' because an other category with " +
                            s"that name already exists: category names must be unique for a given level").fail
                          } else {
                            "Can add, no sub categorie with that name".succeed
                          }
      result           <- con.save(categoryEntry, removeMissingAttributes = true)
      updated          <- getActiveTechniqueCategory(category.id).notOptional(
                            s"Error: can not find back just saved category '${category.id.toString}'"
                          )
      autoArchive      <- ZIO.when(autoExportOnModify && !result.isInstanceOf[LDIFNoopChangeRecord] && !category.isSystem) {
                            for {
                              parents  <- getParentsForActiveTechniqueCategory(category.id)
                              commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                              archive  <- gitCatArchiver.archiveActiveTechniqueCategory(
                                            updated,
                                            parents.map(_.id),
                                            Some((modId, commiter, reason))
                                          )
                            } yield archive
                          }
    } yield {
      updated
    })
  }

  def deleteCategory(
      id:         ActiveTechniqueCategoryId,
      modId:      ModificationId,
      actor:      EventActor,
      reason:     Option[String],
      checkEmpty: Boolean = true
  ): IOResult[ActiveTechniqueCategoryId] = {
    userLibMutex.writeLock(for {
      con     <- ldap
      deleted <- getCategoryEntry(con, id).flatMap {
                   case Some(entry) =>
                     for {
                       category    <- mapper.entry2ActiveTechniqueCategory(entry).toIO
                       parents     <- if (autoExportOnModify) {
                                        getParentsForActiveTechniqueCategory(id)
                                      } else Nil.succeed
                       ok          <- con
                                        .delete(entry.dn, recurse = !checkEmpty)
                                        .chainError(s"Error when trying to delete category with ID '${id.value}'")
                       autoArchive <- ZIO
                                        .when(autoExportOnModify && ok.size > 0 && !category.isSystem) {
                                          for {
                                            commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                                            archive  <- gitCatArchiver.deleteActiveTechniqueCategory(
                                                          id,
                                                          parents.map(_.id),
                                                          Some((modId, commiter, reason))
                                                        )
                                          } yield {
                                            archive
                                          }
                                        }
                                        .chainError("Error when trying to archive automatically the category deletion")
                     } yield {
                       id
                     }

                   case None => id.succeed
                 }
    } yield {
      deleted
    })
  }

  /**
   * Move an existing category into a new one.
   * Both category to move and destination have to exists, else it is a.fail.
   * The destination category can not be a child of the category to move.
   */
  def move(
      categoryId:    ActiveTechniqueCategoryId,
      intoParent:    ActiveTechniqueCategoryId,
      optionNewName: Option[ActiveTechniqueCategoryId],
      modId:         ModificationId,
      actor:         EventActor,
      reason:        Option[String]
  ): IOResult[ActiveTechniqueCategoryId] = {
    userLibMutex.writeLock(for {
      con            <- ldap
      oldParents     <- if (autoExportOnModify) {
                          getParentsForActiveTechniqueCategory(categoryId)
                        } else Nil.succeed
      categoryEntry  <- getCategoryEntry(con, categoryId, A_NAME).notOptional(s"Category was not found")
      newParentEntry <-
        getCategoryEntry(con, intoParent, "1.1").notOptional(s"New destination category '${intoParent.value}' was not found")
      moveAuthorised <- if (newParentEntry.dn.isDescendantOf(categoryEntry.dn, true)) {
                          "Can not move a category to itself or one of its children".fail
                        } else "Succes".succeed
      canAddByName   <- (categoryEntry(A_TECHNIQUE_CATEGORY_UUID), categoryEntry(A_NAME)) match {
                          case (Some(id), Some(name)) =>
                            existsByName(con, newParentEntry.dn, name, id).flatMap { exists =>
                              if (exists) {
                                s"A category with name '${name}' already exists in category '${newParentEntry(A_NAME).getOrElse("UNKNOWN")}': category names must be unique for a given level".fail
                              } else {
                                "Can add, no sub categorie with that name".succeed
                              }
                            }
                          case _                      =>
                            "Can not find the category entry name for category with ID %s. Name is needed to check unicity of categories by level".fail
                        }
      result         <- con.move(
                          categoryEntry.dn,
                          newParentEntry.dn,
                          optionNewName.map(n => rudderDit.ACTIVE_TECHNIQUES_LIB.buildCategoryRDN(n.value))
                        )
      newCat         <- getActiveTechniqueCategory(optionNewName.getOrElse(categoryId))
                          .notOptional(s"Error: can not find back just move category '${categoryId.toString}'")
      autoArchive    <- ZIO
                          .when(autoExportOnModify && !result.isInstanceOf[LDIFNoopChangeRecord] && !newCat.isSystem) {
                            for {
                              parents  <- getParentsForActiveTechniqueCategory(newCat.id)
                              commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                              moved    <- gitCatArchiver.moveActiveTechniqueCategory(
                                            newCat,
                                            oldParents.map(_.id),
                                            parents.map(_.id),
                                            Some((modId, commiter, reason))
                                          )
                            } yield {
                              moved
                            }
                          }
                          .chainError("Error when trying to archive automatically the category move")
    } yield {
      categoryId
    })
  }

  def addTechniqueInUserLibrary(
      categoryId:    ActiveTechniqueCategoryId,
      techniqueName: TechniqueName,
      versions:      Seq[TechniqueVersion],
      policyTypes:   PolicyTypes,
      modId:         ModificationId,
      actor:         EventActor,
      reason:        Option[String]
  ): IOResult[ActiveTechnique] = {
    // check if the technique is already in user lib, and if the category exists
    userLibMutex.writeLock(for {
      con               <- ldap
      noActiveTechnique <- { // check that there is not already defined activeTechnique with such ref id
        getUPTEntry[TechniqueName](con, techniqueName, name => EQ(A_TECHNIQUE_UUID, name.value), "1.1") flatMap {
          case None           => ZIO.unit
          case Some(uptEntry) =>
            s"Can not add a technique with id '${techniqueName.value}' in user library. active technique '${uptEntry.dn}}' is already defined with such a reference technique.".fail
        }
      }
      categoryEntry     <-
        getCategoryEntry(con, categoryId, "1.1").notOptional(s"Category entry with ID '${categoryId.value}' was not found")
      newActiveTechnique = ActiveTechnique(
                             ActiveTechniqueId(techniqueName.value),
                             techniqueName,
                             AcceptationDateTime(versions.map(x => x -> DateTime.now(DateTimeZone.UTC)).toMap),
                             policyTypes = policyTypes
                           )
      uptEntry           = mapper.activeTechnique2Entry(newActiveTechnique, categoryEntry.dn)
      result            <- con.save(uptEntry, removeMissingAttributes = true)
      // a new active technique is never system, see constructor call, using defvault value,
      // maybe we should check in its caller is the technique is system or not
      autoArchive       <- ZIO.when(autoExportOnModify && !result.isInstanceOf[LDIFNoopChangeRecord]) {
                             for {
                               parents  <- activeTechniqueBreadCrump(newActiveTechnique.id)
                               commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                               archive  <- gitATArchiver.archiveActiveTechnique(
                                             newActiveTechnique,
                                             parents.map(_.id),
                                             Some((modId, commiter, reason))
                                           )
                             } yield archive
                           }
    } yield {
      newActiveTechnique
    })
  }

  /**
   * Move a technique to a new category.
   * Fails if the given technique or category
   * does not exist.
   *
   */
  def move(
      uactiveTechniqueId: ActiveTechniqueId,
      newCategoryId:      ActiveTechniqueCategoryId,
      modId:              ModificationId,
      actor:              EventActor,
      reason:             Option[String]
  ): IOResult[ActiveTechniqueId] = {
    userLibMutex.writeLock(for {
      con                  <- ldap
      oldParents           <- if (autoExportOnModify) {
                                activeTechniqueBreadCrump(uactiveTechniqueId)
                              } else Nil.succeed
      activeTechnique      <- getUPTEntry(con, uactiveTechniqueId, "1.1").notOptional(
                                s"Can not move non existing template in use library with ID '${uactiveTechniqueId.value}"
                              )
      newCategory          <-
        getCategoryEntry(con, newCategoryId, "1.1").notOptional(
          s"Can not move template with ID '${uactiveTechniqueId.value}' into non existing category of user library ${newCategoryId.value}"
        )
      moved                <- con
                                .move(activeTechnique.dn, newCategory.dn)
                                .chainError(s"Error when moving technique '${uactiveTechniqueId.value}' to category ${newCategoryId.value}")
      movedActiveTechnique <-
        getActiveTechniqueByActiveTechnique(uactiveTechniqueId).notOptional(s"The technique was not found in new category")
      autoArchive          <-
        ZIO
          .when(
            autoExportOnModify && !moved.isInstanceOf[LDIFNoopChangeRecord] && !movedActiveTechnique.policyTypes.isSystem
          ) {
            for {
              parents  <- activeTechniqueBreadCrump(uactiveTechniqueId)
              commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
              moved    <- gitATArchiver.moveActiveTechnique(
                            movedActiveTechnique,
                            oldParents.map(_.id),
                            parents.map(_.id),
                            Some((modId, commiter, reason))
                          )
            } yield {
              moved
            }
          }
          .chainError("Error when trying to archive automatically the technique move")
    } yield {
      uactiveTechniqueId
    })
  }

  /**
   * Set the status of the technique to the new value
   */
  def changeStatus(
      uactiveTechniqueId: ActiveTechniqueId,
      status:             Boolean,
      modId:              ModificationId,
      actor:              EventActor,
      reason:             Option[String]
  ): IOResult[ActiveTechniqueId] = {
    userLibMutex.writeLock(for {
      con                <- ldap
      oldTechnique       <-
        getUPTEntry(con, uactiveTechniqueId).notOptional(s"Technique with id '${uactiveTechniqueId.value}' was not found")
      activeTechnique     = LDAPEntry(oldTechnique.backed)
      saved              <- {
        activeTechnique.resetValuesTo(A_IS_ENABLED, status.toLDAPString)
        con.save(activeTechnique)
      }
      optDiff            <- diffMapper
                              .modChangeRecords2TechniqueDiff(oldTechnique, saved)
                              .toIO
                              .chainError(
                                s"Error when mapping technique '${uactiveTechniqueId.value}' update to an diff: ${saved}"
                              )
      loggedAction       <- optDiff match {
                              case None       => ZIO.unit
                              case Some(diff) =>
                                actionLogger.saveModifyTechnique(modId, principal = actor, modifyDiff = diff, reason = reason)
                            }
      newactiveTechnique <- getActiveTechniqueByActiveTechnique(uactiveTechniqueId).notOptional(
                              s"Technique with id '${uactiveTechniqueId.value}' can't be find back after status change"
                            )
      autoArchive        <-
        ZIO.when(autoExportOnModify && !saved.isInstanceOf[LDIFNoopChangeRecord] && !newactiveTechnique.policyTypes.isSystem) {
          for {
            parents  <- activeTechniqueBreadCrump(uactiveTechniqueId)
            commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
            archive  <- gitATArchiver.archiveActiveTechnique(
                          newactiveTechnique,
                          parents.map(_.id),
                          Some((modId, commiter, reason))
                        )
          } yield archive
        }
    } yield {
      uactiveTechniqueId
    })
  }

  def setAcceptationDatetimes(
      uactiveTechniqueId: ActiveTechniqueId,
      datetimes:          Map[TechniqueVersion, DateTime],
      modId:              ModificationId,
      actor:              EventActor,
      reason:             Option[String]
  ): IOResult[ActiveTechniqueId] = {
    userLibMutex.writeLock(for {
      con                <- ldap
      activeTechnique    <- getUPTEntry(con, uactiveTechniqueId, A_ACCEPTATION_DATETIME).notOptional(
                              s"Active technique with id '${uactiveTechniqueId.value}' was not found"
                            )
      oldAcceptations    <-
        activeTechnique(A_ACCEPTATION_DATETIME).getOrElse("").fromJson[AcceptationDateTime].toIO
      saved              <- {
        val json = oldAcceptations.withNewVersions(datetimes).toJson
        activeTechnique.resetValuesTo(A_ACCEPTATION_DATETIME, json)
        con.save(activeTechnique)
      }
      newActiveTechnique <-
        getActiveTechniqueByActiveTechnique(uactiveTechniqueId).notOptional(
          s"Active technique with id '${uactiveTechniqueId.value}' was not found after acceptation datetime update"
        )
      autoArchive        <-
        ZIO.when(autoExportOnModify && !saved.isInstanceOf[LDIFNoopChangeRecord] && !newActiveTechnique.policyTypes.isSystem) {
          for {
            parents  <- activeTechniqueBreadCrump(uactiveTechniqueId)
            commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
            archive  <- gitATArchiver.archiveActiveTechnique(
                          newActiveTechnique,
                          parents.map(_.id),
                          Some((modId, commiter, reason))
                        )
          } yield archive
        }
    } yield {
      uactiveTechniqueId
    })
  }

  /**
   * Delete the technique in user library.
   * If no such element exists, it is a.succeed.
   */
  def deleteActiveTechnique(
      uactiveTechniqueId: ActiveTechniqueId,
      modId:              ModificationId,
      actor:              EventActor,
      reason:             Option[String]
  ): IOResult[ActiveTechniqueId] = {
    userLibMutex.writeLock(for {
      con  <- ldap
      done <- getUPTEntry(con, uactiveTechniqueId).flatMap {
                case None                  => "done".succeed
                case Some(activeTechnique) =>
                  val ldapEntryTechnique = LDAPEntry(activeTechnique.backed)
                  for {
                    oldParents   <- if (autoExportOnModify) {
                                      activeTechniqueBreadCrump(uactiveTechniqueId)
                                    } else Nil.succeed
                    oldTechnique <- mapper.entry2ActiveTechnique(ldapEntryTechnique).toIO
                    deleted      <- con.delete(activeTechnique.dn, recurse = false)
                    diff          = DeleteTechniqueDiff(oldTechnique)
                    loggedAction <- actionLogger.saveDeleteTechnique(modId, principal = actor, deleteDiff = diff, reason = reason)
                    _            <- ZIO
                                      .when(autoExportOnModify && deleted.size > 0 && !oldTechnique.policyTypes.isSystem) {
                                        for {
                                          ptName   <- activeTechnique(A_TECHNIQUE_UUID) match {
                                                        case None    => Inconsistency("Missing required reference technique name").fail
                                                        case Some(x) => x.succeed
                                                      }
                                          commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                                          res      <- gitATArchiver.deleteActiveTechnique(
                                                        TechniqueName(ptName),
                                                        oldParents.map(_.id),
                                                        Some((modId, commiter, reason))
                                                      )
                                        } yield res
                                      }
                                      .chainError("Error when trying to archive automatically the category deletion")
                  } yield {
                    "done"
                  }
              }
    } yield {
      uactiveTechniqueId
    })
  }

}
