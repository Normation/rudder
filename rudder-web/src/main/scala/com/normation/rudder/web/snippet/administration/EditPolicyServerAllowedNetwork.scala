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
import bootstrap.liftweb.LiftSpringApplicationContext.inject
import com.normation.rudder.services.servers.PolicyServerManagementService
import com.normation.utils.NetUtils.isValidNetwork
import com.normation.rudder.domain.Constants
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.services.servers.PolicyServerManagementService
import com.normation.eventlog.EventLogService
import com.normation.rudder.batch.AsyncDeploymentAgent
import com.normation.rudder.domain.log.UpdatePolicyServer
import com.normation.eventlog.EventLogDetails
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.domain.log.AuthorizedNetworkModification

class EditPolicyServerAllowedNetwork extends DispatchSnippet with Loggable {
      
  private[this] val psService = inject[PolicyServerManagementService]
  private[this] val eventLogService = inject[EventLogService]
  private[this] val asyncDeploymentAgent = inject[AsyncDeploymentAgent]
  
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
  
  private[this] val currentNets = psService.getAuthorizedNetworks(Constants.ROOT_POLICY_SERVER_ID)
  
  private[this] val allowedNetworks = Buffer() ++ 
    currentNets.getOrElse(Nil).map(n => VH(net = n))
  
  def dispatch = {
    case "render" =>
      currentNets match {
        case e:EmptyBox =>  errorMessage(e)
        case Full(_) => renderForm
      }
  }
  
  
  def errorMessage(b:EmptyBox) = {
    val error = b ?~! "Error when processing allowed network"
    logger.debug(error.messageChain, b)
    
    "#allowedNetworksForm *" #> { x =>
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
  
  def renderForm : IdMemoizeTransform = SHtml.idMemoize { outerXml =>
    
    // our process method returns a
    // JsCmd which will be sent back to the browser
    // as part of the response
    def process(): JsCmd = {
      //clear errors
      S.clearCurrentNotices
      val goodNets = Buffer[String]()
      
      allowedNetworks.foreach { case v@VH(i,net) =>
        if(net.trim.length != 0) {
          if(!isValidNetwork(net)) {
            S.error("errornetwork_"+ i, "Bad format for given network")
          } else {
            goodNets += net
          }
        }
      }
      
      //if no errors, actually save
      if(S.errors.isEmpty) {
        (for {
          currentNetworks <- psService.getAuthorizedNetworks(Constants.ROOT_POLICY_SERVER_ID) ?~! "Error when getting the list of current authorized networks"
          changeNetwork   <- psService.setAuthorizedNetworks(Constants.ROOT_POLICY_SERVER_ID, goodNets, CurrentUser.getActor) ?~! "Error when saving new allowed networks"
          modifications   =  UpdatePolicyServer.buildDetails(AuthorizedNetworkModification(currentNetworks, goodNets)) 
          eventSaved      <- eventLogService.saveEventLog(UpdatePolicyServer(EventLogDetails(principal = CurrentUser.getActor, details = modifications))) ?~! "Unable to save the user event log for modification on authorized networks"
        } yield {
        }) match {
          case Full(_) => 
            asyncDeploymentAgent ! AutomaticStartDeployment(CurrentUser.getActor)
            
            Replace("allowedNetworksForm", outerXml.applyAgain) &
            successPopup 
          case e:EmptyBox => SetHtml("allowedNetworksForm",errorMessage(e)(outerXml.applyAgain))
        }
        
      } else Noop
    }
    
    def delete(i:Long) : JsCmd = {
      allowedNetworks -= VH(i)
      Replace("allowedNetworksForm", outerXml.applyAgain)
    }
    
    def add() : JsCmd = {
      allowedNetworks.append(VH())
      Replace("allowedNetworksForm", outerXml.applyAgain)
    }
    
    //process the list of networks
    "#allowNetworkFields *" #> { xml =>
      allowedNetworks.flatMap { case VH(i,net) =>
        val id = "network_"+ i
        
        (
          ".deleteNetwork" #> SHtml.ajaxSubmit("-", () => delete(i)) &
          "errorClass=error [id]" #> ("error" + id) &
          ".networkField [name]" #> id andThen
          ".networkField" #> SHtml.text(net,  {x => 
            allowedNetworks.find { case VH(y,_) => y==i }.foreach{ v => v.net = x }
          },  "id" -> id)
        )(xml)
      }
    } & 
    "#addNetworkButton" #> SHtml.ajaxSubmit("Add a network", add) &
    "#submitAllowedNetwork" #> { 
      SHtml.ajaxSubmit("Submit", process _) ++ Script(OnLoad(JsRaw(""" correctButtons(); """)))
    }
  }
  
  
  ///////////// success pop-up ///////////////
  private[this] def successPopup : JsCmd = {
    JsRaw(""" callPopupWithTimeout(200, "successConfirmationDialog", 100, 350)     
    """)
  }  
}