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

  private[this] val periodFormatter = new PeriodFormatterBuilder().
    appendDays().
    appendSuffix(" day ", " days ").
    appendSeparator(", ").
    appendMinutes().
    appendSuffix(" min ", " min ").
    appendSeparator(" ").
    appendSeconds().
    appendSuffix("s", "s")
    .toFormatter()

  private[this] def formatPeriod(duration:Duration) : String = {
    if(duration.getMillis < 1000) "less than 1s"
    else periodFormatter.print(duration.toPeriod)
  }

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

  override def render = {
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

    deploymentStatus.current match {
      case NoStatus => <li class="dropdown-header">Policy update status unavailable</li>
      case SuccessStatus(id,start,end,configurationNodes) =>
        <li class="dropdown-header">Policies updated</li>
        <li class="dropdown-header">Update took {formatPeriod(new Duration(start,end))}</li>
      case ErrorStatus(id,start,end,failure) =>
        <li class="dropdown-header">Error during policy update</li>
        <li class="dropdown-header">Error occured in {formatPeriod(new Duration(start,end))}</li>
        <li><a href="#" onClick={
            """$('#errorDetailsDialog').modal({ minHeight:140, minWidth: 300 });
               $('#simplemodal-container').css('height', 'auto');
               correctButtons();
               return false;"""}>Details</a></li> ++ {
          ("#errorDetailsMessage" #> { failure.messageChain match {
            case  deployementErrorMessage(chain, error) =>
              <span>{chain.split("<-").map(x => Text("⇨ " + x) ++ {<br/>})}</span>
              <br/>
              <div class="curspoint listopen" onClick="$('#deploymentErrorMsg').toggle();$('#simplemodal-container').css('width', '80%');$('#simplemodal-container').resize();$(this).toggleClass('listopen');$(this).toggleClass('listclose');"><b>Show technical details</b></div>
              <br/>
              <fieldset id="deploymentErrorMsg" style="display:none;"><legend><b>Technical details</b></legend>
                <span>{error.split("<-").map(x => Text("⇨ " + x) ++ {<br/>})}<br/></span>
              </fieldset>
            case _ => <span>{failure.messageChain.split("<-").map(x => Text("⇨ " + x) ++ {<br/>})}</span>
          } }).apply(errorPopup)
        }
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

        <li class="dropdown">
          <a href="#" class="dropdown-toggle"  data-toggle="dropdown">
            <span>Status</span> <span class="badge"> {statusIcon}</span><span class="caret" style="margin-left:5px"></span></a>
          <ul class="dropdown-menu" role="menu">
            {lastStatus}
            <li>{currentStatus}</li>
          </ul>
        </li>
  }

  private[this] def errorPopup = {
    <div id="errorDetailsDialog" class="nodisplay">
      <div class="simplemodal-title">
        <h1>Error</h1>
        <hr/>
      </div>
      <div class="simplemodal-content">
       <br />
        <div>
          <img src="/images/icfail.png" alt="Error" height="24" width="24" class="erroricon" />
          <h2>Policy update process was stopped due to an error:</h2>
        </div>
        <hr class="spacer" />
        <br />
        <p id="errorDetailsMessage">[Here come the error message]</p>
        <hr class="spacer" />
      </div>
      <div class="simplemodal-bottom">
        <hr/>
        <div class="popupButton">
          <span>
            <button class="simplemodal-close" onClick="return false;">
              Close
            </button>
          </span>
        </div>
      </div>
    </div>
  }

}
