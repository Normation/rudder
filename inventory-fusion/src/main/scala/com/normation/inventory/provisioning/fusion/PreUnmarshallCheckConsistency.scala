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
import com.normation.utils.Control.{pipeline, bestEffort}
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
      checkHostnameTags _ ::
      checkRoot _ ::
      checkPolicyServer _ ::
      checkOS _ ::
      checkKernelVersion _ ::
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

  private[this] def checkInRudderTag(xml:NodeSeq,tag : String)= {
    checkNodeSeq(xml,"RUDDER",false,Some(tag))
  }

  private[this] def checkInAgentTag(xml:NodeSeq,tag : String)= {
    checkNodeSeq(xml,"AGENT",false,Some(tag))
  }

  private[this] def checkId(report:NodeSeq) : Box[NodeSeq] = {
    val tag = "UUID"
    for {
      tagHere <- {
        checkInRudderTag(report,tag) match {
          case full : Full[String] => full
          case eb: EmptyBox => checkNodeSeq(report, tag, true) ?~! s"Missing node ID attribute '${tag}' in report. This attribute is mandatory and must contains node ID."
        }
      }
      uuidOK  <- checkNodeUUID(tagHere)
    } yield {
      report
    }
  }

  private[this] def checkHostnameTags(report:NodeSeq) : Box[NodeSeq] = {

    // Hostname can be found in two tags:
    // Either RUDDER∕HOSTNAME or OPERATINGSYSTEM/FQDN
    ( checkInRudderTag(report,"HOSTNAME"), checkNodeSeq(report, "OPERATINGSYSTEM", false, Some("FQDN")) ) match {
      case (Full(_),_) | (_,Full(_)) => Full(report)
      case (_:EmptyBox, _:EmptyBox) => Failure("Missing hostname tags (RUDDER∕HOSTNAME and OPERATINGSYSTEM/FQDN) in report. Having at least one of Those tags is mandatory.")
    }
  }

  private[this] def checkRoot(report:NodeSeq) : Box[NodeSeq] = {
    val agentTag = "OWNER"
    val tag = "USER"
    for {
      tagHere <- {
        checkInAgentTag(report,agentTag) match {
          case full : Full[String] => full
          case eb: EmptyBox => checkNodeSeq(report, tag) ?~! s"Missing administrator attribute '${tag}' in report. This attribute is mandatory and must contains node local administrator login."
        }
      }
    } yield {
      report
    }
  }

  private[this] def checkPolicyServer(report:NodeSeq) : Box[NodeSeq] = {
    val agentTag = "POLICY_SERVER_UUID"
    val tag = "POLICY_SERVER"
    for {
      tagHere <- {
        checkInAgentTag(report,agentTag) match {
          case full : Full[String] => full
          case eb: EmptyBox => checkNodeSeq(report, tag) ?~! s"Missing rudder policy server attribute '${tag}' in report. This attribute is mandatory and must contains node local administrator login."
        }
      }
    } yield {
      report
    }
  }

  private[this] def checkOS(report:NodeSeq) : Box[NodeSeq] = {

    //VERSION is not mandatory on windows, it can't be added in that list
    val tags = "FULL_NAME" :: "KERNEL_NAME" :: "NAME" :: Nil
    for {
      tagHere <- bestEffort(tags) { tag =>
                   checkNodeSeq(report, "OPERATINGSYSTEM", false, Some(tag)) ?~! "Missing '%s' name attribute in report. This attribute is mandatory.".format(tag)
                 }
    } yield {
      report
    }
  }

  /**
   * Kernel version: either
   * - non empty OPERATINGSYSTEM > KERNEL_VERSION
   * or
   * - (on AIX and non empty HARDWARE > OSVERSION )
   * Other cases are failure (missing required info)
   */
  private[this] def checkKernelVersion(report:NodeSeq) : Box[NodeSeq] = {

    val failure = Failure("Missing attribute OPERATINGSYSTEM>KERNEL_VERSION in report. This attribute is mandatory")
    val aixFailure = Failure("Missing attribute HARDWARE>OSVERSION in report. This attribute is mandatory")

    checkNodeSeq(report, "OPERATINGSYSTEM", false, Some("KERNEL_VERSION")) match {
      case Full(x) => Full(report)
      case e:EmptyBox => //perhaps we are on AIX ?
        checkNodeSeq(report, "OPERATINGSYSTEM", false, Some("KERNEL_NAME")) match {
          case Full(x) if(x.toLowerCase == "aix") => //ok, check for OSVERSION
            checkNodeSeq(report, "HARDWARE", false, Some("OSVERSION")) match {
              case e:EmptyBox => aixFailure
              case Full(kernelVersion) => //update the report to put it in the right place
                Full(new scala.xml.transform.RuleTransformer(
                    new AddChildrenTo("OPERATINGSYSTEM", <KERNEL_VERSION>{kernelVersion}</KERNEL_VERSION>)
                ).transform(report).head)
            }
          //should not be empty give checkOS, but if so, fails. Also fails is not aix.
          case _ => failure
        }

    }
  }
  //for check kernel version
  private[this] class AddChildrenTo(label: String, newChild: scala.xml.Node) extends scala.xml.transform.RewriteRule {
    override def transform(n: scala.xml.Node) = n match {
      case Elem(prefix, "OPERATINGSYSTEM", attribs, scope, child @ _*) =>
        Elem(prefix, label, attribs, scope, false, child ++ newChild : _*)
      case other => other
    }
  }


  private[this] def checkAgent(report:NodeSeq) : Box[NodeSeq] = {
    val agentTag = "AGENT_NAME"
    val tag = "AGENTNAME"
    for {
      tagHere <- {
        checkInAgentTag(report,agentTag) match {
          case full : Full[String] => full
          case eb: EmptyBox => checkNodeSeq(report, tag) ?~! s"Missing Missing agent name attribute ${tag} in report. This attribute is mandatory and must contains node local administrator login."
        }
      }
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
            Full(Elem(prefix, label, attribs, scope, false, newChildren : _*))
          case _ => Failure("The given report does not seems to have an uniq root element and so can not be handled")
        }

      case _ => Full(report)
    }
  }
}
