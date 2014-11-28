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
import com.normation.rudder.reports.ComplianceMode
import com.normation.rudder.reports.FullCompliance

/**
 * Component to display and configure the compliance Mode (and it's heartbeat)
 */
class ComplianceModeEditForm (
    getConfigureCallback : () => Box[(String,Int,Boolean)]
  , saveConfigureCallback: (String,Int,Boolean) => Box[Unit]
  , startNewPolicyGeneration: () => Unit
  , getGlobalConfiguration : () => Option[Box[Int]] = () => None
) extends DispatchSnippet with Loggable  {

  // Html template
  def templatePath = List("templates-hidden", "components", "ComponentComplianceMode")
  def template() =  Templates(templatePath) match {
     case Empty | Failure(_,_,_) =>
       error(s"Template for Compliance mode configuration not found. I was looking for ${templatePath.mkString("/")}.html")
     case Full(n) => n
  }
  def agentScheduleTemplate = chooseTemplate("property", "complianceMode", template)

  def dispatch = {
    case "complianceMode" => (xml) => complianceModeConfiguration
  }

  def submit(jsonSchedule:String) = {
    parseJsonSchedule(jsonSchedule) match {
      case eb:EmptyBox =>
        val e = eb ?~! s"Error when trying to parse user data: '${jsonSchedule}'"
        logger.error(e.messageChain)
        S.error("complianceModeMessage", e.messageChain)
      case Full((name,frequency,overrides)) =>
        saveConfigureCallback(name,frequency,overrides) match {
          case eb:EmptyBox =>
            val e = eb ?~! s"Error when trying to store in base new agent schedule: '${jsonSchedule}'"
            logger.error(e.messageChain)
            S.error("complianceModeMessage", e.messageChain)

          case Full(success) =>
            // start a promise generation, Since we check if there is change to save, if we got there it mean that we need to redeploy
            startNewPolicyGeneration()
            S.notice("complianceModeMessage", "Agent schedule saved")
        }
    }
  }

  /**
   * Parse a json input into a cf-agent Scedule
   */
  def parseJsonSchedule(s: String) : Box[(String,Int,Boolean)] = {
    import net.liftweb.json._
    val json = parse(s)

    ( for {
        JField("name"  , JString(name)) <- json
        JField("heartbeatPeriod"  , JInt(frequency)) <- json
        JField("overrides"  , JBool(overrides)) <- json
    } yield {
      (name,frequency.toInt,overrides)
    } ) match {
      case head :: _ =>
        Full(head)
      case Nil =>
        Failure(s"Could not parse ${s} as a valid cf-agent schedule")
    }
  }

  def complianceModeConfiguration = {


    val transform = (for {
      (complianceMode, frequency,overrides) <- getConfigureCallback()
      globalRun <- getGlobalConfiguration() match {
                     case None => Full("undefined")
                     case Some(g) => g.map(_.toString())
                   }
    } yield {
      val callback = AnonFunc("complianceMode",SHtml.ajaxCall(JsVar("complianceMode"), submit))
      s"""
       angular.bootstrap("#complianceMode", ['complianceMode']);
       var scope = angular.element($$("#complianceModeController")).scope();
          scope.$$apply(function(){
            scope.init("${complianceMode}", ${frequency}, ${overrides}, ${globalRun} ,${callback.toJsCmd}, "${S.contextPath}");
          } );
      """
    }) match {
      case eb:EmptyBox =>
        val e = eb ?~! "Error when retrieving agent schedule from the database"
        logger.error(e.messageChain)
        e.rootExceptionCause.foreach { ex => logger.error(s"Root exception was: ${ex}") }

        ( "#complianceMode" #> "Error when retrieving agent schedule from the database. Please, contact an admin or try again later"  )
      case Full(initScheduleParam) =>
        ( "#complianceMode *+" #> Script(OnLoad(JsRaw(initScheduleParam)) & Noop) )
    }

    transform(agentScheduleTemplate);
   }
}
