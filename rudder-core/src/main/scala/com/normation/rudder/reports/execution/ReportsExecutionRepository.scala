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
   * Returns all executions (complete or not) for a specific Node, over all the time
   * Caution, it can get large !
   */
  def getExecutionByNode (nodeId : NodeId) : Box[Seq[ReportExecution]]

  /**
   * Returns all execution for a specific node, at a specific time
   */
  def getExecutionByNodeAndDate (nodeId : NodeId, date: DateTime) : Box[Option[ReportExecution]]

  def getNodeLastExecution (nodeId : NodeId) : Box[Option[ReportExecution]]

  /**
   * From a seq of found executions in RudderSysEvents, find in the existing executions matching
   */
  def getExecutionsByNodeAndDate (executions: Seq[ReportExecution]) : Box[Seq[ReportExecution]]

}


trait WoReportsExecutionRepository {

  /**
   * From a list of nodes execution fetch from the ruddersysevent table, create or update
   * the list of execution in the execution tables
   */
  def updateExecutions (executions : Seq[ReportExecution]) : Box[Seq[ReportExecution]]

  def closeExecutions (executions : Seq[ReportExecution]) : Box[Seq[ReportExecution]]
}