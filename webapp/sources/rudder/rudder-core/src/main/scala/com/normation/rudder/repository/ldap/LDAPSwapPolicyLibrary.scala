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

import com.normation.cfclerk.domain.TechniqueName
import com.normation.errors._
import com.normation.inventory.ldap.core.LDAPConstants.A_OC
import com.normation.ldap.sdk.GeneralizedTime
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.RudderLDAPConstants._
import com.normation.rudder.domain.policies.ActiveTechniqueCategory
import com.normation.rudder.domain.policies.ActiveTechniqueCategoryId
import com.normation.rudder.domain.policies.ActiveTechniqueId
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.repository.ActiveTechniqueCategoryContent
import com.normation.rudder.repository.ActiveTechniqueContent
import com.normation.rudder.repository.ActiveTechniqueLibraryArchiveId
import com.normation.rudder.repository.ImportTechniqueLibrary
import com.unboundid.ldap.sdk.DN
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import scalaz.zio._
import scalaz.zio.syntax._

class ImportTechniqueLibraryImpl(
    rudderDit   : RudderDit
  , ldap        : LDAPConnectionProvider[RwLDAPConnection]
  , mapper      : LDAPEntityMapper
  , userLibMutex: ScalaReadWriteLock //that's a scala-level mutex to have some kind of consistency with LDAP
) extends ImportTechniqueLibrary with LDAPImportLibraryUtil {

  override def loggerName: String = this.getClass.getName

  /**
   * That method swap an existing active technique library in LDAP
   * to a new one.
   *
   * In case of error, we try to restore the old technique library.
   */
  def swapActiveTechniqueLibrary(rootCategory:ActiveTechniqueCategoryContent, includeSystem:Boolean = false) : IOResult[Unit] = {
    /*
     * Hight level behaviour:
     * - check that User Library respects global rules
     *   (at most one UPT for each PT, no two categories with the same name, etc)
     *   If not, remove duplicates with error logs (because perhaps they are no duplicate,
     *   user will want to know)
     * - move current user lib elsewhere in the LDAP
     *   (with the root and the system)
     * - create back the root, with
     *   - initTimeStamp to now
     *   - the same techniqueLibraryVersion
     * - copy back system if kept
     * - save all categories, PT, PIs
     * - if everything goes well, delete the old User Lob
     * - else, rollback: delete new User lib, move back old user lib
     *
     */

    //as far atomic as we can :)
    //don't bother with system and consistency here, it is taken into account elsewhere
    def atomicSwap(userLib:ActiveTechniqueCategoryContent) : IOResult[ActiveTechniqueLibraryArchiveId] = {
      //save the new one
      //we need to keep the git commit id
      def saveUserLib(con:RwLDAPConnection, userLib:ActiveTechniqueCategoryContent, gitId:Option[String]) : IOResult[Unit] = {
        def recSaveUserLib(parentDN:DN, content:ActiveTechniqueCategoryContent, isRoot:Boolean = false) : IOResult[Unit] = {
          //start with the category
          //then with technique/directive for that category
          //then recurse on sub-categories
          val categoryEntry = mapper.activeTechniqueCategory2ldap(content.category, parentDN)
          if(isRoot) {
            categoryEntry +=  (A_OC, OC_ACTIVE_TECHNIQUE_LIB_VERSION)
            categoryEntry +=! (A_INIT_DATETIME, GeneralizedTime(DateTime.now()).toString)
            gitId.foreach { x => categoryEntry +=! (A_TECHNIQUE_LIB_VERSION, x) }
          }

          for {
            category      <- con.save(categoryEntry).chainError("Error when persisting category with DN '%s' in LDAP".format(categoryEntry.dn))
            techniques    <- ZIO.foreach(content.templates) { case ActiveTechniqueContent(activeTechnique, directives) =>
                               val uptEntry = mapper.activeTechnique2Entry(activeTechnique, categoryEntry.dn)
                               for {
                                 uptSaved <- con.save(uptEntry).chainError("Error when persisting User Policy entry with DN '%s' in LDAP".format(uptEntry.dn))
                                 pisSaved <- ZIO.foreach(directives) { directive =>
                                               val piEntry = mapper.userDirective2Entry(directive, uptEntry.dn)
                                               con.save(piEntry,true).chainError("Error when persisting directive entry with DN '%s' in LDAP".format(piEntry.dn))
                                             }
                               } yield {
                                 "OK"
                               }
                             }
            subCategories <- ZIO.foreach(content.categories) { cat =>
                               recSaveUserLib(categoryEntry.dn, cat)
                             }
          } yield {
            () // unit is expected
          }
        }

        recSaveUserLib(rudderDit.ACTIVE_TECHNIQUES_LIB.dn.getParent,userLib, isRoot = true)
      }


      val archiveId = ActiveTechniqueLibraryArchiveId(DateTime.now().toString(ISODateTimeFormat.dateTime))
      val targetArchiveDN = rudderDit.ARCHIVES.userLibDN(archiveId)

      //the sequence of operation to actually perform the swap with rollback
      for {
        con      <- ldap
        gitId    <- con.get(rudderDit.ACTIVE_TECHNIQUES_LIB.dn, OC_ACTIVE_TECHNIQUE_LIB_VERSION).notOptional("Error when looking for the root entry of the Active Technique Library when trying to check for an existing revision number").map { entry =>
                      entry(OC_ACTIVE_TECHNIQUE_LIB_VERSION)
                    }
        ok       <- moveToArchive(con, rudderDit.ACTIVE_TECHNIQUES_LIB.dn, targetArchiveDN)
        finished <- {
                      (for {
                        saved  <- saveUserLib(con, userLib, gitId)
                        system <-if(includeSystem) "OK".succeed
                                   else copyBackSystemEntrie(con, rudderDit.ACTIVE_TECHNIQUES_LIB.dn, targetArchiveDN).chainError("Error when copying back system entries in the imported technique library")
                      } yield {
                        system
                      }).catchAll { e =>
                             logPure.error("Error when trying to load archived active technique library. Rollbaching to previous one.") *>
                             restoreArchive(con, rudderDit.ACTIVE_TECHNIQUES_LIB.dn, targetArchiveDN).foldM(
                               _ => Chained("Error when trying to restore archive with ID '%s' for the active technique library".format(archiveId.value), e).fail
                             , _ => Chained("Error when trying to load archived active technique library. A rollback to previous state was executed", e).fail
                             )
                      }
                    }
      } yield {
        archiveId
      }
    }

    /**
     * Check that the user lib match our global rules:
     * - two UPT can't referenced the same PT (arbitrary skip the second one)
     * - two categories WITH THE SAME PARENT can not have the same name (arbitrary skip the second one)
     * - all ids must be uniques
     * + remove system library if we don't want them
     */
    def checkUserLibConsistance(userLib:ActiveTechniqueCategoryContent) : IOResult[ActiveTechniqueCategoryContent] = {
      import scala.collection.mutable.Map
      import scala.collection.mutable.Set
      val directiveIds = Set[DirectiveId]()
      val ptNames = Map[TechniqueName,ActiveTechniqueId]()
      val uactiveTechniqueIds = Set[ActiveTechniqueId]()
      val categoryIds = Set[ActiveTechniqueCategoryId]()
      // for a name, all Category already containing a child with that name.
      val categoryNamesByParent = Map[String, List[ActiveTechniqueCategoryId]]()

      def sanitizeUPT(uptContent:ActiveTechniqueContent) : Option[ActiveTechniqueContent] = {
        val activeTechnique = uptContent.activeTechnique
        if(activeTechnique.isSystem && includeSystem == false) None
        else if(uactiveTechniqueIds.contains(activeTechnique.id)) {
          logEffect.error("Ignoring Active Technique because is ID was already processed: " + activeTechnique)
          None
        } else ptNames.get(activeTechnique.techniqueName) match {
          case Some(id) =>
            logEffect.error("Ignoring Active Technique with ID '%s' because it references technique with name '%s' already referenced by active technique with ID '%s'".format(
                activeTechnique.id.value, activeTechnique.techniqueName.value, id.value
            ))
            None
          case None => //OK, proccess PIs !
            val sanitizedPis = uptContent.directives.flatMap { directive =>
              if(directive.isSystem && includeSystem == false) None
              else if(directiveIds.contains(directive.id)) {
                logEffect.error("Ignoring following PI because an other PI with the same ID was already processed: " + directive)
                None
              } else {
                directiveIds += directive.id
                Some(directive)
              }
            }

            Some(uptContent.copy(
                activeTechnique = activeTechnique.copy(directives = sanitizedPis.toList.map( _.id ))
              , directives = sanitizedPis
            ))
        }
      }

      def recSanitizeCategory(content:ActiveTechniqueCategoryContent, parent: ActiveTechniqueCategory, isRoot:Boolean = false) : Option[ActiveTechniqueCategoryContent] = {
        val cat = content.category
        if( !isRoot && content.category.isSystem && includeSystem == false) None
        else if(categoryIds.contains(cat.id)) {
          logEffect.error("Ignoring Active Technique Category because its ID was already processed: " + cat)
          None
        } else if(cat.name == null || cat.name.size < 1) {
          logEffect.error("Ignoring Active Technique Category because its name is empty: " + cat)
          None
        } else categoryNamesByParent.get(cat.name) match { //name is mandatory
          case Some(list) if(list.contains(parent.id))=>
            logEffect.error("Ignoring Active Technique Categor with ID '%s' because its name is '%s' already referenced by category '%s' with ID '%s'".format(
                cat.id.value, cat.name, parent.name, parent.id.value
            ))
            None
          case _ => //OK, process PT and sub categories !
            categoryIds += cat.id
            categoryNamesByParent += (cat.name -> (parent.id :: categoryNamesByParent.getOrElse(cat.name, Nil)))

            val subCategories = content.categories.flatMap(c => recSanitizeCategory(c, cat))
            val subUPTs = content.templates.flatMap( sanitizeUPT(_) )

            Some(content.copy(
                category  = cat.copy(
                                children = subCategories.toList.map( _.category.id )
                              , items = subUPTs.toList.map( _.activeTechnique.id )
                            )
              , categories = subCategories.toSet
              , templates  = subUPTs.toSet
            ))
        }
      }

      recSanitizeCategory(userLib, userLib.category, true).notOptional("Error when trying to sanitize serialised user library for consistency errors")
    }

    //all the logic for a library swap.
    for {
      cleanLib <- checkUserLibConsistance(rootCategory)
      moved    <- userLibMutex.writeLock { atomicSwap(cleanLib) }.chainError("Error when swapping serialised library and existing one in LDAP")
      //delete archive - not a real error if fails
      dn       =  rudderDit.ARCHIVES.userLibDN(moved)
      _        <- (for {
                    con <- ldap
                    _   <- con.delete(dn)
                  } yield {
                    ()
                  }).catchAll { e =>
                    // unit is expected
                    logPure.warn(s"Error when deleting archived library in LDAP with DN '${dn}': ${e.msg}")
                  }
    } yield {
      ()
    }
  }
}
