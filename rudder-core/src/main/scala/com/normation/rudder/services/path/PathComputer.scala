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


import com.normation.rudder.domain.servers._
import com.normation.inventory.domain.AgentType

/**
 * Utilitary tool to compute the path of a server promises (and others information) on the rootMachine
 * 
 * @author nicolas
 *
 */
trait PathComputer {
    
  /**
   * Compute the base path for a server, i.e. the full path on the root server to the data
   * the searched server will fetch, and the backup folder
   * Finish by the server name, with no trailing /
   * Ex : /opt/hive/cfserved/serverA/served/serverB, /opt/hive/backup/serverA/served/serverB
   * @param searchedNode : the server we search
   * @return
   */  
  def computeBaseNodePath(searchedNode : NodeConfiguration) :  (String, String)

  /**
   * Return the path of the promises for the root (we directly write its promises in its path)
   * @param agent
   * @return
   */
  def getRootPath(agentType : AgentType) : String
}
