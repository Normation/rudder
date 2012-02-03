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

package com.normation.rudder.services.path

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain._
import scala.collection.mutable._
import org.apache.commons.io.FilenameUtils
import com.normation.rudder.domain.servers._
import com.normation.rudder.repository._
import com.normation.rudder.exceptions._
import com.normation.exceptions._
import net.liftweb.common._
import com.normation.inventory.domain.AgentType
import com.normation.inventory.domain.NOVA_AGENT
import com.normation.inventory.domain.COMMUNITY_AGENT
import com.normation.exceptions.BusinessException

/**
 * Utilitary tool to compute the path of a machine promises (and others information) on the rootMachine
 * 
 * @author nicolas
 *
 */
class PathComputerImpl(
            nodeConfigurationRepository : NodeConfigurationRepository,
            baseFolder : String, 
            backupFolder : String) extends PathComputer with Loggable {

  
  
  val servedPrefix = "share/"
    
  val promisesPrefix = "rules/"
  
    
  /**
   * Compute the base path for a machine, i.e. the full path on the root server to the data
   * the searched machine will fetch, and the backup folder
   * Finish by the machine uuid, with no trailing /
   * It does not contain the root machine, except if we search the root machine
   * Ex : /var/rudder/share/uuid-a/share/uuid-b, /var/rudder/backup/uuid-a/share/uuid-b
   * @param searchedNodeConfiguration : the machine we search
   * @return
   */  
  def computeBaseServerPath(searchedServer : NodeConfiguration) :  (String, String) = {
    val root = nodeConfigurationRepository.getRootNodeConfiguration match {
      case Full(s) => s
      case e:EmptyBox =>
        val msg = "Failed to get root server to build path"
        logger.error(msg,e)
        throw new BusinessException(msg)
    }
    val path = recurseComputePath(root, searchedServer, "/"  + searchedServer.id)
    return (FilenameUtils.normalize(baseFolder + servedPrefix + path) , FilenameUtils.normalize(backupFolder + path))
  }
  /**
   * compute the relative path from one machine to another
   * typically, something like '/share/uuid-B/share/uuid-C'
   * @param fromServer e.g machineA
   * @param toServer e.g machineC
   * @return
   */
  def computeRelativePath(fromServer : NodeConfiguration, toServer : NodeConfiguration) : String = {
    if (fromServer == toServer)
      return "/"
    
    var machineDest = toServer
    var path = ""
    while (machineDest != fromServer) {
      path ="/" + servedPrefix + machineDest.id ;
      nodeConfigurationRepository.findNodeConfiguration(NodeId(machineDest.targetMinimalNodeConfig.policyServerId)) match {
        case Full(node) => machineDest = node
        case _ => throw new HierarchicalException("Wrong hierarchy for node : " + toServer)
      }
    }
    
    return FilenameUtils.normalize(path)
  }
  
  /**
   * Return the path of the promises for the root (we directly write its promises in its path)
   * @param agent
   * @return
   */
  def getRootPath(agentType : AgentType) : String = {
    agentType match {
        case NOVA_AGENT => "/var/cfengine/inputs"
        case COMMUNITY_AGENT =>  "/var/rudder/cfengine-community/inputs"
        case x => throw new BusinessException("Unrecognized agent type: %s".format(x))
    }
  }
  
  
  /**
   * Return the path from a machine to another, excluding the top rootServer
   * If we have the hierarchy Root, A, B :
   * recurseComputePath(root, B, path) will return : machineA/share/ + path
   * recurseComputePath(A, B, path) will return : machineA/share/+ path
   * recurseComputePath(root, A, path) will return :  path
   * @param fromServer
   * @param toServer
   * @param path
   * @return
   */
  private def recurseComputePath(fromServer : NodeConfiguration, toServer : NodeConfiguration, path : String) : String = {
    if (fromServer == toServer)
      return path
    
    nodeConfigurationRepository.findNodeConfiguration(NodeId(toServer.targetMinimalNodeConfig.policyServerId)) match {
      case Full(root : RootNodeConfiguration) => 
          recurseComputePath(fromServer, root, path)
      case Full(policyParent) =>
          recurseComputePath(fromServer, policyParent, policyParent.id + "/" + servedPrefix + path)
      case _ =>throw new HierarchicalException("Wrong hierarchy for node :" + toServer)
    }
  }
}
