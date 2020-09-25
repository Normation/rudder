/*
*************************************************************************************
* Copyright 2013 Normation SAS
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

package com.normation.rudder.reports.execution

import com.normation.inventory.domain.NodeId
import org.joda.time.DateTime
import com.normation.rudder.domain.reports.NodeConfigId
import com.normation.rudder.domain.reports.NodeExpectedReports


final case class AgentRunId(
    nodeId: NodeId
  , date  : DateTime
)

/**
 * a mapping of reports executions
 */
final case class AgentRun (
    agentRunId       : AgentRunId
  , nodeConfigVersion: Option[NodeConfigId]
  , isCompleted      : Boolean
  , insertionId      : Long
)

/**
 * The run, mapped with the expected node configuration
 * for the NodeConfigId (or None if none is found)
 */
final case class AgentRunWithNodeConfig (
    agentRunId       : AgentRunId
  , nodeConfigVersion: Option[(NodeConfigId, Option[NodeExpectedReports])]
  , isCompleted      : Boolean
  , insertionId      : Long
)

/**
 * Unprocessed Run
 * A run which we received, but does not contain a complianceComputationDate
 */
final case class AgentRunWithoutCompliance (
    agentRunId       : AgentRunId
  , nodeConfigVersion: Option[NodeConfigId]
  , insertionId      : Long
  , insertionDate    : DateTime

)

