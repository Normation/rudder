/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

package com.normation.inventory.provisioning
package fusion

import com.normation.inventory.domain.InventoryError
import com.normation.inventory.domain.InventoryResult._

import scala.xml.NodeSeq
import com.normation.inventory.services.provisioning._

import scala.xml.Elem
import scalaz.zio._
import scalaz.zio.syntax._

class PreUnmarshallCheckConsistency extends PreUnmarshall {
  override val name = "post_process_inventory:check_consistency"

  implicit class ToInconsistency(msg: String) {
    def inconsistency = InventoryError.Inconsistency(msg).fail
  }

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
  override def apply(report:NodeSeq) : InventoryResult[NodeSeq] = {
    val checks =
      checkId _ ::
      checkHostnameTags _ ::
      checkRoot _ ::
      checkPolicyServer _ ::
      checkOS _ ::
      checkKernelVersion _ ::
      checkAgentType _ ::
      checkSecurityToken _ ::
      Nil

    ZIO.foldLeft(checks)(report) { (currentReport, check) =>
      check(currentReport)
    }
  }



  private[this] def checkNodeSeq(xml:NodeSeq, tag:String, directChildren:Boolean = false, optChild:Option[String] = None) : InventoryResult[String] = {
    val nodes = (
      if(directChildren) (xml \ tag)
      else (xml \\ tag)
    )

    val nodes2 = optChild match {
      case None => nodes
      case Some(t) => nodes \ t
    }

    nodes2 match {
      case NodeSeq.Empty => s"Missing XML element: '${optChild.getOrElse(tag)}'. ".inconsistency
      case x => x.head.text match {
        case null | "" => s"Tag '${optChild.getOrElse(tag)}' content is empty".inconsistency
        case s => s.succeed
      }
    }
  }

  private[this] def checkInRudderTag(xml:NodeSeq,tag : String) : InventoryResult[String] = {
    checkNodeSeq(xml,"RUDDER",false,Some(tag))
  }

  private[this] def checkInAgentTag(xml:NodeSeq,tag : String) : InventoryResult[String] = {
    checkNodeSeq(xml,"AGENT",false,Some(tag))
  }

  private[this] def checkId(report:NodeSeq) : InventoryResult[NodeSeq] = {
    val tag = "UUID"
    for {
      tagHere <- {
        checkInRudderTag(report,tag) catchAll { _ =>
           checkNodeSeq(report, tag, true).chainError(s"Missing node ID attribute '${tag}' in report. This attribute is mandatory and must contains node ID.")
        }
      }
      uuidOK  <- checkNodeUUID(tagHere)
    } yield {
      report
    }
  }

  private[this] def checkHostnameTags(report:NodeSeq) : InventoryResult[NodeSeq] = {

    // Hostname can be found in two tags:
    // Either RUDDER∕HOSTNAME or OPERATINGSYSTEM/FQDN
    checkInRudderTag(report,"HOSTNAME").orElse(checkNodeSeq(report, "OPERATINGSYSTEM", false, Some("FQDN")) ).map(_ => report) mapError( _ =>
      InventoryError.Inconsistency(s"Missing hostname tags (RUDDER∕HOSTNAME and OPERATINGSYSTEM/FQDN) in report. Having at least one of Those tags is mandatory."))
  }

  private[this] def checkRoot(report:NodeSeq) : InventoryResult[NodeSeq] = {
    val agentTag = "OWNER"
    val tag = "USER"
    for {
      tagHere <- {
        checkInAgentTag(report,agentTag) catchAll  { _ => checkNodeSeq(report, tag).chainError(s"Missing administrator attribute '${tag}' in report. This attribute is mandatory and must contains node local administrator login.")
        }
      }
    } yield {
      report
    }
  }

  private[this] def checkPolicyServer(report:NodeSeq) : InventoryResult[NodeSeq] = {
    val agentTag = "POLICY_SERVER_UUID"
    val tag = "POLICY_SERVER"
    for {
      tagHere <- {
        checkInAgentTag(report,agentTag) catchAll  { _ => checkNodeSeq(report, tag).chainError(s"Missing rudder policy server attribute '${tag}' in report. This attribute is mandatory and must contain Rudder ID pour policy server."
        )}
      }
    } yield {
      report
    }
  }

  private[this] def checkOS(report:NodeSeq) : InventoryResult[NodeSeq] = {
    //VERSION is not mandatory on windows, it can't be added in that list
    val tags = "FULL_NAME" :: "KERNEL_NAME" :: "NAME" :: Nil
    for {
      tagHere <- {
                  /*
                   * Try each tag one after the other. Stop on the first succes.
                   * In case of none, return a failure.
                   */
                   ZIO.raceAll(IO.fail, tags.map(tag => checkNodeSeq(report, "OPERATINGSYSTEM", false, Some(tag)))).mapError(_ => InventoryError.Inconsistency(s"Missing tags ${tags.map(t => s"OPERATINGSYSTEM/${t}").mkString(", ")}. At least one of them is mandatory"))
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
  private[this] def checkKernelVersion(report:NodeSeq) : InventoryResult[NodeSeq] = {

    val failure = "Missing attribute OPERATINGSYSTEM>KERNEL_VERSION in report. This attribute is mandatory".inconsistency
    val aixFailure = "Missing attribute HARDWARE>OSVERSION in report. This attribute is mandatory".inconsistency

    checkNodeSeq(report, "OPERATINGSYSTEM", false, Some("KERNEL_VERSION")) catchAll  { _ =>
        //perhaps we are on AIX ?
        checkNodeSeq(report, "OPERATINGSYSTEM", false, Some("KERNEL_NAME")) foldM (
            _ => failure
          , x =>  if(x.toLowerCase == "aix") { //ok, check for OSVERSION
            checkNodeSeq(report, "HARDWARE", false, Some("OSVERSION")) foldM (
                _ => aixFailure
              , kernelVersion => //update the report to put it in the right place
                  (new scala.xml.transform.RuleTransformer(
                      new AddChildrenTo("OPERATINGSYSTEM", <KERNEL_VERSION>{kernelVersion}</KERNEL_VERSION>)
                  ).transform(report).head).succeed
              )
            } else {
              //should not be empty given checkOS, but if so, fails. Also fails is not aix.
              failure
            }
        )
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


  private[this] def checkAgentType(report:NodeSeq) : InventoryResult[NodeSeq] = {
    val agentTag = "AGENT_NAME"
    val tag = "AGENTNAME"
    for {
      tagHere <- {
        checkInAgentTag(report,agentTag) catchAll  { _ =>
          checkNodeSeq(report, tag).chainError(s"Missing agent name attribute ${agentTag} in report. This attribute is mandatory and must contains agent type.")
        }
      }
    } yield {
      report
    }
  }

  // since Rudder 4.3, a security token is mandatory
  private[this] def checkSecurityToken(report:NodeSeq) : InventoryResult[NodeSeq] = {
    for {
      tagHere <- checkInAgentTag(report, "CFENGINE_KEY").orElse(checkInAgentTag(report, "AGENT_CERT")).chainError(
                             "Missing security token attribute (RUDDER/AGENT/CFENGINE_KEY or RUDDER/AGENT/AGENT_CERT) " +
                             "in report. This attribute is mandatory and must contains agent certificate or public key.")
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
  private[this] def checkNodeUUID(uuid: String) : InventoryResult[String] = {
    uuid match {
      case uuidAuthCharRegex(x) => uuid.succeed
      case _ => s"""The UUID '${uuid}' is not valid. It should be lesser than 50 chars and contains chars among the set [a-zA-Z0-9\-])""".inconsistency
    }
  }

}
