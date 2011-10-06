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
import com.normation.rudder.repository.jdbc.SerializedGroups
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.repository.jdbc.SerializedPIs
import com.normation.rudder.domain.policies.PolicyInstance
import com.normation.rudder.domain.policies.UserPolicyTemplate
import com.normation.cfclerk.domain.PolicyPackage
import com.normation.rudder.domain.policies.ConfigurationRule
import com.normation.rudder.repository.jdbc.SerializedCRPIs
import com.normation.rudder.repository.jdbc.SerializedCRGroups
import com.normation.rudder.repository.jdbc.SerializedCRs
import org.joda.time.DateTime
import com.normation.rudder.repository.jdbc.SerializedNodes
import com.normation.rudder.domain.nodes.NodeInfo

trait HistorizationRepository {

  def getAllOpenedNodes() : Seq[SerializedNodes]
  
  def getAllNodes(after : Option[DateTime]) : Seq[SerializedNodes]
  
  def updateNodes(nodes : Seq[NodeInfo], closable : Seq[String]) :Seq[SerializedNodes]
  
  def getAllGroups(after : Option[DateTime]) : Seq[SerializedGroups]
  
  def getAllOpenedGroups() : Seq[SerializedGroups] 
  
  def updateGroups(nodes : Seq[NodeGroup], closable : Seq[String]) :Seq[SerializedGroups]
  
  def getAllPIs(after : Option[DateTime]) : Seq[SerializedPIs] 
  
  def getAllOpenedPIs() : Seq[SerializedPIs]
  
  def updatePIs(pis : Seq[(PolicyInstance, UserPolicyTemplate, PolicyPackage)], 
      				closable : Seq[String]) :Seq[SerializedPIs]
   
  def getAllCRs(after : Option[DateTime]) : Seq[(SerializedCRs, Seq[SerializedCRGroups],  Seq[SerializedCRPIs])]
  
  def getAllOpenedCRs() : Seq[ConfigurationRule] 
  
  def updateCrs(crs : Seq[ConfigurationRule], closable : Seq[String]) : Unit
  
 
}

