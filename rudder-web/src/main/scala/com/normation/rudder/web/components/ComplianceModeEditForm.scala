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
import com.normation.eventlog.EventActor
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.reports.ComplianceModeName
import net.liftweb.json.JsonAST._
import net.liftweb.json.Printer
import com.normation.rudder.reports.ComplianceMode
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.reports.NodeComplianceMode
import scala.reflect.runtime.universe._
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.reports.NodeComplianceMode

/**
 * Component to display and configure the compliance Mode (and it's heartbeat)
 */

class ComplianceModeEditForm [T <: ComplianceMode] (
    getConfigureCallback : Box[T]
  , saveConfigureCallback: Function1[T,Box[Unit]]
  , startNewPolicyGeneration: () => Unit
  , getGlobalConfiguration : Box[GlobalComplianceMode]
) (implicit tt : TypeTag[T]) extends DispatchSnippet with Loggable  {

  // Html template
  def templatePath = List("templates-hidden", "components", "ComponentComplianceMode")
  def template() =  Templates(templatePath) match {
     case _ : EmptyBox =>
       sys.error(s"Template for Compliance mode configuration not found. I was looking for ${templatePath.mkString("/")}.html")
     case Full(n) => n
  }
  def complianceModeTemplate = chooseTemplate("property", "compliancemode", template)

  def dispatch = {
    case "complianceMode" => (xml) => complianceModeConfiguration
  }

  val isNodePage : Boolean = {
    typeOf[T] match {
      case t if t =:= typeOf[GlobalComplianceMode] => false
      case t if t =:= typeOf[NodeComplianceMode] => true
    }
  }

  def submit(jsonMode:String) = {
    parseJsonMode(jsonMode) match {
      case eb:EmptyBox =>
        val e = eb ?~! s"Error when trying to parse user data: '${jsonMode}'"
        logger.error(e.messageChain)
        S.error("complianceModeMessage", e.messageChain)
      case Full(complianceMode: T) =>
        saveConfigureCallback(complianceMode)  match {
          case eb:EmptyBox =>
            val e = eb ?~! s"Error when trying to store in base new compliance mode: '${jsonMode}'"
            logger.error(e.messageChain)
            S.error("complianceModeMessage", e.messageChain)

          case Full(success) =>
            // start a promise generation, Since we check if there is change to save, if we got there it mean that we need to redeploy
            startNewPolicyGeneration()
            S.notice("complianceModeMessage", "Compliance mode saved")
        }
    }
  }

  /**
   * Parse a json input into a valid complianceMode
   */
  def parseJsonMode(s: String) : Box[ComplianceMode] = {
    import net.liftweb.json._
    val json = parse(s)

    def parseOverride(jsonMode : JObject) : Box[Boolean] = {
      jsonMode.values.get("overrides") match {
        case Some(JBool(bool)) => Full(bool)
        case Some(allow_override : Boolean) => Full(allow_override)
        case Some(json : JValue) => Failure(s"'${render(json)}' is not a valid value for compliance mode 'override' attribute")
        // Should not happen, values in lift json are only JValues, but since we type any we have to add it unless we will have a warning
        case Some(any) => Failure(s"'${any}' is not a valid value for compliance mode 'override' attribute")
        case None => Failure("compliance mode 'overrides' parameter must not be empty ")
      }
    }

    def parseMode(jsonMode: JObject) : Box[ComplianceModeName]= {
      jsonMode.values.get("name") match {
        case Some(JString(mode)) => ComplianceModeName.parse(mode)
        case Some(mode : String) => ComplianceModeName.parse(mode)
        case Some(json : JValue) => Failure(s"'${(json)}' is not a valid value for compliance mode 'name' attribute")
        // Should not happen, values in lift json are only JValues, but since we type any we have to add it unless we will have a warning
        case Some(any) => Failure(s"'${any}' is not a valid value for compliance mode 'name' attribute")
        case None => Failure("compliance mode 'name' parameter must not be empty ")
      }
    }

    def parseHeartbeat(jsonMode: JObject) : Box[Int]= {
      jsonMode.values.get("heartbeatPeriod") match {
        case Some(JInt(heartbeat)) => Full(heartbeat.toInt)
        case Some(heartbeat : BigInt) => Full(heartbeat.toInt)
        case Some(json : JValue) => Failure(s"'${(json)}' is not a valid value for compliance mode 'heartbeatPeriod' attribute")
        // Should not happen, values in lift json are only JValues, but since we type any we have to add it unless we will have a warning
        case Some(any) => Failure(s"'${any}' is not a valid value for compliance mode 'heartbeatPeriod' attribute")
        case None => Failure("compliance mode 'heartbeatPeriod' parameter must not be empty ")
      }
    }
    json match {
      case obj : JObject =>
        for {
          mode <- parseMode(obj)
          heartbeat <- parseHeartbeat(obj)
          complianceMode <-
            typeOf[T] match {
              case t if t =:= typeOf[GlobalComplianceMode] =>
                Full(GlobalComplianceMode(mode,heartbeat))
              case t if t =:= typeOf[NodeComplianceMode] =>
                for {
                  overrides <- parseOverride(obj)
                } yield {
                  NodeComplianceMode(mode,heartbeat,overrides)
                }
          }
        } yield {
          complianceMode
        }
      case _ =>
        Failure(s"Could not parse ${s} as a valid compliance mode")
    }
  }

  def toJs (mode : ComplianceMode )= {
    def json : JValue = {
      import net.liftweb.json.JsonDSL._
      val baseMode =
        ( "name"            -> mode.name ) ~
        ( "heartbeatPeriod" -> mode.heartbeatPeriod)

      mode match {
        case node : NodeComplianceMode =>
        baseMode ~ ( "overrides" -> node.overrideGlobal)
        case _ => baseMode
      }
    }

    Printer.compact(render(json))
  }

  def complianceModeConfiguration = {

    val transform = (for {
      complianceMode <- getConfigureCallback
      globalMode <- getGlobalConfiguration
    } yield {
      val callback = AnonFunc("complianceMode",SHtml.ajaxCall(JsVar("complianceMode"), submit))

      val allModes = ComplianceModeName.allModes.mkString("['", "' , '", "']")
      s"""
       angular.bootstrap("#complianceMode", ['complianceMode']);
       var scope = angular.element($$("#complianceModeController")).scope();
          scope.$$apply(function(){
            scope.init(${toJs(complianceMode)}, ${toJs(globalMode)}, ${isNodePage} ,${callback.toJsCmd}, "${S.contextPath}", ${allModes});
          } );
      """
    }) match {
      case eb:EmptyBox =>
        val e = eb ?~! "Error when retrieving compliance mode from the database"
        logger.error(e.messageChain)
        e.rootExceptionCause.foreach { ex => logger.error(s"Root exception was: ${ex}") }

        ( "#complianceMode" #> "Error when retrieving compliance mode from the database. Please, contact an admin or try again later"  )
      case Full(initScheduleParam) =>
        ( "#complianceMode *+" #> Script(OnLoad(JsRaw(initScheduleParam)) & Noop) )
    }

    transform(complianceModeTemplate);
   }
}
