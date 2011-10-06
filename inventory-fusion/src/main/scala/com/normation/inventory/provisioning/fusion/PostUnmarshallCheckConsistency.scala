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

package com.normation.inventory.provisioning
package fusion

import com.normation.inventory.domain.InventoryReport

import net.liftweb.common._
import scala.xml.NodeSeq
import com.normation.utils.Control.{pipeline,bestEffort}
import com.normation.inventory.services.provisioning._

class PostUnmarshallCheckConsistency extends PostUnmarshall {
  override val name = "post_process_inventory:check_consistency"  

  /**
   * There is a list of variables that MUST be set at that point:
   * - an id for the node (it is ok that it may be change latter on the process, for ex. for unusal changes)
   * - an hostname
   * - an admin (root user on the node)
   * - a policy server id
   * - an OS name
   * 
   * If any of these variable are not set, just abort
   */
  override def apply(report:InventoryReport) : Box[InventoryReport] = {
    val checks = 
      checkId _ ::
      checkHostname _ ::
      checkRoot _ ::
      checkPolicyServer _ ::
      checkOS _ ::
      checkAgent _ ::
      Nil
      
    pipeline(checks, report) { (check,currentReport) =>
      check(currentReport) 
    }
    
  }

  
  private[this] def checkNodeSeq(xml:NodeSeq, tag:String, directChildren:Boolean = false, optChild:Option[String] = None) : Box[String] = {
    val nodes = (
      if(directChildren) (xml \ tag)
      else (xml \\ tag) 
    ) 
    
    val nodes2 = optChild match { 
      case None => nodes
      case Some(t) => nodes \ t
    }
    
    nodes2 match {
      case NodeSeq.Empty => Failure("Missing XML element: '%s'. ".format(optChild.getOrElse(tag)))
      case x => x.head.text match {
        case null | "" => Failure("Tag %s content is empty".format(optChild.getOrElse(tag)))
        case s => Full(s)
      }
    }
  }
    
  private[this] def checkId(report:InventoryReport): Box[InventoryReport] = {
    val tag = "UUID"
    for {
      tagHere <- checkNodeSeq(report.sourceReport, tag, true) ?~! "Missing node ID attribute '%s' in report. This attribute is mandatory and must contains node ID.".format(tag)
      idHere <- if(report.node.main.id.value == tagHere) Full("OK") 
                else Failure("Node ID is not correctly set (but tag '%s' is present with value '%s'".format(tag, tagHere))
    } yield {
      report
    }
  }
    
  private[this] def checkHostname(report:InventoryReport): Box[InventoryReport] = {
    val tag = "HOSTNAME"
    for {
      tagHere <- checkNodeSeq(report.sourceReport, tag) ?~! "Missing '%s' attribute in report. This attribute is mandatory and must contains node hostname.".format(tag)
      idHere <- if(report.node.main.hostname == tagHere) Full("OK")
                else Failure("Hostname is not correctly set (but tag '%s' is present with value '%s'".format(tag, tagHere) )
    } yield {
      report
    }
  }
  
  private[this] def checkRoot(report:InventoryReport): Box[InventoryReport] = {
    val tag = "USER"
    for {
      tagHere <- checkNodeSeq(report.sourceReport, tag, true) ?~! "Missing administrator attribute '%s' in report. This attribute is mandatory and must contains node local administrator login.".format(tag)
      idHere <- if(report.node.main.rootUser == tagHere) Full("OK")
                else Failure("Node administrator login is not correctly set (but tag '%s' is present with value '%s'".format(tag, tagHere))
    } yield {
      report
    }
  }  
    
  private[this] def checkPolicyServer(report:InventoryReport): Box[InventoryReport] = {
    val tag = "POLICY_SERVER"
    for {
      tagHere <- checkNodeSeq(report.sourceReport, tag) ?~! "Missing rudder policy server attribute '%s' in report. This attribute is mandatory and must contains the policy server ID that the node must contact.".format(tag)
      idHere <- if(report.node.main.policyServerId.value == tagHere) Full("OK")
                else Failure("Rudder policy server is not correctly set (but tag '%s' is present with value '%s'".format(tag, tagHere) )
    } yield {
      report
    }
  }
   
  private[this] def checkOS(report:InventoryReport): Box[InventoryReport] = {
    val tags = "FULL_NAME" :: "KERNEL_NAME" :: "KERNEL_VERSION" :: "NAME" :: "VERSION" :: Nil   
    for {
      tagHere <- bestEffort(tags) { tag => 
                   checkNodeSeq(report.sourceReport, "OPERATINGSYSTEM", false, Some(tag)) ?~! "Missing '%s' name attribute in report. This attribute is mandatory.".format(tag)
                 }
    } yield {
      report
    }
  }
  
  private[this] def checkAgent(report:InventoryReport): Box[InventoryReport] = {
    val tag = "AGENTNAME"
    for {
      tagHere <- checkNodeSeq(report.sourceReport, tag) ?~! "Missing agent name attribute in report. This attribute is mandatory and must contains (at least one of) the agent deployed on the node.".format(tag)
      idHere <- Box(report.node.agentNames.headOption) ?~! "Agent name is not correctly set (but tag '%s' is present with value '%s'".format(tag, tagHere)
    } yield {
      report
    }
  }
}
