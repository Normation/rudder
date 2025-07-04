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

package com.normation.rudder.web.snippet.administration

import bootstrap.liftweb.RudderConfig
import collection.mutable.Buffer
import com.normation.box.*
import com.normation.eventlog.EventLogDetails
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.NodeId
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.eventlog.AuthorizedNetworkModification
import com.normation.rudder.domain.eventlog.UpdatePolicyServer
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.services.servers.AllowedNetwork
import com.normation.rudder.users.CurrentUser
import com.normation.rudder.web.snippet.WithNonce
import com.normation.zio.*
import net.liftweb.*
import net.liftweb.common.*
import net.liftweb.http.*
import net.liftweb.http.js.*
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import net.liftweb.util.CssSel
import scala.xml.NodeSeq
import util.Helpers.*

class EditPolicyServerAllowedNetwork extends DispatchSnippet with Loggable {

  private val psService            = RudderConfig.policyServerManagementService
  private val eventLogService      = RudderConfig.eventLogRepository
  private val asyncDeploymentAgent = RudderConfig.asyncDeploymentAgent
  private val uuidGen              = RudderConfig.stringUuidGenerator
  private val nodeFactRepo         = RudderConfig.nodeFactRepository
  private var addNetworkField      = ""
  /*
   * We are forced to use that class to deals with multiple request
   * not processed in order (ex: one request add an item, the second remove
   * item at index 0, the third remove item at index 0. Now, any order for
   * these requests have to be considered and lead to the same result.
   */
  private case class VH(id: Long = nextNum, var net: String = "") {
    override def hashCode = id.hashCode
    override def equals(x: Any): Boolean = x match {
      case VH(i, _) => id == i
      case _        => false
    }
  }

  // we need to store that out of the form, so that the changes are persisted at redraw
  private val allowedNetworksMap = scala.collection.mutable.Map[NodeId, Buffer[VH]]()

  def dispatch: PartialFunction[String, NodeSeq => NodeSeq] = {
    case "render" =>
      implicit val qc: QueryContext = CurrentUser.queryContext // bug https://issues.rudder.io/issues/26605
      val policyServers = nodeFactRepo
        .getAll()
        .map(_.collect { case (id, f) if (f.rudderSettings.kind.isPolicyServer) => id }.toSeq)
        .toBox

      policyServers match {
        case e: EmptyBox => errorMessage("#allowedNetworksForm", e)
        case Full(seq) =>
          // we need to order the seq to have root first
          val sortedSeq = Constants.ROOT_POLICY_SERVER_ID +: seq.filter(x => x != Constants.ROOT_POLICY_SERVER_ID)
          (xml: NodeSeq) => {
            sortedSeq.foldLeft(NodeSeq.Empty)((result, id) => result ++ renderForm(id).apply(xml))
          }
      }
  }

