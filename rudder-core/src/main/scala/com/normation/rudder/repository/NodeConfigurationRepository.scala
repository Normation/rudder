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

import com.normation.inventory.domain.NodeId
import net.liftweb.common.Box
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.servers._
import com.normation.rudder.domain._
import com.normation.rudder.exceptions._
import com.normation.cfclerk.domain.{TechniqueId}
import net.liftweb.common.Full
import net.liftweb.common.Failure

trait NodeConfigurationRepository {

  /**
   * Save several servers in the repo
   * @param server
   * @return
   */
  def saveMultipleNodeConfigurations(server: Seq[NodeConfiguration]) : Box[Seq[NodeConfiguration]]

  /**
   * Delete a server. It will first clean its roles, and keep the consistencies of data
   * @param server
   */
  def deleteNodeConfigurations(nodeIds:Set[NodeId]) : Box[Set[NodeId]]

  /**
   * Delete all node configurations
   */
  def deleteAllNodeConfigurations : Box[Set[NodeId]]

  /**
   * Return all servers
   * @return
   */
  def getAll() : Box[Map[NodeId, NodeConfiguration]]

}
