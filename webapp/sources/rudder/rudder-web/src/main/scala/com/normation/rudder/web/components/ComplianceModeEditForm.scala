/*
*************************************************************************************
* Copyright 2014 Normation SAS
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

package com.normation.rudder.web.components

import net.liftweb.http.DispatchSnippet
import net.liftweb.common._
import net.liftweb.http.{S, SHtml}
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.js._
import JsCmds._
import JE._
import net.liftweb.util.Helpers._
import com.normation.rudder.reports.ComplianceMode
import com.normation.rudder.reports.ComplianceModeName
import net.liftweb.json.JsonAST._
import com.normation.rudder.reports.ComplianceMode
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.reports.NodeComplianceMode
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.reports.NodeComplianceMode
import com.normation.rudder.web.ChooseTemplate

import scala.xml.NodeSeq

/**
 * Component to display and configure the compliance Mode (and it's heartbeat)
 */
sealed trait ParseComplianceMode[T <: ComplianceMode] {
  def isNodePage : Boolean
  def parseOverride(jsonMode : JObject) : Box[Boolean] = {
    jsonMode.values.get("overrides") match {
      case Some(JBool(bool)) => Full(bool)
      case Some(allow_override : Boolean) => Full(allow_override)
      case Some(json : JValue) => Failure(s"'${compactRender(json)}' is not a valid value for compliance mode 'override' attribute")
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

  def parse(s: String): Box[JObject] = {
    net.liftweb.json.parse(s) match {
      case obj: JObject => Full(obj)
      case _            => Failure(s"Could not parse ${s} as a valid compliance mode")
    }
  }

  def parseJsonMode(s: String) : Box[T]
}

object ParseComplianceMode {

  implicit final object Global extends ParseComplianceMode[GlobalComplianceMode] {
    override def isNodePage = false
    override def parseJsonMode(s: String):  Box[GlobalComplianceMode] = {
      for {
        obj       <- parse(s)
        mode      <- parseMode(obj)
        heartbeat <- parseHeartbeat(obj)
      } yield {
        GlobalComplianceMode(mode,heartbeat)
      }
    }
  }

  implicit final object Node extends ParseComplianceMode[NodeComplianceMode] {
    override def isNodePage = true
    override def parseJsonMode(s: String):  Box[NodeComplianceMode] = {
      for {
        obj       <- parse(s)
        mode      <- parseMode(obj)
        heartbeat <- parseHeartbeat(obj)
        overrides <- parseOverride(obj)
      } yield {
        NodeComplianceMode(mode,heartbeat,overrides)
      }
    }
  }
}

class ComplianceModeEditForm [T <: ComplianceMode] (
    getConfigureCallback : Box[T]
  , saveConfigureCallback: Function1[T,Box[Unit]]
  , startNewPolicyGeneration: () => Unit
  , getGlobalConfiguration : Box[GlobalComplianceMode]
) (implicit p: ParseComplianceMode[T]) extends DispatchSnippet with Loggable  {

  // Html template
  def complianceModeTemplate: NodeSeq = ChooseTemplate(
      List("templates-hidden", "components", "ComponentComplianceMode")
    , "property-compliancemode"
  )

  def dispatch = {
    case "complianceMode" => (xml) => complianceModeConfiguration
  }

  val isNodePage : Boolean = p.isNodePage
  /**
   * Parse a json input into a valid complianceMode
   */
  def parseJsonMode(s: String) : Box[T] = p.parseJsonMode(s)

  def submit(jsonMode:String) = {
    parseJsonMode(jsonMode) match {
      case eb:EmptyBox =>
        val e = eb ?~! s"Error when trying to parse user data: '${jsonMode}'"
        logger.error(e.messageChain)
        JsRaw(s"""createErrorNotification("${ e.messageChain}")""")
      case Full(complianceMode) =>
        saveConfigureCallback(complianceMode)  match {
          case eb:EmptyBox =>
            val e = eb ?~! s"Error when trying to store in base new compliance mode: '${jsonMode}'"
            logger.error(e.messageChain)
            JsRaw(s"""createErrorNotification("${ e.messageChain}")""")

          case Full(success) =>
            // start a promise generation, Since we check if there is change to save, if we got there it mean that we need to redeploy
            startNewPolicyGeneration()
            JsRaw("""createSuccessNotification("Compliance mode saved")""")
        }
      //necessary to avoid the non-exhaustive warning due to "type pattern T is unchecked since eliminated by erasure" pb above
      case x => JsRaw(s"""createErrorNotification("Compliance mode is not of the awaited type (dev error): please report that error")""")
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

    compactRender(json)
  }

  def complianceModeConfiguration: NodeSeq = {

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
       });
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
