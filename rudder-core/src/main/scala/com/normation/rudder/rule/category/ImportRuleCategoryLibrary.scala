/*
*************************************************************************************
* Copyright 2013 Normation SAS
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

package com.normation.rudder.rule.category

import com.normation.rudder.domain.nodes._
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.queries.Query
import net.liftweb.common._
import com.normation.eventlog.EventActor
import com.normation.utils.HashcodeCaching
import scala.collection.SortedMap
import com.normation.rudder.services.marshalling.DirectiveUnserialisation
import com.normation.rudder.services.marshalling.ActiveTechniqueUnserialisation
import com.normation.rudder.services.marshalling.ActiveTechniqueCategoryUnserialisation
import com.normation.rudder.repository.ParseActiveTechniqueLibrary
import com.normation.utils.XmlUtils
import java.io.File
import java.io.FileInputStream
import com.normation.utils.UuidRegex
import com.normation.utils.Control.sequence
import com.normation.rudder.repository.ImportTechniqueLibrary
import com.normation.rudder.domain.policies.ActiveTechniqueId
import com.normation.rudder.domain.policies.DirectiveId
import com.unboundid.ldap.sdk.RDN
import com.normation.rudder.domain.RudderDit
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.utils.ScalaReadWriteLock
import com.unboundid.ldap.sdk.DN
import com.normation.rudder.domain.policies.ActiveTechniqueCategoryId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.ldap.sdk.RwLDAPConnection
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import com.normation.rudder.domain.RudderLDAPConstants._
import com.normation.inventory.ldap.core.LDAPConstants.A_OC
import com.normation.ldap.sdk.GeneralizedTime
import com.normation.rudder.repository.ImportGroupLibrary
import com.normation.rudder.repository.NodeGroupLibraryArchiveId
import com.normation.rudder.domain.policies.GroupTarget
import com.normation.rudder.domain.archives.RuleCategoryArchiveId
import com.normation.rudder.repository.ldap._



trait ImportRuleCategoryLibrary {
  /**
   * That method swap an existing active technique library in LDAP
   * to a new one.
   *
   * In case of error, we try to restore the old technique library.
   */
  def swapRuleCategory(
      newRootCategory : RuleCategory
    , includeSystem   : Boolean = false
  ) : Box[Unit]
}

