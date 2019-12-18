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

package com.normation.rudder.web.comet

import scala.xml._
import net.liftweb.common._
import net.liftweb.http._
import js._
import JE._
import JsCmds._
import com.normation.rudder.batch._
import com.normation.rudder.web.components.DateFormaterService
import org.joda.time.DateTime
import bootstrap.liftweb.RudderConfig
import bootstrap.liftweb.RudderConfig.clearCacheService
import com.normation.rudder.web.model.CurrentUser

class AsyncDeployment extends CometActor with CometListener with Loggable {

  private[this] val asyncDeploymentAgent      = RudderConfig.asyncDeploymentAgent

  //current states of the deployment
  private[this] var deploymentStatus = DeploymentStatus(NoStatus, IdleDeployer)

  override def registerWith = asyncDeploymentAgent

  override val defaultHtml = NodeSeq.Empty

  override def lowPriority = {
    case d:DeploymentStatus => deploymentStatus = d ; reRender()
  }

  private[this] def displayTime(label: String, time: DateTime): NodeSeq = {
    val t = time.toString("yyyy-MM-dd HH:mm:ss")
    val d = DateFormaterService.getFormatedPeriod(time, DateTime.now)
    // exceptionnaly not putting {} to remove the noide
    <span>{label + t}</span><div class="help-block">{"↳ " + d} ago</div>
  }
  private[this] def displayDate(label: String, time: DateTime): NodeSeq = {
    val t = time.toString("yyyy-MM-dd HH:mm:ss")
    <span class="dropdown-header">{label + t}</span>
  }
  private[this] def updateDuration = {
    val content = deploymentStatus.current match {
        case SuccessStatus(_,_,end,_) => displayTime("Ended at ", end)
        case ErrorStatus(_,_,end,_)   => displayTime("Ended at ", end)
        case _                        => NodeSeq.Empty
    }
    SetHtml("deployment-end",content)
  }

  override def render = {
    partialUpdate(updateDuration)
    new RenderOut(layout)
  }

  val deployementErrorMessage = """(.*)!errormessage!(.*)""".r

  private[this] def statusBackground : String = {
    deploymentStatus.processing match {
      case IdleDeployer =>
        deploymentStatus.current match {
          case NoStatus =>
            "bg-neutral"
          case _:SuccessStatus =>
            "bg-success"
          case _:ErrorStatus =>
            "bg-error"
        }
      case _ =>
        "bg-refresh"
    }
  }

  private[this] def lastStatus  = {
    def commonStatement(start : DateTime, end : DateTime, durationText : String, headText : String, iconClass: String, statusClass : String) = {
      <li>
        <ul class="menu">
          <li><a href="#" class={statusClass + " no-click"}><span class={iconClass}></span>{headText}</a></li>
          <li><a href="#" class="no-click"><span class={statusClass +" fa fa-hourglass-start"}></span>{displayTime("Started at ", start)}</a></li>
          <li><a href="#" class="no-click"><span class={statusClass +" fa fa-hourglass-end"}></span><span id="deployment-end">{displayTime("Ended at ", end)}</span></a></li>
          <li><a href="#" class="no-click"><span class={statusClass +" fa fa-clock-o"}></span>{durationText} {DateFormaterService.getFormatedPeriod(start,end)}</a></li>
        </ul>
      </li>
    }
    def loadingStatement(start : DateTime) = {
      <li class="dropdown-header">Policies building...</li>
      <li>{displayDate("Started at ", start)}</li>
    }
    deploymentStatus.processing match {
      case IdleDeployer =>
        deploymentStatus.current match {
          case NoStatus => <li class="dropdown-header">Policy update status unavailable</li>
          case SuccessStatus(id,start,end,configurationNodes) =>
            commonStatement(start, end, "Update took", "Policies updated","text-success fa fa-check","text-success")
          case ErrorStatus(id,start,end,failure) =>
            val popupContent =
              failure.messageChain match {
              case  deployementErrorMessage(chain, error) =>
                <div class="alert alert-danger" role="alert">
                  {chain.split("<-").map(x =>  {<b>⇨&nbsp;</b>} ++ Text(x) ++  {<br/>})}
                </div>
                <br/>
                <div class="panel-group" role="tablist">
                  <div class="panel panel-default">
                    <a class="" id="showTechnicalErrorDetails" role="button" data-toggle="collapse" href="#collapseListGroup1" aria-expanded="true" aria-controls="collapseListGroup1" onclick="reverseErrorDetails()">
                      <div class="panel-heading" role="tab" id="collapseListGroupHeading1">
                        <h4 class="panel-title">
                          Show technical details
                          <span id="showhidetechnicalerrors" class="glyphicon glyphicon-chevron-up up"></span>
                        </h4>
                      </div>
                    </a>
                    <div id="collapseListGroup1" class="panel-collapse collapse in" role="tabpanel" aria-labelledby="collapseListGroupHeading1" aria-expanded="true">
                      <ul id="deploymentErrorMsg" class="list-group">
                        {error.split("<-").map(x => <li class="list-group-item">{"⇨ " + x}</li>)}
                      </ul>
                    </div>
                  </div>
                </div>

              case _ => <div class="pre">{failure.messageChain.split("<-").map(x => Text("⇨ " + x) ++ {<br/>})}</div>
            }

            val callback = JsRaw("$('#errorDetailsDialog').bsModal('show');") & SetHtml("errorDetailsMessage" , popupContent)

            commonStatement(start, end, "Error occured in", "Error during policy update","text-danger fa fa-times","text-danger") ++
            <li class="footer">{ SHtml.a(Text("Details"), callback, ("href","#") , ("style","color:#a94442 !important;"))}</li>
        }
      case Processing(id, start) => loadingStatement(start)
      case ProcessingAndPendingAuto(asked, current, a, e)      => loadingStatement(current.started)
      case ProcessingAndPendingManual(asked, current, a, e, r) => loadingStatement(current.started)
    }
  }

