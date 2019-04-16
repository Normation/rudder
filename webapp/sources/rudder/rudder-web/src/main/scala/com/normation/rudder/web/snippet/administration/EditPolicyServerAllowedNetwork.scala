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

import net.liftweb._
import http._
import common._
import util.Helpers._
import js._
import JsCmds._
import JE._
import scala.xml.NodeSeq
import collection.mutable.Buffer
import com.normation.rudder.domain.Constants
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.services.servers.PolicyServerManagementService
import com.normation.rudder.domain.eventlog.UpdatePolicyServer
import com.normation.eventlog.EventLogDetails
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.domain.eventlog.AuthorizedNetworkModification
import com.normation.eventlog.ModificationId
import bootstrap.liftweb.RudderConfig
import com.normation.inventory.domain.NodeId

import com.normation.box._

class EditPolicyServerAllowedNetwork extends DispatchSnippet with Loggable {

  private[this] val psService            = RudderConfig.policyServerManagementService
  private[this] val eventLogService      = RudderConfig.eventLogRepository
  private[this] val asyncDeploymentAgent = RudderConfig.asyncDeploymentAgent
  private[this] val uuidGen              = RudderConfig.stringUuidGenerator
  private[this] val nodeInfoService      = RudderConfig.nodeInfoService
  private[this] var addNetworkField = ""
  /*
   * We are forced to use that class to deals with multiple request
   * not processed in order (ex: one request add an item, the second remove
   * item at index 0, the third remove item at index 0. Now, any order for
   * these requests have to be considered and lead to the same result.
   */
  private[this] case class VH(id:Long = nextNum, var net : String = "") {
    override def hashCode = id.hashCode
    override def equals(x:Any) = x match {
      case VH(i, _) => id == i
      case _ => false
    }
  }

  private[this] val policyServers = nodeInfoService.getAllSystemNodeIds()

  // we need to store that out of the form, so that the changes are persisted at redraw
  private[this] val allowedNetworksMap = scala.collection.mutable.Map[NodeId, Buffer[VH]]()

  def dispatch = {
    case "render" =>
      policyServers match {
        case e:EmptyBox =>  errorMessage("#allowedNetworksForm", e)
        case Full(seq) =>
          // we need to order the seq to have root first
          val sortedSeq =  Constants.ROOT_POLICY_SERVER_ID +: seq.filter(x => x != Constants.ROOT_POLICY_SERVER_ID)
          xml:NodeSeq =>  {
              sortedSeq.foldLeft(NodeSeq.Empty)((result, id) => result++renderForm(id).apply(xml)) }
      }
  }

