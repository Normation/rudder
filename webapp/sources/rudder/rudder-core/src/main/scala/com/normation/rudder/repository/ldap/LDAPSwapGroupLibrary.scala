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
import com.normation.NamedZioLogger
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.RudderLDAPConstants._
import com.normation.rudder.domain.nodes._
import com.normation.rudder.domain.policies.GroupTarget
import com.normation.rudder.repository.ImportGroupLibrary
import com.normation.rudder.repository.NodeGroupCategoryContent
import com.normation.rudder.repository.NodeGroupLibraryArchiveId
import com.unboundid.ldap.sdk.DN
import com.unboundid.ldap.sdk.RDN
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import com.normation.errors._
import scalaz.zio._
import scalaz.zio.syntax._

trait LDAPImportLibraryUtil extends NamedZioLogger {

  //move user lib to archive branch
  def moveToArchive(connection:RwLDAPConnection, sourceLibraryDN:DN, targetArchiveDN:DN) : IOResult[Unit] = {
    for {
      ok <- connection.move(sourceLibraryDN, targetArchiveDN.getParent, Some(targetArchiveDN.getRDN)) .chainError("Error when arching current Library with DN '%s' to LDAP".format(targetArchiveDN))
    } yield {
      {}
    }
  }

  //copy back system categories/groups if includeSystem is FALSE
  def copyBackSystemEntrie(con:RwLDAPConnection, sourceLibraryDN:DN, targetArchiveDN:DN) : IOResult[Unit] = {
    //the only hard part could be for system group in non system categories, because
    //we may miss a parent. But it should not be allowed, so we consider such cases
    //as errors
    import com.normation.ldap.sdk.BuildFilter.EQ
    import com.normation.ldap.sdk._


    //a method that change the root of a dn from archive to user lib root
    def setUserLibRoot(dn:DN) : Option[DN] = {
      def recParent(x:DN) : Option[List[RDN]] = {
        if(null == x) None
        else if(x == targetArchiveDN) Some(Nil)
        else recParent(x.getParent).map( x.getRDN :: _ )
      }

      def recBuildDN(root:DN, rdns:List[RDN]) : DN = rdns match {
        case Nil => root
        case h::t => recBuildDN(new DN(h,root),t)
      }

      val relatives = recParent(dn)
      relatives.map( rdns => recBuildDN(sourceLibraryDN, rdns.reverse))
    }

    for {
      entries <- con.searchSub(targetArchiveDN, EQ(A_IS_SYSTEM,true.toLDAPString))
      allDNs  =  entries.map( _.dn ).toSet
      //update DN to UserLib DN, remove root entry and entries without parent in that set
      updatedDNEntries =  (entries.collect {
                                case entry if(entry.dn == targetArchiveDN) =>
                                  logEffect.trace("Skipping root entry, already taken into account")
                                  None
                                case entry if(allDNs.exists( _ == entry.dn.getParent)) =>
                                  //change the DN to user lib
                                  setUserLibRoot(entry.dn) match {
                                    case None =>
                                      logEffect.error("Ignoring entry with DN '%s' because it does not belong to archive '%s'".format(entry.dn, targetArchiveDN))
                                      None
                                    case Some(dn) =>
                                      Some(LDAPEntry(dn, entry.attributes))
                                  }
                                case entry =>
                                  logEffect.error("Error when trying to save entry '%s' marked as system: its parent is not available, perhaps it is not marked as system?".format(entry.dn))
                                  None
                              }).flatten
     //actually save system entries in User Lib
       _ <- ZIO.foreach(updatedDNEntries.sortWith( (x,y) => DN.compare(x.dn.toString, y.dn.toString) < 0)) { entry =>
              con.save(entry) .chainError("Error when copying back system entry '%s' from archive '%s'".format(entry.dn, targetArchiveDN))
            }
    } yield {
      ()
    }
  }

  //restore in case of error
  def restoreArchive(con:RwLDAPConnection, sourceLibraryDN:DN, targetArchiveDN:DN) : IOResult[Unit] = {
    for {
      exists    <- con.exists(sourceLibraryDN)
      delete    <- if(exists) {
                     con.delete(sourceLibraryDN)
                   } else "ok".succeed
      movedBack <- con.move(targetArchiveDN, sourceLibraryDN.getParent, Some(sourceLibraryDN.getRDN))
    } yield {
      () // unit is expected
    }
  }

}