  private[this] def closePopup() : JsCmd = {
    JsRaw("""$('body').removeClass('modal-open').attr('style','');""")
  }

  private[this] def fullPolicyGeneration : NodeSeq = {
    <lift:authz role="deployment_write"> {
      SHtml.ajaxButton(
        "Regenerate"
        , () => {
          clearCacheService.clearNodeConfigurationCache(storeEvent = true, CurrentUser.actor)
          closePopup()
        }
        , ("class","btn btn-danger")
      )
    }
    </lift:authz>
  }
  private[this] def showGeneratePoliciesPopup : NodeSeq = {
    val callback = JsRaw("$('#generatePoliciesDialog').bsModal('show');")
    <lift:authz role="deployment_write"> {
      SHtml.a(
          Text("Regenerate all policies")
          , callback
          , ("class","regeneratePolicies")
      )
    }
    </lift:authz>
  }
  private[this] def layout = {
    <lift:authz role="deployment_read">
      <li class={"dropdown notifications-menu "++ statusBackground}>
        <a href="#" class="dropdown-toggle"  data-toggle="dropdown">
          Status
          <i class="fa fa-heartbeat"></i>
          <span id="generation-status" class="label"><span></span></span>
          <i class="fa fa-angle-down" style="margin-left:15px;"></i>
        </a>
        <ul class="dropdown-menu" role="menu">
          {lastStatus}
          <li class="footer">
            {showGeneratePoliciesPopup}
          </li>
        </ul>
      </li>
      {errorPopup}
      {generatePoliciesPopup}
    </lift:authz>
  }

  private[this] def errorPopup = {
    <div class="modal fade" data-keyboard="true" tabindex="-1" id="errorDetailsDialog">
      <div class="modal-backdrop fade in" style="height: 100%;"></div>
        <div class="modal-dialog">
            <div class="modal-content">
                <div class="modal-header">
                    <div class="close" data-dismiss="modal">
                    <span aria-hidden="true">&times;</span>
                    <span class="sr-only">Close</span>
                    </div>
                    <h4 class="modal-title">Error</h4>
                </div>
                <div class="modal-body">
                    <div class="row space-bottom">
                        <div class="col-lg-12">
                            <h4 class="text-center">
                                Policy update process was stopped due to an error:
                            </h4>
                        </div>
                    </div>
                    <div id="errorDetailsMessage" class="space-bottom space-top">
                        [Here come the error message]
                    </div>
                </div>
                <div class="modal-footer">
                    <button type="button" class="btn btn-default" data-dismiss="modal">Close</button>
                </div>
            </div><!-- /.modal-content -->
        </div><!-- /.modal-dialog -->
      </div>
  }

  private[this] def generatePoliciesPopup = {
    <div class="modal fade" data-keyboard="true" tabindex="-1" id="generatePoliciesDialog">
      <div class="modal-backdrop fade in" style="height: 100%;"></div>
      <div class="modal-dialog">
        <div class="modal-content">
          <div class="modal-header">
            <div class="close" data-dismiss="modal">
              <span aria-hidden="true">&times;</span>
              <span class="sr-only">Close</span>
            </div>
            <h4 class="modal-title">Regenerate Policies</h4>
          </div>
          <div class="modal-body">
            <div class="row space-bottom">
              <div class="col-lg-12">
                <h4 class="text-center">
                  Are you sure that you want to regenerate all policies ?
                </h4>
              </div>
            </div>
          </div>
          <div class="modal-footer">
            <button type="button" class="btn btn-default" data-dismiss="modal">Close</button>
            {fullPolicyGeneration}
          </div>
        </div><!-- /.modal-content -->
      </div><!-- /.modal-dialog -->
    </div>
  }
}