class ImportRuleCategoryLibraryImpl(
    rudderDit    : RudderDit
  , ldap         : LDAPConnectionProvider[RwLDAPConnection]
  , mapper       : LDAPEntityMapper
  , groupLibMutex: ScalaReadWriteLock //that's a scala-level mutex to have some kind of consistency with LDAP
) extends ImportRuleCategoryLibrary with LDAPImportLibraryUtil {


  /**
   * That method swap an existing active technique library in LDAP
   * to a new one.
   *
   * In case of error, we try to restore the old technique library.
   */
   def swapRuleCategory (
      newRootCategory : RuleCategory
    , includeSystem   : Boolean = false
  ) : Box[Unit] = {
    /*
     * High level behavior:
     * - check that Rule category tree respects global rules
     *   no two categories or group with the same name, etc)
     *   If not, remove duplicates with error logs (because perhaps they are no duplicate,
     *   user will want to know)
     * - move current Rule category tree elsewhere in the LDAP
     *   (with the root and the system)
     * - create back the root
     * - copy back system if kept
     * - save all categories
     * - if everything goes well, delete the backup tree
     * - else, rollback: delete new tree, move back old group tree
     *
     */

    //as far atomic as we can :)
    //don't bother with system and consistency here, it is taken into account elsewhere
    def atomicSwap(rootCategory:RuleCategory) : Box[RuleCategoryArchiveId] = {
      //save the new one
      //we need to keep the git commit id
      def saveUserLib(con:RwLDAPConnection) : Box[Unit] = {
        def recSaveUserLib(parentDN:DN, content:RuleCategory) : Box[Unit] = {
          //start with the category
          //then recurse on sub-categories
          val categoryEntry =  mapper.ruleCategory2ldap(content,parentDN)
          for {
            category      <- con.save(categoryEntry) ?~! s"Error when persisting category with DN '${categoryEntry.dn}' in LDAP"
            subCategories <- sequence(content.childs) { cat =>
                               recSaveUserLib(categoryEntry.dn, cat)
                             }
          } yield {
            () // unit is expected
          }
        }

        recSaveUserLib(rudderDit.RULECATEGORY.dn.getParent,rootCategory)
      }

      val archiveId = RuleCategoryArchiveId(DateTime.now.toString(ISODateTimeFormat.dateTime))
      val targetArchiveDN = rudderDit.ARCHIVES.RuleCategoryLibDN(archiveId)

      //the sequence of operation to actually perform the swap with rollback
      for {
        con      <- ldap
        archived <- moveToArchive(con, rudderDit.RULECATEGORY.dn, targetArchiveDN)
        finished <- {
                      (for {
                        saved  <- saveUserLib(con)
                        system <-if(includeSystem) Full("OK")
                                   else copyBackSystemEntrie(con, rudderDit.RULECATEGORY.dn, targetArchiveDN) ?~! "Error when copying back system entries in the imported Rule category library"
                      } yield {
                        system
                      }) match {
                           case Full(unit)  => Full(unit)
                           case eb:EmptyBox =>
                             logger.error("Error when trying to load archived Rule category library. Rollbaching to previous one.")
                             restoreArchive(con, rudderDit.GROUP.dn, targetArchiveDN) match {
                               case eb2: EmptyBox => eb ?~! "Error when trying to restore archive with ID '%s' for the active technique library".format(archiveId.value)
                               case Full(_) => eb ?~! "Error when trying to load archived Rule category library. A rollback to previous state was executed"
                             }

                      }
                    }
      } yield {
        archiveId
      }
    }

    /**
     * Check that the root category match our global rules:
     * - two categories can not have the same name while having the same parent (arbitrary skip the second one)
     * - all ids must be uniques
     * + remove system category if we don't want them
     */
    def checkUserLibConsistance(rootCategory:RuleCategory) : Box[RuleCategory] = {
      import scala.collection.mutable.{Set,Map}
      val categoryIds = Set[RuleCategoryId]()
      // for a name, all Category already containing a child with that name.
      val categoryNamesByParent = Map[String, List[RuleCategoryId]]()

      def recSanitizeCategory(cat:RuleCategory, parent:RuleCategory, isRoot:Boolean = false) : Option[RuleCategory] = {
        if( !isRoot && cat.isSystem && includeSystem == false) None
        else if(categoryIds.contains(cat.id)) {
          logger.error(s"Ignoring Rule category because its ID was already processed: ${cat}")
          None
        } else if(cat.name == null || cat.name.size < 1) {
          logger.error(s"Ignoring Rule category because its name is empty: ${cat}")
          None
        } else {
          val parentCategories = categoryNamesByParent.get(cat.name)
          parentCategories match {
            //name is mandatory
            case Some(list) if list.contains(parent.id) =>
              logger.error(s"Ignoring Rule category with ID '${cat.id.value}' because its name is '${cat.name}' already referenced in a child of category with '${parent.name}' of ID '${parent.id.value}'")
              None
            case _ => //OK, process sub categories !
              categoryIds += cat.id
              val currentStatus = parentCategories.getOrElse(Nil)
              categoryNamesByParent += (cat.name -> (parent.id :: currentStatus))

              val subCategories = cat.childs.flatMap( recSanitizeCategory(_,cat) )

              Some(cat.copy(childs = subCategories))
          } }
      }

      Box(recSanitizeCategory(rootCategory, rootCategory, true)) ?~! "Error when trying to sanitize serialised user library for consistency errors"
    }

    //all the logic for a library swap.
    for {
          cleanLib <- checkUserLibConsistance(newRootCategory)
          moved    <- groupLibMutex.writeLock { atomicSwap(cleanLib) } ?~! "Error when swapping serialised library and existing one in LDAP"
        } yield {
          //delete archive - not a real error if fails
          val dn = rudderDit.ARCHIVES.RuleCategoryLibDN(moved)
          (for {
            con     <- ldap
            deleted <- con.delete(dn)
          } yield {
            deleted
          }) match {
            case eb:EmptyBox =>
              logger.warn("Error when deleting archived library in LDAP with DN '%s'".format(dn))
            case _ => //
          }
          () // unit is expected
        }

  }
}