class ImportGroupLibraryImpl(
    rudderDit    : RudderDit
  , ldap         : LDAPConnectionProvider[RwLDAPConnection]
  , mapper       : LDAPEntityMapper
  , groupLibMutex: ScalaReadWriteLock //that's a scala-level mutex to have some kind of consistency with LDAP
) extends ImportGroupLibrary with LDAPImportLibraryUtil {

  override def loggerName: String = this.getClass.getName

  /**
   * That method swap an existing active technique library in LDAP
   * to a new one.
   *
   * In case of error, we try to restore the old technique library.
   */
  def swapGroupLibrary(rootCategory:NodeGroupCategoryContent, includeSystem:Boolean = false) : IOResult[Unit] = {
    /*
     * Hight level behaviour:
     * - check that Group Library respects global rules
     *   no two categories or group with the same name, etc)
     *   If not, remove duplicates with error logs (because perhaps they are no duplicate,
     *   user will want to know)
     * - move current group lib elsewhere in the LDAP
     *   (with the root and the system)
     * - create back the root
     * - copy back system if kept
     * - save all categories, groups
     * - if everything goes well, delete the old group library
     * - else, rollback: delete new group lib, move back old group lib
     *
     */

    //as far atomic as we can :)
    //don't bother with system and consistency here, it is taken into account elsewhere
    def atomicSwap(userLib:NodeGroupCategoryContent) : IOResult[NodeGroupLibraryArchiveId] = {
      //save the new one
      //we need to keep the git commit id
      def saveUserLib(con:RwLDAPConnection, userLib:NodeGroupCategoryContent) : IOResult[Unit] = {
        def recSaveUserLib(parentDN:DN, content:NodeGroupCategoryContent) : IOResult[Unit] = {
          //start with the category
          //then with technique/directive for that category
          //then recurse on sub-categories
          val categoryEntry = mapper.nodeGroupCategory2ldap(content.category, parentDN)

          for {
            category      <- con.save(categoryEntry) .chainError("Error when persisting category with DN '%s' in LDAP".format(categoryEntry.dn))
            groups        <- ZIO.foreach(content.groups) { nodeGroup =>
                               val nodeGroupEntry = rudderDit.GROUP.groupModel(
                                  nodeGroup.id.value,
                                  categoryEntry.dn,
                                  nodeGroup.name,
                                  nodeGroup.description,
                                  nodeGroup.query,
                                  nodeGroup.isDynamic,
                                  nodeGroup.serverList,
                                  nodeGroup.isEnabled,
                                  nodeGroup.isSystem
                               )

                               con.save(nodeGroupEntry,true) .chainError("Error when persisting group entry with DN '%s' in LDAP".format(nodeGroupEntry.dn))
                             }
            subCategories <- ZIO.foreach(content.categories) { cat =>
                               recSaveUserLib(categoryEntry.dn, cat)
                             }
          } yield {
            () // unit is expected
          }
        }

        recSaveUserLib(rudderDit.GROUP.dn.getParent,userLib)
      }

      val archiveId = NodeGroupLibraryArchiveId(DateTime.now().toString(ISODateTimeFormat.dateTime))
      val targetArchiveDN = rudderDit.ARCHIVES.groupLibDN(archiveId)

      //the sequence of operation to actually perform the swap with rollback
      for {
        con      <- ldap
        archived <- moveToArchive(con, rudderDit.GROUP.dn, targetArchiveDN)
        finished <- {
                      (for {
                        saved  <- saveUserLib(con, userLib)
                        system <- if(includeSystem) "OK".succeed
                                  else copyBackSystemEntrie(con, rudderDit.GROUP.dn, targetArchiveDN) .chainError("Error when copying back system entries in the imported library")
                      } yield {
                        system
                      }).catchAll { e =>
                        logPure.error("Error when trying to load archived active technique library. Rollbaching to previous one.") *>
                        restoreArchive(con, rudderDit.GROUP.dn, targetArchiveDN).foldM(
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
     * - two NodeGroup can't referenced the same PT (arbitrary skip the second one)
     * - two categories WITH THE SAME PARENT can not have the same name (arbitrary skip the second one)
     * - all ids must be uniques
     * + remove system library if we don't want them
     */
    def checkUserLibConsistance(userLib:NodeGroupCategoryContent) : IOResult[NodeGroupCategoryContent] = {
      import scala.collection.mutable.Map
      import scala.collection.mutable.Set
      val nodeGroupIds = Set[NodeGroupId]()
      val nodeGroupNames = Map[String, NodeGroupId]()
      val categoryIds = Set[NodeGroupCategoryId]()
      // for a name, all Category already containing a child with that name.
      val categoryNamesByParent = Map[String, List[NodeGroupCategoryId]]()

      def sanitizeNodeGroup(nodeGroup:NodeGroup) : Option[NodeGroup] = {

        if(nodeGroup.isSystem && includeSystem == false) None
        else if(nodeGroupIds.contains(nodeGroup.id)) {
          logEffect.error("Ignoring Active Technique because is ID was already processed: " + nodeGroup)
          None
        } else nodeGroupNames.get(nodeGroup.name) match {
          case Some(id) =>
            logEffect.error("Ignoring Active Technique with ID '%s' because it references technique with name '%s' already referenced by active technique with ID '%s'".format(
                nodeGroup.id.value, nodeGroup.name, id.value
            ))
            None
          case None =>
            Some(nodeGroup)
        }
      }

      def recSanitizeCategory(content:NodeGroupCategoryContent, parent: NodeGroupCategory, isRoot:Boolean = false) : Option[NodeGroupCategoryContent] = {
        val cat = content.category
        if( !isRoot && content.category.isSystem && includeSystem == false) None
        else if(categoryIds.contains(cat.id)) {
          logEffect.error("Ignoring Active Technique Category because its ID was already processed: " + cat)
          None
        } else if(cat.name == null || cat.name.size < 1) {
          logEffect.error("Ignoring Active Technique Category because its name is empty: " + cat)
          None
        } else categoryNamesByParent.get(cat.name) match { //name is mandatory
          case Some(list) if list.contains(parent.id) =>
            logEffect.error("Ignoring Active Technique Categor with ID '%s' because its name is '%s' already referenced by category '%s' with ID '%s'".format(
                cat.id.value, cat.name, parent.name, parent.id.value
            ))
            None
          case _ => //OK, process PT and sub categories !
            categoryIds += cat.id
            categoryNamesByParent += (cat.name -> (parent.id :: categoryNamesByParent.getOrElse(cat.name, Nil)))

            val subCategories = content.categories.flatMap(c => recSanitizeCategory(c, cat) ).toSet
            val subNodeGroups = content.groups.flatMap( sanitizeNodeGroup(_) ).toSet

            //remove from sub cat groups that where not correct
            val directiveTargetInfos = cat.items.filter { info =>
              info.target match {
                case GroupTarget(id) => subNodeGroups.exists(g => g.id == id )
                case x => true
              }
            }

            Some(content.copy(
                category  = cat.copy(
                                children = subCategories.toList.map( _.category.id )
                              , items = directiveTargetInfos
                            )
              , categories = subCategories
              , groups     = subNodeGroups
            ))
        }
      }

      recSanitizeCategory(userLib, userLib.category, true).notOptional("Error when trying to sanitize serialised user library for consistency errors")
    }

    //all the logic for a library swap.
    for {
      cleanLib <- checkUserLibConsistance(rootCategory)
      moved    <- groupLibMutex.writeLock { atomicSwap(cleanLib) } .chainError("Error when swapping serialised library and existing one in LDAP")
    } yield {
      //delete archive - not a real error if fails
      val dn = rudderDit.ARCHIVES.groupLibDN(moved)
      (for {
        con     <- ldap
        deleted <- con.delete(dn)
      } yield {
        deleted
      }).catchAll(e =>
          // unit is expected
          logPure.warn(s"Error when deleting archived library in LDAP with DN '${dn}': ${e.msg}")
      )
    }
  }
}
