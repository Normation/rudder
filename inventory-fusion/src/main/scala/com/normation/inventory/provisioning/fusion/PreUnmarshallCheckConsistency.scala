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
import scala.xml.Elem

class PostUnmarshallCheckConsistency extends PreUnmarshall with Loggable {
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
  override def apply(report:NodeSeq) : Box[NodeSeq] = {
    val checks = 
      checkId _ ::
      checkHostname _ ::
      checkRoot _ ::
      checkPolicyServer _ ::
      checkOS _ ::
      checkAgent _ ::
      checkMachineId _ ::
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
    
  private[this] def checkId(report:NodeSeq) : Box[NodeSeq] = {
    val tag = "UUID"
    for {
      tagHere <- checkNodeSeq(report, tag, true) ?~! "Missing node ID attribute '%s' in report. This attribute is mandatory and must contains node ID.".format(tag)
      uuidOK  <- checkNodeUUID(tagHere)
    } yield {
      report
    }
  }
    
  private[this] def checkHostname(report:NodeSeq) : Box[NodeSeq] = {
    val tag = "HOSTNAME"
    for {
      tagHere <- checkNodeSeq(report, tag) ?~! "Missing '%s' attribute in report. This attribute is mandatory and must contains node hostname.".format(tag)
    } yield {
      report
    }
  }
  
  private[this] def checkRoot(report:NodeSeq) : Box[NodeSeq] = {
    val tag = "USER"
    for {
      tagHere <- checkNodeSeq(report, tag, true) ?~! "Missing administrator attribute '%s' in report. This attribute is mandatory and must contains node local administrator login.".format(tag)
    } yield {
      report
    }
  }  
    
  private[this] def checkPolicyServer(report:NodeSeq) : Box[NodeSeq] = {
    val tag = "POLICY_SERVER"
    for {
      tagHere <- checkNodeSeq(report, tag) ?~! "Missing rudder policy server attribute '%s' in report. This attribute is mandatory and must contains the policy server ID that the node must contact.".format(tag)
    } yield {
      report
    }
  }
   
  private[this] def checkOS(report:NodeSeq) : Box[NodeSeq] = {
    val tags = "FULL_NAME" :: "KERNEL_NAME" :: "KERNEL_VERSION" :: "NAME" :: "VERSION" :: Nil   
    for {
      tagHere <- bestEffort(tags) { tag => 
                   checkNodeSeq(report, "OPERATINGSYSTEM", false, Some(tag)) ?~! "Missing '%s' name attribute in report. This attribute is mandatory.".format(tag)
                 }
    } yield {
      report
    }
  }
  
  private[this] def checkAgent(report:NodeSeq) : Box[NodeSeq] = {
    val tag = "AGENTNAME"
    for {
      tagHere <- checkNodeSeq(report, tag) ?~! "Missing agent name attribute in report. This attribute is mandatory and must contains (at least one of) the agent deployed on the node.".format(tag)
    } yield {
      report
    }
  }
  
  /**
   * A node ID must:
   * - be less than 50 chars (because we have taken that hypothesis elsewhere)
   * - only contains [a-zA-Z0-9\-] (because other chars leads to strange errors, like
   *   having a # breaks javascript)
   */
  private[this] val uuidAuthCharRegex = """([a-zA-Z0-9\-]{1,50})""".r
  private[this] def checkNodeUUID(uuid:String) : Box[String] = {
    uuid match {
      case uuidAuthCharRegex(x) => Full(uuid)
      case _ => Failure("""The UUID '%s' is not valid. It should be lesser than 50 chars and contains chars among the set [a-zA-Z0-9\-])""".format(uuid))
    }
  }

  /**
   * That one is special: we don't fail on a missing
   * machine id, we just add an empty one. 
   */
  private[this] def checkMachineId(report:NodeSeq) : Box[NodeSeq] = {
    (report \ "MACHINEID") match {
      case NodeSeq.Empty => //missing machine id tag, add an empty one
        logger.warn("Missing MACHINEID tag, adding an empty one for consistency")
        
        report match {
          case Elem(prefix, label, attribs, scope, children @ _*) =>
            val newChildren = children ++ <MACHINEID></MACHINEID>
            Full(Elem(prefix, label, attribs, scope, newChildren : _*))
          case _ => Failure("The given report does not seems to have an uniq root element and so can not be handled")
        }
        
      case _ => Full(report)
    }
  }
}
