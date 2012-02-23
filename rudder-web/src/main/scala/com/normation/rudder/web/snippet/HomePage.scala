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

package com.normation.rudder.web.snippet

//lift std import
import scala.xml._
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.util._
import Helpers._
import net.liftweb.http.js._
import JsCmds._
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.BuildFilter._
import com.normation.rudder.domain.NodeDit
import com.normation.rudder.domain.RudderLDAPConstants._
import com.normation.rudder.repository.RuleRepository

// For implicits
import JE._
import net.liftweb.http.SHtml._


import bootstrap.liftweb.LiftSpringApplicationContext.inject



class HomePage extends Loggable {

  private[this] val ldap = inject[LDAPConnectionProvider]
  private[this] val pendingNodesDit = inject[InventoryDit]("pendingNodesDit")
  private[this] val nodeDit = inject[NodeDit]("nodeDit")
  private[this] val ruleRepository = inject[RuleRepository]

  private def countPendingNodes() : Box[Int] = {
    ldap.map { con =>
      con.searchOne(pendingNodesDit.NODES.dn,ALL)
    }.map(x => x.size)
  }

  private def countAcceptedNodes() : Box[Int] = {
    ldap.map { con =>
      con.searchOne(nodeDit.NODES.dn,NOT(IS(OC_POLICY_SERVER_NODE)))
    }.map(x => x.size)
  }

  private def countAllCr() : Box[Int] = {
    ruleRepository.getAll().map(_.size)
  }


  def pendingNodes(html : NodeSeq) : NodeSeq = {
    countPendingNodes match {
      case Empty => <li>There are no pending nodes</li>
      case m:Failure =>
          logger.error("Could not fetch the number of pending nodes. reason : %s".format(m.messageChain))
          <div>Could not fetch the number of pending nodes</div>
      case Full(x) if x == 0 => <li>There are no pending nodes</li>
      case Full(x) => <li>There are <a href="secure/assetManager/manageNewNode">{x} pending nodes</a></li>
    }
  }

  def acceptedNodes(html : NodeSeq) : NodeSeq = {
    countAcceptedNodes match {
      case Empty => <li>There are no accepted nodes</li>
      case m:Failure =>
          logger.error("Could not fetch the number of accepted nodes. reason : %s".format(m.messageChain))
          <div>Could not fetch the number of accepted nodes</div>
      case Full(x) if x == 0 => <li>There are no accepted nodes</li>
      case Full(x) => <li>There are <a href={"""secure/assetManager/searchNodes#{"query":{"select":"node","composition":"and","where":[{"objectType":"node","attribute":"osVersion","comparator":"exists"}]}}"""}>{x} accepted nodes</a></li>
    }
  }

  def rules(html : NodeSeq) : NodeSeq = {
    countAllCr match {
      case Empty => <li>There are no Rules defined</li>
      case m:Failure =>
          logger.error("Could not fetch the number of Rules. reason : %s".format(m.messageChain))
          <div>Could not fetch the number of Rules</div>
      case Full(x) if x == 0 => <li>There are no Rules defined</li>
      case Full(x) => <li>There are <a href="secure/configurationManager/ruleManagement">{x} Rules defined</a></li>
    }
  }

}
