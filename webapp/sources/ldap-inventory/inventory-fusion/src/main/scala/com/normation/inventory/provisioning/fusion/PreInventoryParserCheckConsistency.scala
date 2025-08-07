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

import com.normation.errors.*
import com.normation.inventory.domain.InventoryError
import com.normation.inventory.services.provisioning.*
import com.normation.utils.NodeIdRegex
import scala.xml.Elem
import scala.xml.Node
import scala.xml.NodeSeq
import zio.*
import zio.syntax.*

class PreInventoryParserCheckConsistency extends PreInventoryParser {
  override val name = "post_process_inventory:check_consistency"

  implicit class ToInconsistency(msg: String) {
    def inconsistency: IO[InventoryError.Inconsistency, Nothing] = InventoryError.Inconsistency(msg).fail
  }

  /**
   * There is a list of variables that MUST be set at that point:
   * - an id for the node (it is ok that it may be change later on the process, for ex. for unusual changes)
   * - a hostname
   * - an admin (root user on the node)
   * - a policy server id
   * - an OS name
   *
   * If any of these variable are not set, just abort
   */
  override def apply(inventory: NodeSeq): IOResult[NodeSeq] = {
    val agentTagContent  = getInTags(inventory, "AGENT")
    val rudderTagContent = getInTags(inventory, "RUDDER")

    // hostname is now only check in FusionInventoryParser
    val checks = {
      checkId(rudderTagContent) ::
      checkRoot(agentTagContent) ::
      checkPolicyServer(agentTagContent) ::
      checkOS ::
      checkKernelVersion ::
      checkAgentType(agentTagContent) ::
      checkSecurityToken(agentTagContent) ::
      Nil
    }

    ZIO.foldLeft(checks)(inventory)((currentInventory, check) => check(currentInventory))
  }

  // Utility method to get only once the RUDDER and the AGENT
  private def getInTags(xml: NodeSeq, tag: String): NodeSeq = {
    xml \\ tag
  }

  private def checkWithinNodeSeq(nodes: NodeSeq, child: String): IOResult[String] = {
    val nodes2 = nodes \ child

    nodes2 match {
      case NodeSeq.Empty => s"Missing XML element: '${child}.".inconsistency
      case x             =>
        x.head.text match {
          case null | "" => s"Tag ${child} content is empty".inconsistency
          case s         => s.succeed
        }
    }
  }

  private def checkNodeSeq(
      xml:            NodeSeq,
      tag:            String,
      directChildren: Boolean = false,
      optChild:       Option[String] = None
  ): IOResult[String] = {
    val nodes = {
      (
        if (directChildren) (xml \ tag)
        else (xml \\ tag)
      )
    }

    val nodes2 = optChild match {
      case None    => nodes
      case Some(t) => nodes \ t
    }

    nodes2 match {
      case NodeSeq.Empty => s"Missing XML element: '${optChild.getOrElse(tag)}'. ".inconsistency
      case x             =>
        x.head.text match {
          case null | "" => s"Tag '${optChild.getOrElse(tag)}' content is empty".inconsistency
          case s         => s.succeed
        }
    }
  }

  private def checkId(rudderNodeSeq: NodeSeq)(inventory: NodeSeq): IOResult[NodeSeq] = {
    val tag = "UUID"
    for {
      tagHere <- {
        checkWithinNodeSeq(rudderNodeSeq, tag) catchAll { _ =>
          checkNodeSeq(inventory, tag, directChildren = true).chainError(
            s"Missing node ID attribute '${tag}' in inventory. This attribute is mandatory and must contains node ID."
          )
        }
      }
      uuidOK  <- NodeIdRegex.checkNodeId(tagHere).toIO
    } yield {
      inventory
    }
  }

  private def checkRoot(agentNodeSeq: NodeSeq)(inventory: NodeSeq): IOResult[NodeSeq] = {
    val agentTag = "OWNER"
    val tag      = "USER"
    for {
      tagHere <- {
        checkWithinNodeSeq(agentNodeSeq, agentTag) catchAll { _ =>
          checkNodeSeq(inventory, tag).chainError(
            s"Missing administrator attribute '${tag}' in inventory. This attribute is mandatory and must contains node local administrator login."
          )
        }
      }
    } yield {
      inventory
    }
  }

