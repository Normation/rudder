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
import net.liftweb.util._
import Helpers._
import net.liftweb.http._
import js._
import JE._
import JsCmds._
import com.normation.rudder.batch._
import org.joda.time.Duration
import org.joda.time.format.PeriodFormatterBuilder
import com.normation.rudder.web.components.DateFormaterService
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.services.eventlog.EventLogDeploymentService
import com.normation.eventlog.EventLog
import com.normation.rudder.domain.eventlog.ModificationWatchList
import com.normation.eventlog.UnspecializedEventLog
import com.normation.rudder.domain.eventlog.RudderEventActor
import org.joda.time.DateTime
import net.liftweb.common.EmptyBox
import com.normation.rudder.web.services.EventListDisplayer
import com.normation.eventlog.EventLogDetails
import com.normation.utils.StringUuidGenerator
import com.normation.eventlog.ModificationId
import bootstrap.liftweb.RudderConfig

class AsyncDeployment extends CometActor with CometListener with Loggable {

  private[this] val asyncDeploymentAgent      = RudderConfig.asyncDeploymentAgent
  private[this] val eventLogDeploymentService = RudderConfig.eventLogDeploymentService
  private[this] val eventList                 = RudderConfig.eventListDisplayer
  private[this] val uuidGen                   = RudderConfig.stringUuidGenerator

  //current states of the deployment
  private[this] var deploymentStatus = DeploymentStatus(NoStatus, IdleDeployer)

  // the last deployment data
  private[this] var lastSuccessfulDeployement : Box[EventLog] = eventLogDeploymentService.getLastSuccessfulDeployement()
  private[this] var lastEventSinceDeployment : Box[Seq[EventLog]] = Empty

  override def registerWith = asyncDeploymentAgent

  override def lowPriority = {
    case d:DeploymentStatus => deploymentStatus = d ; reRender()
  }

  private[this] def updateDuration = {
    val content =
      deploymentStatus.current match {
        case SuccessStatus(_,_,end,_) =>Text(DateFormaterService.getFormatedPeriod(end, DateTime.now))
        case ErrorStatus(_,_,end,_) =>Text(DateFormaterService.getFormatedPeriod(end, DateTime.now))
        case _ =>
          NodeSeq.Empty
    }
    SetHtml("deployment-end",content)

  }

  override def render = {
    partialUpdate(updateDuration)
    new RenderOut(layout)
  }

  val deployementErrorMessage = """(.*)!errormessage!(.*)""".r

  private[this] def statusIcon : NodeSeq = {

    deploymentStatus.processing match {
      case IdleDeployer =>
        deploymentStatus.current match {
          case NoStatus =>
            <span class="glyphicon glyphicon-question-sign"></span>
          case _:SuccessStatus =>
            <span class="glyphicon glyphicon-ok"></span>
          case _:ErrorStatus =>
            <span class="glyphicon glyphicon-remove"></span>
        }
      case _ =>
          <img src="/images/ajax-loader-small.gif" />
    }

  }

  private[this] def lastStatus  = {
    def commonStatement(start : DateTime, end : DateTime, durationText : String, headText : String) = {
      <li class="dropdown-header">{headText}</li>
      <li class="dropdown-header">Started at {DateFormaterService.getFormatedDate(start)}</li>
      <li class="dropdown-header" >Finished <span id="deployment-end">{DateFormaterService.getFormatedPeriod(end, DateTime.now)}</span> ago</li>
      <li class="dropdown-header">{durationText} {DateFormaterService.getFormatedPeriod(start,end)}</li>
    }
    deploymentStatus.current match {
      case NoStatus => <li class="dropdown-header">Policy update status unavailable</li>
      case SuccessStatus(id,start,end,configurationNodes) =>
        commonStatement(start, end, "Update took", "Policies updated")
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

              
            case _ => <span>{failure.messageChain.split("<-").map(x => Text("⇨ " + x) ++ {<br/>})}</span>
        }
        
        val callback = JsRaw("$('#errorDetailsDialog').bsModal('show');") & SetHtml("errorDetailsMessage" , popupContent)
        
        commonStatement(start, end, "Error occured in", "Error during policy update") ++
        <li>{ SHtml.a(Text("Details"), callback, ("href","#"))}</li>
    }
  }

  private[this] def currentStatus : NodeSeq = {

    <lift:authz role="deployment_write"> {
      SHtml.a( {
          () =>
            asyncDeploymentAgent ! ManualStartDeployment(ModificationId(uuidGen.newUuid), CurrentUser.getActor, "User requested a manual policy update") //TODO: let the user fill the cause
            Noop
        }
        , Text("Update")
      )
    }
    </lift:authz>

  }

  private[this] def layout = {

    <lift:authz role="deployment_read">
    <li class="dropdown">
      <a href="#" class="dropdown-toggle"  data-toggle="dropdown">
        <span>Status</span> <span class="badge" id="generation-status"> {statusIcon}</span><span class="caret" style="margin-left:5px"></span></a>
      <ul class="dropdown-menu" role="menu">
        {lastStatus}
        <li>{currentStatus}</li>
      </ul>
    </li>
    {errorPopup}
    </lift:authz>
  }

  private[this] def errorPopup = {
      <div class="modal fade" id="errorDetailsDialog">
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

}
