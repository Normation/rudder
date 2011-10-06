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
import com.normation.rudder.domain.policies.ConfigurationRuleId
import com.normation.rudder.domain.reports.bean._
import org.joda.time._
import com.normation.cfclerk.domain.{CFCPolicyInstanceId}

/**
 * An overly simple repository for searching through the cfengine reports
 * Can search by CR, by Node, by both, and or by date
 * @author Nicolas CHARLES
 *
 */
trait ReportsRepository {

  /**
   * Returns all reports for the configurationRuleId, between the two differents date (optionnally)
   */
  def findReportsByConfigurationRule(configurationRuleId : ConfigurationRuleId, serial : Option[Int],  beginDate : Option[DateTime], endDate : Option[DateTime]) : Seq[Reports]
  
  
  /**
   * Return the last (really the last, serial wise, with full execution) reports for a configuration rule
   */
  def findLastReportByConfigurationRule(configurationRuleId : ConfigurationRuleId, serial : Int, node : Option[NodeId]) : Seq[Reports]
  
  
  /**
   * Returns all reports for the node, between the two differents date (optionnal)
   * for a configuration rule (optionnal) and for a specific serial of this configuration rule (optionnal)
   * Note : serial is used only if configurationrule is used
   * Note : only the 1000 first entry are returned
   */
  def findReportsByServer(nodeId : NodeId, configurationRuleId : Option[ConfigurationRuleId], serial : Option[Int], beginDate : Option[DateTime], endDate : Option[DateTime]) : Seq[Reports]
  
  /**
   * All reports for a node and cr/serial, between two date, ordered by date
   */
  def findReportsByNode(nodeId : NodeId, configurationRuleId : ConfigurationRuleId,
      serial : Int, beginDate : DateTime, endDate : Option[DateTime]) : Seq[Reports]
  
  def findExecutionTimeByNode(nodeId : NodeId, 
      beginDate: DateTime, 
      endDate: Option[DateTime] ) : Seq[DateTime]
  
}