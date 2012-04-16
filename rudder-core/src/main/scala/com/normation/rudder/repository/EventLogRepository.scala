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

import net.liftweb.common._
import com.normation.eventlog._
import java.security.Principal
import com.normation.rudder.domain.policies._
import com.normation.rudder.services.log.EventLogFactory
import com.normation.cfclerk.domain.SectionSpec
import com.normation.rudder.domain.nodes.DeleteNodeGroupDiff
import com.normation.rudder.domain.nodes.ModifyNodeGroupDiff
import com.normation.rudder.domain.nodes.AddNodeGroupDiff

trait EventLogRepository {
  def eventLogFactory : EventLogFactory
  
  
  /**
   * Save an eventLog
   * Optionnal : the user. At least one of the eventLog user or user must be defined
   * Return the unspecialized event log with its serialization number
   */
  def saveEventLog(eventLog : EventLog) : Box[EventLog]
                         
  def saveAddRule(principal: EventActor, addDiff: AddRuleDiff, reason:Option[String]) = {
    saveEventLog(eventLogFactory.getAddRuleFromDiff(
        principal = principal
      , addDiff   = addDiff
      , reason = reason
    ))
  }
  
  def saveDeleteRule(principal: EventActor, deleteDiff: DeleteRuleDiff, reason:Option[String]) = {
    saveEventLog(eventLogFactory.getDeleteRuleFromDiff(
        principal  = principal
      , deleteDiff = deleteDiff
      , reason = reason
    ))
  }

  def saveModifyRule(principal: EventActor, modifyDiff: ModifyRuleDiff, reason:Option[String]) = {
    saveEventLog(eventLogFactory.getModifyRuleFromDiff(
        principal = principal
      , modifyDiff = modifyDiff
      , reason = reason
    ))
  }

  def saveAddDirective(principal: EventActor, addDiff: AddDirectiveDiff, varsRootSectionSpec: SectionSpec, reason:Option[String]) = {
    saveEventLog(eventLogFactory.getAddDirectiveFromDiff(
        principal           = principal
      , addDiff             = addDiff
      , varsRootSectionSpec = varsRootSectionSpec
      , reason = reason
    ))
  }
  
  def saveDeleteDirective(principal : EventActor, deleteDiff:DeleteDirectiveDiff, varsRootSectionSpec: SectionSpec, reason:Option[String]) = {
    saveEventLog(eventLogFactory.getDeleteDirectiveFromDiff(
        principal  = principal
      , deleteDiff = deleteDiff
      , varsRootSectionSpec = varsRootSectionSpec
      , reason = reason
    ))
  }

  def saveModifyDirective(principal : EventActor, modifyDiff: ModifyDirectiveDiff, reason:Option[String]) = {
    saveEventLog(eventLogFactory.getModifyDirectiveFromDiff(
        principal = principal
      , modifyDiff = modifyDiff
      , reason = reason
    ))
  }
  
  def saveAddNodeGroup(principal: EventActor, addDiff: AddNodeGroupDiff, reason:Option[String]) = {
    saveEventLog(eventLogFactory.getAddNodeGroupFromDiff(
        principal           = principal
      , addDiff             = addDiff
      , reason = reason
    ))
  }
  
  def saveDeleteNodeGroup(principal : EventActor, deleteDiff:DeleteNodeGroupDiff, reason:Option[String]) = {
    saveEventLog(eventLogFactory.getDeleteNodeGroupFromDiff(
        principal  = principal
      , deleteDiff = deleteDiff
      , reason = reason
    ))
  }

  def saveModifyNodeGroup(principal : EventActor, modifyDiff: ModifyNodeGroupDiff, reason:Option[String]) = {
    saveEventLog(eventLogFactory.getModifyNodeGroupFromDiff(
        principal = principal
      , modifyDiff = modifyDiff
      , reason = reason
    ))
  } 
  
  /**
   * Get an EventLog by its entry
   */
  def getEventLog(id : Int) : Box[EventLog]
  
  /**
   * Returns eventlog matching criteria
   * For the moment it only a string, it should be something else in the future
   */
  def getEventLogByCriteria(criteria : Option[String], limit:Option[Int] = None, orderBy:Option[String] = None) : Box[Seq[EventLog]]
    
}