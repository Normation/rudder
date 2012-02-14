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

package com.normation.rudder.repository.ldap

import com.normation.rudder.domain.nodes._
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.queries.Query
import net.liftweb.common._
import com.normation.eventlog.EventActor
import com.normation.utils.HashcodeCaching
import scala.collection.SortedMap
import com.normation.rudder.services.marshalling.PolicyInstanceUnserialisation
import com.normation.rudder.services.marshalling.UserPolicyTemplateUnserialisation
import com.normation.rudder.services.marshalling.UserPolicyTemplateCategoryUnserialisation
import com.normation.rudder.repository.ParsePolicyLibrary
import com.normation.utils.XmlUtils
import com.normation.rudder.repository.NodeGroupCategoryContent
import java.io.File
import java.io.FileInputStream
import com.normation.utils.UuidRegex
import com.normation.utils.Control.sequence
import com.normation.rudder.repository.ImportPolicyLibrary
import com.normation.rudder.domain.policies.UserPolicyTemplateId
import com.normation.rudder.domain.policies.PolicyInstanceId
import com.unboundid.ldap.sdk.RDN
import com.normation.rudder.domain.RudderDit
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.utils.ScalaReadWriteLock
import com.unboundid.ldap.sdk.DN
import com.normation.rudder.domain.policies.UserPolicyTemplateCategoryId
import com.normation.cfclerk.domain.PolicyPackageName
import com.normation.ldap.sdk.LDAPConnection
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import com.normation.rudder.domain.RudderLDAPConstants._
import com.normation.inventory.ldap.core.LDAPConstants.A_OC
import com.normation.ldap.sdk.GeneralizedTime
import com.normation.rudder.repository.NodeGroupCategoryContent
import com.normation.rudder.repository.ImportGroupLibrary
import com.normation.rudder.repository.NodeGroupLibraryArchiveId
import com.normation.rudder.domain.policies.GroupTarget



trait LDAPImportLibraryUtil extends Loggable {
  
  //move user lib to archive branch
  def moveToArchive(connection:LDAPConnection, sourceLibraryDN:DN, targetArchiveDN:DN) : Box[Unit] = {
    for {
      ok <- connection.move(sourceLibraryDN, targetArchiveDN.getParent, Some(targetArchiveDN.getRDN)) ?~! "Error when arching current Library with DN '%s' to LDAP".format(targetArchiveDN)
    } yield {
      {}
    }
  }
  
  //copy back system categories/groups if includeSystem is FALSE 
  def copyBackSystemEntrie(con:LDAPConnection, sourceLibraryDN:DN, targetArchiveDN:DN) : Box[Unit] = {
    //the only hard part could be for system group in non system categories, because
    //we may miss a parent. But it should not be allowed, so we consider such cases
    //as errors
    import com.normation.ldap.sdk._
    import com.normation.ldap.sdk.BuildFilter.EQ
  
        
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
    
    val entries = con.searchSub(targetArchiveDN, EQ(A_IS_SYSTEM,true.toLDAPString))
    val allDNs  =  entries.map( _.dn ).toSet
    //update DN to UserLib DN, remove root entry and entries without parent in that set
    val updatedDNEntries =  (entries.collect {
                              case entry if(entry.dn == targetArchiveDN) => 
                                logger.trace("Skipping root entry, already taken into account")
                                None
                              case entry if(allDNs.exists( _ == entry.dn.getParent)) =>
                                //change the DN to user lib
                                setUserLibRoot(entry.dn) match {
                                  case None => 
                                    logger.error("Ignoring entry with DN '%s' because it does not belong to archive '%s'".format(entry.dn, targetArchiveDN))
                                    None
                                  case Some(dn) => 
                                    Some(LDAPEntry(dn, entry.attributes))
                                }
                              case entry =>
                                logger.error("Error when trying to save entry '%s' marked as system: its parent is not available, perhaps it is not marked as system?".format(entry.dn))
                                None
                            }).flatten
                            
     //actually save system entries in User Lib
     (sequence(updatedDNEntries.sortWith( (x,y) => DN.compare(x.dn.toString, y.dn.toString) < 0)) { 
       entry => con.save(entry) ?~! "Error when copying back system entry '%s' from archive '%s'".format(entry.dn, targetArchiveDN)
     }).map { x => ; /*unit*/}
  }
  
  //restore in case of error
  def restoreArchive(con:LDAPConnection, sourceLibraryDN:DN, targetArchiveDN:DN) : Box[Unit] = {
    for {
      delete    <- if(con.exists(sourceLibraryDN)) {
                     con.delete(sourceLibraryDN)
                   } else Full("ok")
      movedBack <- con.move(targetArchiveDN, sourceLibraryDN.getParent, Some(sourceLibraryDN.getRDN))
    } yield {
      movedBack
    }
  }

}

