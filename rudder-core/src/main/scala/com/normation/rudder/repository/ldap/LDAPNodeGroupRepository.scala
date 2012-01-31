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

import com.normation.rudder.repository.NodeGroupRepository
import com.normation.rudder.domain.nodes._
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.queries.Query
import net.liftweb.common._
import com.normation.utils.StringUuidGenerator
import com.unboundid.ldap.sdk.{DN,Filter}
import com.normation.ldap.sdk.{LDAPConnectionProvider,LDAPConnection,LDAPEntry,BuildFilter}
import BuildFilter._
import com.normation.rudder.domain.{RudderDit,RudderLDAPConstants}
import com.normation.inventory.ldap.core.LDAPConstants.{A_OC, A_NAME}
import RudderLDAPConstants._
import com.normation.utils.Control.sequence
import com.normation.inventory.ldap.core.LDAPConstants
import com.normation.eventlog.EventActor
import com.normation.rudder.domain.log._
import scala.collection.SortedMap
import com.normation.rudder.domain.policies.GroupTarget
import com.normation.utils.ScalaReadWriteLock
import com.normation.ldap.ldif.LDIFNoopChangeRecord
import com.normation.rudder.services.user.PersonIdentService

class LDAPNodeGroupRepository(
    rudderDit         : RudderDit
  , ldap              : LDAPConnectionProvider
  , mapper            : LDAPEntityMapper
  , diffMapper        : LDAPDiffMapper
  , categoryRepo      : GroupCategoryRepository
  , uuidGen           : StringUuidGenerator
  , actionLogger      : EventLogRepository
  , gitArchiver       : GitNodeGroupArchiver
  , personIdentService: PersonIdentService
  , autoExportOnModify: Boolean 
  , groupLibMutex     : ScalaReadWriteLock //that's a scala-level mutex to have some kind of consistency with LDAP
) extends NodeGroupRepository with Loggable {
	
	/**
   * Look in the group subtree 
   * for and entry with the given id. 
   * We expect at most one result, more is a Failure
   */
  private[this] def getSGEntry[ID](
      con:LDAPConnection, 
      id:ID, 
      filter: ID => Filter,
      attributes:String*) : Box[LDAPEntry] = {
    val srvEntries = con.searchSub(rudderDit.GROUP.dn, filter(id), attributes:_*)
    srvEntries.size match {
      case 0 => Empty
      case 1 => Full(srvEntries(0))
      case _ => Failure("Error, the directory contains multiple occurrence of the server group with ID %s. DNs involved: %s".format(id, srvEntries.map( _.dn).mkString("; ")))
    }     
  }
  
  def getSGEntry(con:LDAPConnection, id:NodeGroupId, attributes:String*) : Box[LDAPEntry] = {
    this.getSGEntry[NodeGroupId](con, id, { id => EQ(A_NODE_GROUP_UUID, id.value) } )
  }
  

  private[this] def getNodeGroup[ID](id: ID, filter: ID => Filter): Box[NodeGroup] = { 
  	for {
      con <- ldap
      sgEntry <- getSGEntry(con, id, filter)
      sg <- mapper.entry2NodeGroup(sgEntry) ?~! "Error when mapping server group entry to its entity. Entry: %s".format(sgEntry)
    } yield {
      sg
    }
  }

  /**
   * Check if a nodeGroup exist with the given name
   */
  private[this] def nodeGroupExists(con:LDAPConnection, name : String) : Boolean = {
    con.searchSub(rudderDit.GROUP.dn, AND(IS(OC_RUDDER_NODE_GROUP), EQ(A_NAME, name)), A_NODE_GROUP_UUID).size match {
      case 0 => false
      case 1 => true
      case _ => logger.error("More than one nodeGroup has %s name".format(name)); true
    }
  }

  /**
   * Check if another nodeGroup exist with the given name
   */
  private[this] def nodeGroupExists(con:LDAPConnection, name : String, id: NodeGroupId) : Boolean = {
    con.searchSub(rudderDit.GROUP.dn, AND(NOT(EQ(A_NODE_GROUP_UUID, id.value)), AND(EQ(A_OC, OC_RUDDER_NODE_GROUP), EQ(A_NAME, name))), A_NODE_GROUP_UUID).size match {
      case 0 => false
      case 1 => true
      case _ => logger.error("More than one nodeGroup has %s name".format(name)); true
    }
  }


  /**
   * Retrieve the category entry for the given ID, with the given connection
   * Used to get the ldap dn
   */
  def getCategoryEntry(con:LDAPConnection, id:NodeGroupCategoryId, attributes:String*) : Box[LDAPEntry] = {
    val categoryEntries = groupLibMutex.readLock {
      con.searchSub(rudderDit.GROUP.dn,  EQ(A_GROUP_CATEGORY_UUID, id.value), attributes:_*)
    }
    categoryEntries.size match {
      case 0 => Empty
      case 1 => Full(categoryEntries(0))
      case _ => Failure("Error, the directory contains multiple occurrence of group category with id %s. DN: %s".format(id, categoryEntries.map( _.dn).mkString("; ")))
    } 
  }
  
  
  def getGroupsByCategory(includeSystem:Boolean = false) : Box[SortedMap[List[NodeGroupCategoryId], CategoryAndNodeGroup]] = {
    groupLibMutex.readLock { for {
      allCats        <- categoryRepo.getAllGroupCategories(includeSystem)
      catsWithGroups <- sequence(allCats) { ligthCat =>
                          for {
                            category <- categoryRepo.getGroupCategory(ligthCat.id)
                            parents  <- categoryRepo.getParents_NodeGroupCategory(category.id)
                            groups   <- (sequence(category.items) { targetInfo => targetInfo.target match {
                                          case group:GroupTarget => this.getNodeGroup(group.groupId).map( Some(_) )
                                          //just ignore other target type
                                          case _ => Full(None)
                                        } }).map( _.flatten )
                            
                          } yield {
                            ( (category.id :: parents.map(_.id)).reverse, CategoryAndNodeGroup(category, groups.toSet))
                          }
                        }
    } yield {
      implicit val ordering = NodeGroupCategoryOrdering
      SortedMap[List[NodeGroupCategoryId], CategoryAndNodeGroup]() ++ catsWithGroups
    } }
  }
  
  def getNodeGroup(id: NodeGroupId): Box[NodeGroup] = { 
 	   groupLibMutex.readLock { this.getNodeGroup[NodeGroupId](id, { id => EQ(A_NODE_GROUP_UUID, id.value) } ) }
  }

  def createNodeGroup(name: String, description: String, q: Option[Query], isDynamic: Boolean, srvList: Set[NodeId], into: NodeGroupCategoryId, isActivated : Boolean, actor:EventActor): Box[AddNodeGroupDiff] = {
	  for {
      con           <- ldap
      exists        <- if (nodeGroupExists(con, name)) Failure("Cannot create a group with name %s : there is already a group with the same name".format(name))
                       else Full(Unit)
      categoryEntry <- getCategoryEntry(con, into) ?~! "Entry with ID '%s' was not found".format(into)
      uuid          = uuidGen.newUuid
      nodeGroup     = NodeGroup(NodeGroupId(uuid), name, description, q, isDynamic, srvList, isActivated, false)
      entry         = rudderDit.GROUP.groupModel(uuid,
      													categoryEntry.dn, 
      													name, 
      													description, 
      													q,
      													isDynamic,
      													srvList,
      													isActivated)
      result        <- groupLibMutex.writeLock { con.save(entry, true) }
      diff          <- diffMapper.addChangeRecords2NodeGroupDiff(entry.dn, result)
      loggedAction  <- actionLogger.saveAddNodeGroup(principal = actor, addDiff = diff )
      autoArchive   <- if(autoExportOnModify && !result.isInstanceOf[LDIFNoopChangeRecord]) {
                         for {
                           parents  <- categoryRepo.getParents_NodeGroupCategory(into)
                           commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                           archived <- gitArchiver.archiveNodeGroup(nodeGroup, into :: (parents.map( _.id)), Some(commiter))
                         } yield archived
                       } else Full("ok")
  	} yield {
  		diff
  	} 
  }
  
  def update(nodeGroup:NodeGroup, actor:EventActor): Box[Option[ModifyNodeGroupDiff]] = {
    for {
      con          <- ldap
      existing     <- getSGEntry(con, nodeGroup.id) ?~! "Error when trying to check for existence of group with id %s. Can not update".format(nodeGroup.id)
      exists       <- if (nodeGroupExists(con, nodeGroup.name, nodeGroup.id)) Failure("Cannot change the group name to %s : there is already a group with the same name".format(nodeGroup.name))
                      else Full(Unit)
      entry        =  rudderDit.GROUP.groupModel(
                                nodeGroup.id.value,
                                existing.dn.getParent,
                                nodeGroup.name,
                                nodeGroup.description,
                                nodeGroup.query,
                                nodeGroup.isDynamic,
                                nodeGroup.serverList,
                                nodeGroup.isActivated,
                                nodeGroup.isSystem)
      result       <- groupLibMutex.writeLock { con.save(entry, true) ?~! "Error when saving entry: %s".format(entry) }
      optDiff      <- diffMapper.modChangeRecords2NodeGroupDiff(existing, result) ?~! "Error when mapping change record to a diff object: %s".format(result)
      loggedAction <- optDiff match {
                        case None => Full("OK")
                        case Some(diff) => actionLogger.saveModifyNodeGroup(principal = actor, modifyDiff = diff) ?~! "Error when logging modification as an event"
                      }
      autoArchive  <- if(autoExportOnModify && optDiff.isDefined) { //only persists if modification are present
                        for {
                          parent   <- getParentGroupCategory(nodeGroup.id)
                          parents  <- categoryRepo.getParents_NodeGroupCategory(parent.id)
                          commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                          archived <- gitArchiver.archiveNodeGroup(nodeGroup, (parent :: parents).map( _.id), Some(commiter))
                        } yield archived
                      } else Full("ok")
    } yield {
      optDiff
    } 
  }

  def move(nodeGroup:NodeGroup, containerId : NodeGroupCategoryId, actor:EventActor): Box[Option[ModifyNodeGroupDiff]] = {
    for {
      con          <- ldap
      oldParents   <- if(autoExportOnModify) {
                        for {
                          parent   <- getParentGroupCategory(nodeGroup.id)
                          parents  <- categoryRepo.getParents_NodeGroupCategory(parent.id)
                        } yield (parent::parents).map( _.id )
                      } else Full(Nil)
      existing     <- getSGEntry(con, nodeGroup.id) ?~! "Error when trying to check for existence of group with id %s. Can not update".format(nodeGroup.id)
      groupRDN     <- Box(existing.rdn) ?~! "Error when retrieving RDN for an exising group - seems like a bug"
      exists       <- if (nodeGroupExists(con, nodeGroup.name, nodeGroup.id)) Failure("Cannot change the group name to %s : there is already a group with the same name".format(nodeGroup.name))
                        else Full(Unit)
      newParentDn  <- getContainerDn(con, containerId) ?~! "Couldn't find the new parent category when updating group %s".format(nodeGroup.name)
      result       <- groupLibMutex.writeLock { con.move(existing.dn, newParentDn) }
      optDiff      <- diffMapper.modChangeRecords2NodeGroupDiff(existing, result)
      loggedAction <- optDiff match {
                        case None => Full("OK")
                        case Some(diff) => actionLogger.saveModifyNodeGroup(principal = actor, modifyDiff = diff )
                      }
      autoArchive   <- (if(autoExportOnModify && optDiff.isDefined) { //only persists if that was a real move (not a move in the same category)
                         for {
                           newGroup <- getNodeGroup(nodeGroup.id)
                           parent   <- getParentGroupCategory(nodeGroup.id)
                           parents  <- categoryRepo.getParents_NodeGroupCategory(parent.id)
                           commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                           moved    <- gitArchiver.moveNodeGroup(newGroup, oldParents, (parent::parents).map( _.id ), Some(commiter))
                         } yield {
                           moved
                         }
                       } else Full("ok") ) ?~! "Error when trying to archive automatically the category move"
    } yield {
      optDiff
    }
  }
  
  /**
   * Fetch the parent category of the NodeGroup
   * Caution, its a lightweight version of the entry (no children nor item)
   * @param id
   * @return
   */
  def getParentGroupCategory(id: NodeGroupId): Box[NodeGroupCategory] = { 
  	 groupLibMutex.readLock { for {
      con <- ldap
      groupEntry <- getSGEntry(con, id, "1.1") ?~! "Entry with ID '%s' was not found".format(id)
      parentCategoryEntry <- con.get(groupEntry.dn.getParent)
      parentCategory <- mapper.entry2NodeGroupCategory(parentCategoryEntry) ?~! "Error when transforming LDAP entry %s into a user policy template category".format(parentCategoryEntry)
    } yield {
      parentCategory
    } }
  }
  
  def getAll : Box[Seq[NodeGroup]] = {
    groupLibMutex.readLock { for {
      con <- ldap
      //for each pi entry, map it. if one fails, all fails
      groups <- sequence(con.searchSub(rudderDit.GROUP.dn,  EQ(A_OC, OC_RUDDER_NODE_GROUP))) { groupEntry => 
        mapper.entry2NodeGroup(groupEntry) ?~! "Error when transforming LDAP entry into a Group instance. Entry: %s".format(groupEntry)
      }
    } yield {
      groups
    } }
  }
  
  private[this] def getContainerDn(con : LDAPConnection, id: NodeGroupCategoryId) : Box[DN] = {
    groupLibMutex.readLock { con.searchSub(rudderDit.GROUP.dn, AND(IS(OC_GROUP_CATEGORY), EQ(A_GROUP_CATEGORY_UUID, id.value)), A_GROUP_CATEGORY_UUID).toList match {
      case Nil => Empty
      case (head : com.normation.ldap.sdk.LDAPEntry) :: Nil => Full(head.dn)
      case _ => logger.error("Too many NodeGroupCategory found with this id %s".format(id.value))
                Failure("Too many NodeGroupCategory found with this id %s".format(id.value))
    } }
  }
  
  
  /**
   * Delete the given nodeGroup. 
   * If no nodegroup has such id in the directory, return a success. 
   * @param id
   * @return
   */
  def delete(id:NodeGroupId, actor:EventActor) : Box[DeleteNodeGroupDiff] = {
    for {
      con          <- ldap
      parents      <- if(autoExportOnModify) {
                        for {
                          parent   <- getParentGroupCategory(id)
                          parents  <- categoryRepo.getParents_NodeGroupCategory(parent.id)
                        } yield {
                          (parent :: parents ) map ( _.id )
                        }
                      } else Full(Nil)
      existing     <- getSGEntry(con, id) ?~! "Error when trying to check for existence of group with id %s. Can not update".format(id)
      oldGroup     <- mapper.entry2NodeGroup(existing)
      deleted      <- {
                        getSGEntry(con,id, "1.1") match {
                          case Full(entry) => {
                            for {
                              deleted <- groupLibMutex.writeLock { con.delete(entry.dn, recurse = false) }
                            } yield {
                              id
                            }
                          }
                          case Empty => Full(id)
                          case f:Failure => f
                        }
                      } 
      diff         =  DeleteNodeGroupDiff(oldGroup)
      loggedAction <- actionLogger.saveDeleteNodeGroup(principal = actor, deleteDiff = diff ) ?~! "Error when saving user event log for node deletion"
      autoArchive  <- if(autoExportOnModify) {
                        for {
                          commiter <- personIdentService.getPersonIdentOrDefault(actor.name)
                          archive  <- gitArchiver.deleteNodeGroup(id, parents, Some(commiter))
                        } yield {
                          archive
                        }
                      } else Full("ok")
    } yield {
      diff
    }
  }
 
  
  private[this] def findGroupWithFilter(filter:Filter) : Box[Seq[NodeGroupId]] = {
    groupLibMutex.readLock { for {
      con <- ldap
      groupIds <- sequence(con.searchSub(rudderDit.GROUP.dn,  filter, "1.1")) { entry =>
        rudderDit.GROUP.getGroupId(entry.dn) ?~! "DN '%s' seems to not be a valid group DN".format(entry.dn)
      }
    } yield {
      groupIds.map(id => NodeGroupId(id))
    } }
  }
  
  /**
   * Retrieve all groups that have at least one of the given
   * node ID in there member list.
   * @param nodeIds
   * @return
   */
  def findGroupWithAnyMember(nodeIds:Seq[NodeId]) : Box[Seq[NodeGroupId]] = {
    val filter = AND(
       IS(OC_RUDDER_NODE_GROUP),
       OR(nodeIds.distinct.map(nodeId => EQ(LDAPConstants.A_NODE_UUID, nodeId.value)):_*)
    )
    findGroupWithFilter(filter)
  }
  
  /**
   * Retrieve all groups that have ALL given node ID in their
   * member list.
   * @param nodeIds
   * @return
   */
  def findGroupWithAllMember(nodeIds:Seq[NodeId]) : Box[Seq[NodeGroupId]] = {
    val filter = AND(
       IS(OC_RUDDER_NODE_GROUP),
       AND(nodeIds.distinct.map(nodeId => EQ(LDAPConstants.A_NODE_UUID, nodeId.value)):_*)
    )
    findGroupWithFilter(filter)
  }
  
}