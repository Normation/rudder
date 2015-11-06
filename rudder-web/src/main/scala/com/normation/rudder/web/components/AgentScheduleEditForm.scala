/*
*************************************************************************************
* Copyright 2014 Normation SAS
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
  , startNewPolicyGeneration: () => Unit
  , getGlobalConfiguration : () => Option[Box[AgentRunInterval]] = () => None
) extends DispatchSnippet with Loggable  {

  // Html template
  def templatePath = List("templates-hidden", "components", "ComponentAgentSchedule")
  def template() =  Templates(templatePath) match {
     case Empty | Failure(_,_,_) =>
       sys.error("Template for Agent Schedule configuration not found. I was looking for %s.html"
           .format(templatePath.mkString("/")))
     case Full(n) => n
  }
  def agentScheduleTemplate = chooseTemplate("schedule", "agentSchedule", template)

  def dispatch = {
    case "cfagentSchedule" => (xml) => cfagentScheduleConfiguration
  }

  def submit(jsonSchedule:String) = {
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

  /**
   * Parse a json input into a cf-agent Scedule
   */
  def parseJsonSchedule(s: String) : Box[AgentRunInterval] = {
    import net.liftweb.json._
    val json = parse(s)

    ( for {
        JField("overrides"  , ov) <- json
        JField("interval"   , JInt(i)) <- json
        JField("startHour"  , JInt(h)) <- json
        JField("startMinute", JInt(m)) <- json
        JField("splayHour"  , JInt(sh)) <- json
        JField("splayMinute", JInt(sm)) <- json
    } yield {
      val splayTime = (sh.toInt * 60) + sm.toInt
      val overrideValue = ov match {
        case JBool(ov) => Some(ov)
        case _ => None
      }

      AgentRunInterval(
          overrideValue
        , i.toInt
        , m.toInt
        , h.toInt
        , splayTime
      )
    } ) match {
      case head :: _ =>
        if (head.interval <= head.splaytime) {
          Failure("Cannot save an agent schedule with a splaytime higher than or equal to agent run interval")
        } else {
          Full(head)
        }
      case Nil =>
        Failure(s"Could not parse ${s} as a valid cf-agent schedule")
    }
  }

  def cfagentScheduleConfiguration = {

    val transform = (for {
      schedule  <- getConfigureCallback().map(_.json)
      globalRun <- getGlobalConfiguration() match {
                     case None => Full("undefined")
                     case Some(g) => g.map(_.json)
                   }
    } yield {
      val callback = AnonFunc("schedule",SHtml.ajaxCall(JsVar("schedule"), submit))
      s"""
       angular.bootstrap("#cfagentSchedule", ['cfagentSchedule']);
       var scope = angular.element($$("#agentScheduleController")).scope();
          scope.$$apply(function(){
            scope.init(${schedule}, ${globalRun}, ${callback.toJsCmd}, "${S.contextPath}");
          } );
      """
    }) match {
      case eb:EmptyBox =>
        val e = eb ?~! "Error when retrieving agent schedule from the database"
        logger.error(e.messageChain)
        e.rootExceptionCause.foreach { ex => logger.error(s"Root exception was: ${ex}") }

        ( "#cfagentSchedule" #> "Error when retrieving agent schedule from the database. Please, contact an admin or try again later"  )
      case Full(initScheduleParam) =>
        ( "#cfagentSchedule *+" #> Script(OnLoad(JsRaw(initScheduleParam)) & Noop) )
    }

    transform(agentScheduleTemplate);
   }
}