  def errorMessage(htmlId: String, b: EmptyBox): CssSel = {
    val error = b ?~! "Error when processing allowed network"
    logger.error(error.messageChain)

    s"${htmlId} *" #> { (x: NodeSeq) =>
      <div class="error">
      <p>An error occured when trying to get the list of existing allowed networks</p>
      {
        b match {
          case Failure(m, _, _) => <p>Error message was: {m}</p>
          case _                => <p>No error message was left</p>
        }

      }
    </div>
    }
  }

  def renderForm(policyServerId: NodeId)(implicit qc: QueryContext): IdMemoizeTransform = SHtml.idMemoize { outerXml =>
    val allowedNetworksFormId = "allowedNetworksForm" + policyServerId.value

    val policyServerName = nodeFactRepo.get(policyServerId).either.runNow match {
      case Right(Some(nodeInfo)) =>
        <span>{nodeInfo.fqdn}</span>
      case Left(err)             =>
        val e = s"Could not get details for Policy Server ID ${policyServerId.value}: ${err.fullMsg}"
        logger.error(e)
        <span class="error">Unknown hostname</span>
      case Right(None)           =>
        logger.error(
          s"Could not get details for Policy Server ID ${policyServerId.value}, the details were not found for that ID"
        )
        <span class="error">Unknown hostname</span>
    }

    val currentNets     = psService.getAllowedNetworks(policyServerId).toBox
    val allowedNetworks =
      allowedNetworksMap.getOrElseUpdate(policyServerId, Buffer() ++ currentNets.getOrElse(Nil).map(n => VH(net = n.inet)))

    // our process method returns a
    // JsCmd which will be sent back to the browser
    // as part of the response
    def process(): JsCmd = {
      // clear errors
      S.clearCurrentNotices
      val goodNets = Buffer[String]()

      allowedNetworks.foreach {
        case v @ VH(i, net) =>
          val netWithoutSpaces = net.replaceAll("""\s""", "")
          if (netWithoutSpaces.length != 0) {
            if (!AllowedNetwork.isValid(netWithoutSpaces)) {
              S.error("errornetwork_" + i, "Bad format for given network")
            } else {
              goodNets += netWithoutSpaces
            }
          }
      }

      // for now, allowed networks have the same name as inet
      val gootNetsSeq = goodNets.toSeq.map(s => AllowedNetwork(s, s))

      // if no errors, actually save
      if (S.errors.isEmpty) {
        val modId = ModificationId(uuidGen.newUuid)
        (for {
          currentNetworks <-
            psService
              .getAllowedNetworks(policyServerId)
              .toBox ?~! s"Error when getting the list of current authorized networks for policy server ${policyServerId.value}"
          changeNetwork   <- psService
                               .setAllowedNetworks(policyServerId, gootNetsSeq, modId, CurrentUser.actor)
                               .toBox ?~! s"Error when saving new allowed networks for policy server ${policyServerId.value}"
          modifications    =
            UpdatePolicyServer.buildDetails(AuthorizedNetworkModification(currentNetworks.map(_.inet), gootNetsSeq.map(_.inet)))
          eventSaved      <-
            eventLogService
              .saveEventLog(
                modId,
                UpdatePolicyServer(
                  EventLogDetails(modificationId = None, principal = CurrentUser.actor, details = modifications, reason = None)
                )
              )
              .toBox ?~! s"Unable to save the user event log for modification on authorized networks for policy server ${policyServerId.value}"
        } yield {}) match {
          case Full(_) =>
            asyncDeploymentAgent ! AutomaticStartDeployment(modId, CurrentUser.actor)
            Replace(allowedNetworksFormId, outerXml.applyAgain()) &
            successNotification
          case e: EmptyBox => SetHtml(allowedNetworksFormId, errorMessage(s"#${allowedNetworksFormId}", e)(outerXml.applyAgain()))
        }

      } else Noop
    }

    def delete(i: Long): JsCmd = {
      allowedNetworks -= VH(i)
      allowedNetworksMap.put(policyServerId, allowedNetworks)
      Replace(allowedNetworksFormId, outerXml.applyAgain())
    }

    def add(): JsCmd = {
      allowedNetworks.append(VH(net = addNetworkField))
      addNetworkField = ""
      allowedNetworksMap.put(policyServerId, allowedNetworks)
      Replace(allowedNetworksFormId, outerXml.applyAgain())
    }

    // process the list of networks
    currentNets match {
      case eb: EmptyBox => errorMessage("#allowedNetworksForm", eb)
      case _ =>
        "#allowedNetworksForm [id]" #> allowedNetworksFormId andThen
        "#policyServerDetails" #> <h3>{"Allowed networks for policy server "}{policyServerName} {
          s"(Rudder ID: ${policyServerId.value})"
        }</h3> &
        "#allowNetworkFields [id]" #> s"allowNetworkFields${policyServerId.value}" &
        "#allowNetworkFields *" #> { (xml: NodeSeq) =>
          allowedNetworks.flatMap {
            case VH(i, net) =>
              val id = "network_" + i

              (
                ".deleteNetwork" #> SHtml.ajaxButton(<span class="fa fa-minus"></span>, () => delete(i)) &
                "#errorNetworkField" #> <div><span class={
                  "lift:Msg?errorClass=text-danger;id=errornetwork_" + i
                }>[error]</span></div> &
                ".networkField [name]" #> id andThen
                ".networkField" #> SHtml.text(
                  net,
                  x => allowedNetworks.find { case VH(y, _) => y == i }.foreach(v => v.net = x),
                  "id" -> id
                )
              )(xml)
          }
        } &
        "#addNetworkButton" #> SHtml.ajaxButton(
          <span class="fa fa-plus"></span>,
          add _,
          ("id", s"addNetworkButton${policyServerId.value}")
        ) &
        "#addaNetworkfield" #> SHtml.ajaxText(
          addNetworkField,
          addNetworkField = _,
          ("id", s"addaNetworkfield${policyServerId.value}")
        ) &
        "#submitAllowedNetwork" #> {
          (SHtml.ajaxSubmit(
            "Save changes",
            process _,
            ("id", s"submitAllowedNetwork${policyServerId.value}"),
            ("class", "btn btn-success")
          ): NodeSeq) ++ WithNonce.scriptWithNonce(
            Script(
              OnLoad(
                JsRaw(s"""
                initInputAddress("${policyServerId.value}")
                $$(".networkField").keydown( function(event) {
                  processKey(event , 'submitAllowedNetwork${policyServerId.value}')
                });
              """) // JsRaw ok, no user inputs
              )
            )
          )
        }
    }
  }

  ///////////// success pop-up ///////////////
  private def successNotification: JsCmd = {
    JsRaw(""" createSuccessNotification() """) // JsRaw ok, const
  }
}
