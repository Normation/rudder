/*
*************************************************************************************
* Copyright 2013 Normation SAS
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

package com.normation.rudder.web.components


import bootstrap.liftweb.RudderConfig
import net.liftweb.http.DispatchSnippet
import net.liftweb.common._
import com.normation.rudder.domain.policies.Directive
import net.liftweb.http.{SHtml,S}
import scala.xml._
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.js._
import JsCmds._
import com.normation.rudder.web.components.popup.CreateOrCloneRulePopup
import JE._
import net.liftweb.util.Helpers
import net.liftweb.util.Helpers._
import net.liftweb.http.Templates
import com.normation.rudder.reports.AgentRunInterval
import net.liftweb.util.PassThru
import net.liftweb.util.ClearNodes


/**
 * Component to display and configure the Agent Schedule
 */
class AgentScheduleEditForm(
    getConfigureCallback : () => Box[AgentRunInterval]
  , saveConfigureCallback: AgentRunInterval => Box[Unit]
  , displayOverridable   : Boolean
  , startNewPolicyGeneration: () => Unit
) extends DispatchSnippet with Loggable  {


     // Load the template from the popup
  def templatePath = List("templates-hidden", "components", "ComponentAgentSchedule")
  def template() =  Templates(templatePath) match {
     case Empty | Failure(_,_,_) =>
       error("Template for Agent Schedule configuration not found. I was looking for %s.html"
           .format(templatePath.mkString("/")))
     case Full(n) => n
  }
  def agentScheduleTemplate = chooseTemplate("schedule", "agentSchedule", template)

  def dispatch = {
    case "cfagentSchedule" => cfagentScheduleConfiguration
  }

  private[this] var jsonSchedule = "{}"

  def submit() = {
    parseJsonSchedule(jsonSchedule) match {
        case eb:EmptyBox =>
          val e = eb ?~! s"Error when trying to parse user data: '${jsonSchedule}'"
          logger.error(e.messageChain)
          S.error("cfagentScheduleMessage", e.messageChain)

        case Full(agentInterval) =>
          saveConfigureCallback(agentInterval) match {
            case eb:EmptyBox =>
              val e = eb ?~! s"Error when trying to store in base new agent schedule: '${jsonSchedule}'"
              logger.error(e.messageChain)
              S.error("cfagentScheduleMessage", e.messageChain)

            case Full(success) =>

              // start a promise generation, Since we check if there is change to save, if we got there it mean that we need to redeploy
              startNewPolicyGeneration()
              S.notice("cfagentScheduleMessage", "Agent schedule saved")
          }
    }
  }

  //return a box of (interval, start hour, start min, splay)
  def parseJsonSchedule(s: String) : Box[AgentRunInterval] = {
      import net.liftweb.json._


      val json = parse(s)

      val x = for {
        JField("overrides"  , JBool(ov)) <- json
        JField("interval"   , JInt(i)) <- json
        JField("starthour"  , JInt(h)) <- json
        JField("startminute", JInt(m)) <- json
        JField("splayHour"  , JInt(sh)) <- json
        JField("splayMinute", JInt(sm)) <- json
      } yield {
        val splayTime = (sh.toInt * 60) + sm.toInt
        AgentRunInterval(
            ov
          , i.toInt
          , m.toInt
          , h.toInt
          , splayTime
        )
      }
    Full(x.head)
  }


  def cfagentScheduleConfiguration = { xml : NodeSeq =>
    val transform = (for {
      schedule  <- getConfigureCallback()
      starthour = schedule.startHour
      startmin  = schedule.startMinute
      splaytime = schedule.splaytime
      interval = schedule.interval

    } yield {
      val overrides = if (displayOverridable) {schedule.overrides} else { true }

      val splayHour = splaytime / 60
      val splayMinute = splaytime % 60
      ("ng-init",s"""agentRun={ 'overrides'   : ${overrides}
                              , 'interval'    : ${interval}
                              , 'starthour'   : ${starthour}
                              , 'startminute' : ${startmin}
                              , 'splayHour'   : ${splayHour}
                              , 'splayMinute' : ${splayMinute}
                              }""")
    }) match {
      case eb:EmptyBox =>
        val e = eb ?~! "Error when retrieving agent schedule from the database"
        logger.error(e.messageChain)
        e.rootExceptionCause.foreach { ex =>
          logger.error("Root exception was:", ex)
        }

        (
          "#cfagentScheduleForm" #> "Error when retrieving agent schedule from the database. Please, contact an admin or try again later"
        )
      case Full(initScheduleParam) =>
        println(initScheduleParam._2)
        (
            "#cfagentScheduleHidden" #> SHtml.hidden((x:String) => { jsonSchedule = x ; x}, "{{agentRun}}", initScheduleParam)
          & "#cfagentScheduleSubmit" #> SHtml.ajaxSubmit("Save changes", submit _)
          & "#enableSchedule" #> (if (displayOverridable) PassThru else ClearNodes )

        )
    }

    transform.apply(agentScheduleTemplate ++ Script(OnLoad(JsRaw("""angular.bootstrap(document, ['cfagentSchedule']);"""))))

   }


}