  private def checkPolicyServer(agentNodeSeq: NodeSeq)(inventory: NodeSeq): IOResult[NodeSeq] = {
    val agentTag = "POLICY_SERVER_UUID"
    val tag      = "POLICY_SERVER"
    for {
      tagHere <- {
        checkWithinNodeSeq(agentNodeSeq, agentTag) catchAll { _ =>
          checkNodeSeq(inventory, tag).chainError(
            s"Missing rudder policy server attribute '${agentTag}' in inventory. This attribute is mandatory and must contain Rudder ID pour policy server."
          )
        }
      }
    } yield {
      inventory
    }
  }

  private def checkOS(inventory: NodeSeq): IOResult[NodeSeq] = {
    // VERSION is not mandatory on windows, it can't be added in that list
    val tags  = "FULL_NAME" :: "KERNEL_NAME" :: "NAME" :: Nil
    val error = InventoryError.Inconsistency(
      s"Missing tags ${tags.map(t => s"OPERATINGSYSTEM/${t}").mkString(", ")}. At least one of them is mandatory"
    )
    val zero: Either[RudderError, String] = Left(error)
    ZIO
      .absolve(
        ZIO.foldLeft(tags)(zero)((a, b) => {
          a match {
            case Right(x) => Right(x).succeed
            case Left(_)  => checkNodeSeq(inventory, "OPERATINGSYSTEM", directChildren = false, optChild = Some(b)).either
          }
        })
      )
      .foldZIO(_ => error.fail, _ => inventory.succeed)
  }

  /**
   * Kernel version: either
   * - non-empty OPERATINGSYSTEM > KERNEL_VERSION
   * or
   * - (on AIX and non empty HARDWARE > OSVERSION )
   * Other cases are failure (missing required info)
   */
  private def checkKernelVersion(inventory: NodeSeq): IOResult[NodeSeq] = {

    val failure    = "Missing attribute OPERATINGSYSTEM>KERNEL_VERSION in inventory. This attribute is mandatory".inconsistency
    val aixFailure = "Missing attribute HARDWARE>OSVERSION in inventory. This attribute is mandatory".inconsistency

    checkNodeSeq(inventory, "OPERATINGSYSTEM", directChildren = false, optChild = Some("KERNEL_VERSION"))
      .map(_ => inventory)
      .catchAll { _ =>
        // perhaps we are on AIX ?
        checkNodeSeq(inventory, "OPERATINGSYSTEM", directChildren = false, optChild = Some("KERNEL_NAME"))
          .foldZIO(
            _ => failure,
            x => {
              if (x.toLowerCase == "aix") { // ok, check for OSVERSION
                checkNodeSeq(inventory, "HARDWARE", directChildren = false, optChild = Some("OSVERSION")).foldZIO(
                  _ => aixFailure,
                  kernelVersion => { // update the inventory to put it in the right place
                    (new scala.xml.transform.RuleTransformer(
                      new AddChildrenTo("OPERATINGSYSTEM", <KERNEL_VERSION>{kernelVersion}</KERNEL_VERSION>)
                    ).transform(inventory).head).succeed
                  }
                )
              } else {
                // should not be empty given checkOS, but if so, fails. Also fails is not aix.
                failure
              }
            }
          )
          .map(n => n: NodeSeq)
      }
  }

  // for check kernel version
  private class AddChildrenTo(label: String, newChild: scala.xml.Node) extends scala.xml.transform.RewriteRule {
    override def transform(n: scala.xml.Node): scala.collection.Seq[Node] = n match {
      case Elem(prefix, "OPERATINGSYSTEM", attribs, scope, child*) =>
        Elem(prefix, label, attribs, scope, minimizeEmpty = false, child ++ newChild*)
      case other                                                   => other
    }
  }

  private def checkAgentType(agentNodeSeq: NodeSeq)(inventory: NodeSeq): IOResult[NodeSeq] = {
    val agentTag = "AGENT_NAME"
    val tag      = "AGENTNAME"
    for {
      tagHere <- {
        checkWithinNodeSeq(agentNodeSeq, agentTag) catchAll { _ =>
          checkNodeSeq(inventory, tag).chainError(
            s"Missing agent name attribute ${agentTag} in inventory. This attribute is mandatory and must contains agent type."
          )
        }
      }
    } yield {
      inventory
    }
  }

  // since Rudder 6.0, an agent certificate is mandatory
  private def checkSecurityToken(agentNodeSeq: NodeSeq)(inventory: NodeSeq): IOResult[NodeSeq] = {
    for {
      tagHere <- checkWithinNodeSeq(agentNodeSeq, "AGENT_CERT")
                   .chainError(
                     "Missing security token attribute (RUDDER/AGENT/AGENT_CERT) " +
                     "in inventory. This attribute is mandatory and must contains the agent certificate"
                   )
    } yield {
      inventory
    }
  }

}