  def errorMessage(htmlId: String, b:EmptyBox) = {
    val error = b ?~! "Error when processing allowed network"
    logger.error(error.messageChain, b)

    s"${htmlId} *" #> { (x:NodeSeq) =>
      <div class="error">
      <p>An error occured when trying to get the list of existing allowed networks</p>
      {
          b match {
            case Failure(m,_,_) => <p>Error message was: {m}</p>
            case _ => <p>No error message was left</p>
          }

        }
    </div>
    }
  }

  def renderForm(policyServerId: NodeId) : IdMemoizeTransform = SHtml.idMemoize { outerXml =>

    val allowedNetworksFormId = "allowedNetworksForm" + policyServerId.value

    val policyServerName = nodeInfoService.getNodeInfo(policyServerId) match {
      case Full(Some(nodeInfo)) =>
        <span>{nodeInfo.hostname}</span>
      case eb: EmptyBox =>
        val e = eb ?~! s"Could not get details for Policy Server ID ${policyServerId.value}"
        logger.error(e.messageChain)
        <span class="error">Unknown hostname</span>
      case Full(None) =>
        logger.error(s"Could not get details for Policy Server ID ${policyServerId.value}, the details were not found for that ID")
        <span class="error">Unknown hostname</span>
    }

    val currentNets = psService.getAuthorizedNetworks(policyServerId)
    val allowedNetworks = allowedNetworksMap.getOrElseUpdate(policyServerId,
        Buffer() ++ currentNets.getOrElse(Nil).map(n => VH(net = n)))

    // our process method returns a
    // JsCmd which will be sent back to the browser
    // as part of the response
    def process(): JsCmd = {
      //clear errors
      S.clearCurrentNotices
      val goodNets = Buffer[String]()

      allowedNetworks.foreach { case v@VH(i,net) =>
        val netWithoutSpaces = net.replaceAll("""\s""", "")
        if(netWithoutSpaces.length != 0) {
          if(!PolicyServerManagementService.isValidNetwork(netWithoutSpaces)) {
            S.error("errornetwork_"+ i, "Bad format for given network")
          } else {
            goodNets += netWithoutSpaces
          }
        }
      }

      //if no errors, actually save
      if(S.errors.isEmpty) {
        val modId = ModificationId(uuidGen.newUuid)
        (for {
          currentNetworks <- psService.getAuthorizedNetworks(policyServerId) ?~! s"Error when getting the list of current authorized networks for policy server ${policyServerId.value}"
          changeNetwork   <- psService.setAuthorizedNetworks(policyServerId, goodNets, modId, CurrentUser.actor) ?~! "Error when saving new allowed networks for policy server ${policyServerId.value}"
          modifications   =  UpdatePolicyServer.buildDetails(AuthorizedNetworkModification(currentNetworks, goodNets))
          eventSaved      <- eventLogService.saveEventLog(modId,
                               UpdatePolicyServer(EventLogDetails(
                                 modificationId = None
                               , principal = CurrentUser.actor
                               , details = modifications
                               , reason = None))).toBox ?~! "Unable to save the user event log for modification on authorized networks for policy server ${policyServerId.value}"
        } yield {
        }) match {
          case Full(_) =>
            asyncDeploymentAgent ! AutomaticStartDeployment(modId, CurrentUser.actor)
            Replace(allowedNetworksFormId, outerXml.applyAgain) &
            successNotification
          case e:EmptyBox => SetHtml(allowedNetworksFormId,errorMessage(s"#${allowedNetworksFormId}", e)(outerXml.applyAgain))
        }

      } else Noop
    }

    def delete(i:Long) : JsCmd = {
      allowedNetworks -= VH(i)
      allowedNetworksMap.put(policyServerId, allowedNetworks)
      Replace(allowedNetworksFormId, outerXml.applyAgain)
    }

    def add() : JsCmd = {
      allowedNetworks.append(VH(net = addNetworkField))
      addNetworkField = ""
      allowedNetworksMap.put(policyServerId, allowedNetworks)
      Replace(allowedNetworksFormId, outerXml.applyAgain)
    }

    //process the list of networks
    currentNets match {
      case eb: EmptyBox => errorMessage("#allowedNetworksForm", eb)
      case _            =>
        "#allowedNetworksForm [id]" #> allowedNetworksFormId andThen
        "#policyServerDetails" #> <h3>{"Allowed networks for policy server "}{policyServerName} {s"(Rudder ID: ${policyServerId.value})"}</h3> &
        "#allowNetworkFields [id]" #> s"allowNetworkFields${policyServerId.value}" &
        "#allowNetworkFields *" #> { (xml:NodeSeq) =>
          allowedNetworks.flatMap { case VH(i,net) =>
            val id = "network_"+ i

            (
              ".deleteNetwork" #> SHtml.ajaxButton(<span class="glyphicon glyphicon-minus"></span>, () => delete(i)) &
              "#errorNetworkField" #> <div><span class={"lift:Msg?errorClass=bs-text-danger;id=errornetwork_"+i}>[error]</span></div> &
              ".networkField [name]" #> id andThen
              ".networkField" #> SHtml.text(net,  {x =>
                allowedNetworks.find { case VH(y,_) => y==i }.foreach{ v => v.net = x }
              },  "id" -> id)
            )(xml)
          }
        }  &
        "#addNetworkButton" #> SHtml.ajaxButton(<span class="glyphicon glyphicon-plus"></span>, add _ , ("id", s"addNetworkButton${policyServerId.value}")) &
        "#addaNetworkfield" #> SHtml.ajaxText(addNetworkField, addNetworkField = _, ("id", s"addaNetworkfield${policyServerId.value}")) &
        "#submitAllowedNetwork" #> {
          SHtml.ajaxSubmit("Save changes", process _,("id",s"submitAllowedNetwork${policyServerId.value}"), ("class","btn btn-default")) ++ Script(
            OnLoad (
              JsRaw(s"""
                initInputAddress("${policyServerId.value}")
                $$(".networkField").keydown( function(event) {
                  processKey(event , 'submitAllowedNetwork${policyServerId.value}')
                });
              """)
            )
          )
        }
    }
  }

  ///////////// success pop-up ///////////////
  private[this] def successNotification : JsCmd = {
    JsRaw(""" createSuccessNotification() """)
  }
}
