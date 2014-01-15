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

package com.normation.rudder.services.nodes

import com.unboundid.ldap.sdk.{LDAPConnection => ULDAPConnection, _ }
import com.normation.ldap.sdk._
import BuildFilter._
import com.normation.utils.Control._
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.inventory.ldap.core.LDAPConstants
import com.normation.inventory.ldap.core.LDAPConstants._
import com.normation.inventory.domain._
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.NodeDit
import com.normation.rudder.domain.RudderLDAPConstants._
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.services.nodes.NodeInfoServiceImpl.nodeInfoAttributes

import com.normation.rudder.domain.Constants._
import com.normation.rudder.repository.ldap.LDAPEntityMapper

import org.joda.time.DateTime

import net.liftweb.common._
import net.liftweb.util.Helpers._
import scala.collection.JavaConversions._

/**
 * A service that allows to query for
 * nodes based on a part of some of their
 * information, typically the id, hostname, ip,
 * etc.
 *
 * That service is typically used in autocomplete
 * UI inputs.
 */
trait QuickSearchService {

  /**
   * Try to retrieve a list of server based of the given information.
   * The list may be empty if there is no server, or if the provided
   * information does not reach some lower bound (too short, only spaces, etc)
   */
  def lookup(partialInfo:String, maxResult:Int) : Box[Seq[NodeInfo]]

}

class QuickSearchServiceImpl(
    ldap            : LDAPConnectionProvider[RoLDAPConnection] //manage LDAP connection
  , nodeDit         : NodeDit                //LDAP structure of nodes branch
  , inventoryDit    : InventoryDit           //LDAP structure of inventory branch
  , mapper          : LDAPEntityMapper       //map LDAP entries to domain object
  , nodeAttributes  : Seq[String]            //attributes of Node entries in node branch to scan for match
  , serverAttributes: Seq[String]            //attributes of NodeInventorory entries in inventory branch to scan for match
  , timeLimit       : Int = 5                //LDAP server max search time, in secondes
) extends QuickSearchService {

  /**
   * Try to retrieve a list of server based of the given information.
   * The list may be empty if there is no server, or if the provided
   * information does not reach some lower bound (too short, only spaces, etc)
   */
  def lookup(in:String, maxResult:Int) : Box[Seq[NodeInfo]] = {

    if(validateInput(in))
      searchNodesInLdap(in, maxResult)
    else
      Full(Seq())
  }

  def searchNodesInLdap(in : String, maxResult: Int) : Box[Seq[NodeInfo]] = {
    for {
        con                <- ldap

        nodeEntries        <- search(con, nodeDit.NODES.dn, maxResult, nodeFilter(in), nodeInfoAttributes)
        nodePairs          <- { sequence(nodeEntries) { e =>
                                nodeDit.NODES.NODE.idFromDn(e.dn).map(id => (id,e))
                              } }.map( _.toMap )
        nodeIdSet1         =  nodePairs.keySet

        serverEntries      <- search(con, inventoryDit.NODES.dn, maxResult, serverFilter(in, nodeIdSet1), nodeInfoAttributes)
        serverPairs        <- { sequence(serverEntries) { e =>
                                e(A_NODE_UUID).map(id => (NodeId(id),e))
                              } }.map( _.toMap )
        nodeIdSet2         =  serverPairs.keySet

        //now, we have to find back entries missing from nodes but in server
        filter             =  OR( (nodeIdSet2 -- nodeIdSet1).map(id => EQ(A_NODE_UUID,id.value) ).toSeq:_* )
        missingNodeEntries <- search(con, nodeDit.NODES.dn, 0, filter, nodeInfoAttributes)
        missingNodePairs   <- { sequence(missingNodeEntries) { e =>
                                nodeDit.NODES.NODE.idFromDn(e.dn).map(id => (id,e))
                              } }.map( _.toMap )
        //now, map to node info
        allNodePairs       =  nodePairs ++ missingNodePairs

        nodeInfos          <- matchNodeInfoPairs( serverPairs, allNodePairs )
      } yield {
        nodeInfos
      }
  }


def matchNodeInfoPairs( serverPairs:Map[NodeId,LDAPEntry], allNodePairs:Map[NodeId,LDAPEntry] ): Box[Seq[NodeInfo]] = {
  sequence( allNodePairs.toSeq ) {
    case(id,nodeEntry) =>
      for {
        serverEntry <- Box(serverPairs.get(id)) ?~! "Missing required node entry with id %s".format(id)
        nodeInfos <- mapper.convertEntriesToNodeInfos(nodeEntry, serverEntry,None)
      } yield {
        nodeInfos
      }
  }
}


  //filter for ou=nodes based on attributes to look for in quicksearch
  private[this] def nodeFilter(in:String) = OR( nodeAttributes.map(attr =>  SUB(attr, null, Array(in), null) ) :_* )
  //filter for ou=severs in inventory based on attributes to look for in quicksearch
  private[this] def serverFilter(in:String, nodeIds:scala.collection.Set[NodeId]) = {
    val sub = serverAttributes.map(attr =>  SUB(attr, null, Array(in), null) )
    val otherNodes = nodeIds.map(id => EQ(A_NODE_UUID,id.value))
    OR( (sub ++ otherNodes):_* )
  }

  //search request with expected time/size limit
  private[this] def searchRequest(baseDN:DN,sizeLimit:Int,filter:Filter, attrs:Seq[String]) = new SearchRequest(
      baseDN.toString, SearchScope.ONE, DereferencePolicy.NEVER,
      sizeLimit, timeLimit, false,
      filter, attrs:_*
  )

  /*
   * We have to do some special case processing on responses here because we are just in best
   * effort. If the answer is not sufficiently precise, the user will just have to write some
   * more text.
   */
  private[this] def search(con:RoLDAPConnection, baseDN:DN, sizeLimit:Int, filter:Filter, attrs:Seq[String]) : Box[Seq[LDAPEntry]] = {
    def handleException(e:LDAPSearchException) : Box[Seq[LDAPEntry]] = {
        e.getSearchEntries match {
          case null => Failure("Exception when processing quick search query", Full(e), Empty)
          case entries => Full(entries.map(e => LDAPEntry(e.getDN,e.getAttributes)))
        }
    }

    val sr = new SearchRequest(
        baseDN.toString, SearchScope.ONE, DereferencePolicy.NEVER,
        sizeLimit, timeLimit, false,
        filter, attrs:_*
    )
    try {
      Full(con.backed.search(sr).getSearchEntries.map(e => LDAPEntry(e.getDN,e.getAttributes)))
    } catch {
      case e:LDAPSearchException if(e.getResultCode == ResultCode.TIME_LIMIT_EXCEEDED) => handleException(e)
      case e:LDAPSearchException if(e.getResultCode == ResultCode.SIZE_LIMIT_EXCEEDED) => handleException(e)
      case e:Exception => Failure("Error in quick search query: " + e.getMessage(), Full(e), Empty)
    }
  }


  /**
   * Check if a new query should be started for the given input.
   * I query does not have to be started if there is not at least some chars,
   * etc.
   */
  private[this] def validateInput(in:String) : Boolean = {
    in.trim match {
      case "" => false
      case s if(s.length < 2) => false
      case _ => true
    }
  }

}









