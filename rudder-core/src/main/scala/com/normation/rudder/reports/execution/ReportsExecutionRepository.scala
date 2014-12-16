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

package com.normation.rudder.reports.execution

import com.normation.inventory.domain.NodeId
import net.liftweb.common._
import org.joda.time.DateTime


/**
 * Service for reading or storing execution of Nodes
 */
trait RoReportsExecutionRepository {

  /**
   * Find the last execution of nodes, whatever is its state.
   * Last execution is defined as "the last executions that have been inserted in the database",
   * and do not rely on date (which can change too often)
   * The goal is to have reporting that does not depend on time, as node may have time in the future, or
   * past, or even change during their lifetime
   * So the last run are the last run inserted in the reports database
   * See ticket http://www.rudder-project.org/redmine/issues/6005
   */
  def getNodesLastRun(nodeIds: Set[NodeId]): Box[Map[NodeId, Option[AgentRun]]]
}


trait WoReportsExecutionRepository {

  /**
   * Create or update the list of execution in the execution tables
   * Only return execution which where actually changed in backend
   *
   * The logic is:
   * - a new execution (not present in backend) is inserted as provided
   * - a existing execution can only change the completion status from
   *   "not completed" to "completed" (i.e: a completed execution can
   *   not be un-completed).
   */
  def updateExecutions(executions : Seq[AgentRun]) : Box[Seq[AgentRun]]

}