class ImportGroupLibraryImpl(
    rudderDit    : RudderDit
  , ldap         : LDAPConnectionProvider
  , mapper       : LDAPEntityMapper
  , groupLibMutex: ScalaReadWriteLock //that's a scala-level mutex to have some kind of consistency with LDAP
) extends ImportGroupLibrary with LDAPImportLibraryUtil {
  
  
  /**
   * That method swap an existing user policy library in LDAP
   * to a new one. 
   * 
   * In case of error, we try to restore the old policy library. 
   */
  def swapGroupLibrary(rootCategory:NodeGroupCategoryContent, includeSystem:Boolean = false) : Box[Unit] = {
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
    def atomicSwap(userLib:NodeGroupCategoryContent) : Box[NodeGroupLibraryArchiveId] = {
      //save the new one
      //we need to keep the git commit id
      def saveUserLib(con:LDAPConnection, userLib:NodeGroupCategoryContent) : Box[Unit] = {
        def recSaveUserLib(parentDN:DN, content:NodeGroupCategoryContent) : Box[Unit] = {
          //start with the category
          //then with pt/pi for that category
          //then recurse on sub-categories
          val categoryEntry = mapper.nodeGroupCategory2ldap(content.category, parentDN)
          
          for {
            category      <- con.save(categoryEntry) ?~! "Error when persisting category with DN '%s' in LDAP".format(categoryEntry.dn)
            groups        <- sequence(content.groups.toSeq) { nodeGroup =>
                               val nodeGroupEntry = rudderDit.GROUP.groupModel(
                                  nodeGroup.id.value,
                                  categoryEntry.dn,
                                  nodeGroup.name,
                                  nodeGroup.description,
                                  nodeGroup.query,
                                  nodeGroup.isDynamic,
                                  nodeGroup.serverList,
                                  nodeGroup.isActivated,
                                  nodeGroup.isSystem
                               )
                               
                               con.save(nodeGroupEntry,true) ?~! "Error when persisting group entry with DN '%s' in LDAP".format(nodeGroupEntry.dn)
                             }
            subCategories <- sequence(content.categories.toSeq) { cat => 
                               recSaveUserLib(categoryEntry.dn, cat)
                             }
          } yield {
            "OK"
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
                        system <-if(includeSystem) Full("OK") 
                                   else copyBackSystemEntrie(con, rudderDit.GROUP.dn, targetArchiveDN) ?~! "Error when copying back system entries in the imported library"
                      } yield {
                        system
                      }) match {
                           case Full(unit)  => Full(unit)
                           case eb:EmptyBox => 
                             logger.error("Error when trying to load archived User Policy Library. Rollbaching to previous one.")
                             restoreArchive(con, rudderDit.GROUP.dn, targetArchiveDN) match {
                               case eb2: EmptyBox => eb ?~! "Error when trying to restore archive with ID '%s' for the user policy template library".format(archiveId.value)
                               case Full(_) => eb ?~! "Error when trying to load archived User Policy Library. A rollback to previous state was executed"
                             }
                             
                      }
                    }
      } yield {
        archiveId
      }
    }
    
    /**
     * Check that the user lib match our global rules:
     * - two NodeGroup can't referenced the same PT (arbitrary skip the second one)
     * - two categories can not have the same name (arbitrary skip the second one)
     * - all ids must be uniques
     * + remove system library if we don't want them
     */
    def checkUserLibConsistance(userLib:NodeGroupCategoryContent) : Box[NodeGroupCategoryContent] = {
      import scala.collection.mutable.{Set,Map}
      val nodeGroupIds = Set[NodeGroupId]()
      val nodeGroupNames = Map[String, NodeGroupId]()
      val categoryIds = Set[NodeGroupCategoryId]()
      val categoryNames = Map[String, NodeGroupCategoryId]()
      
      def sanitizeNodeGroup(nodeGroup:NodeGroup) : Option[NodeGroup] = {

        if(nodeGroup.isSystem && includeSystem == false) None
        else if(nodeGroupIds.contains(nodeGroup.id)) {
          logger.error("Ignoring User Policy Template because is ID was already processed: " + nodeGroup)
          None
        } else nodeGroupNames.get(nodeGroup.name) match {
          case Some(id) =>
            logger.error("Ignoring User Policy Template with ID '%s' because it references policy template with name '%s' already referenced by user policy template with ID '%s'".format(
                nodeGroup.id.value, nodeGroup.name, id.value
            ))
            None
          case None => 
            Some(nodeGroup)
        }
      }
      
      def recSanitizeCategory(content:NodeGroupCategoryContent, isRoot:Boolean = false) : Option[NodeGroupCategoryContent] = {
        val cat = content.category
        if( !isRoot && content.category.isSystem && includeSystem == false) None
        else if(categoryIds.contains(cat.id)) {
          logger.error("Ignoring User Policy Template Category because its ID was already processed: " + cat)
          None
        } else if(cat.name == null || cat.name.size < 1) {
          logger.error("Ignoring User Policy Template Category because its name is empty: " + cat)
          None
        } else categoryNames.get(cat.name) match { //name is mandatory
          case Some(id) =>
            logger.error("Ignoring User Policy Template Categor with ID '%s' because its name is '%s' already referenced by category with ID '%s'".format(
                cat.id.value, cat.name, id.value
            ))
            None
          case None => //OK, process PT and sub categories !
            categoryIds += cat.id
            categoryNames += (cat.name -> cat.id)
            
            val subCategories = content.categories.flatMap( recSanitizeCategory(_) ).toSet
            val subNodeGroups = content.groups.flatMap( sanitizeNodeGroup(_) ).toSet
            
            //remove from sub cat groups that where not correct
            val policyInstanceTargetInfos = cat.items.filter { info => 
              info.target match {
                case GroupTarget(id) => subNodeGroups.exists(g => g.id == id )
                case x => true
              }
            }
            
            Some(content.copy(
                category  = cat.copy(
                                children = subCategories.toList.map( _.category.id )
                              , items = policyInstanceTargetInfos
                            )
              , categories = subCategories
              , groups     = subNodeGroups
            ))
        }
      }
      
      Box(recSanitizeCategory(userLib, true)) ?~! "Error when trying to sanitize serialised user library for consistency errors"
    }
        
    //all the logic for a library swap.
    for {
      cleanLib <- checkUserLibConsistance(rootCategory)
      moved    <- groupLibMutex.writeLock { atomicSwap(cleanLib) } ?~! "Error when swapping serialised library and existing one in LDAP"
    } yield {
      //delete archive - not a real error if fails
      val dn = rudderDit.ARCHIVES.groupLibDN(moved)
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
      moved
    }
    
  }
}