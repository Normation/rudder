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

import com.normation.rudder.reports.ComplianceMode
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.web.ChooseTemplate
import com.normation.rudder.web.snippet.WithNonce
import net.liftweb.common.*
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.S
import net.liftweb.http.SHtml
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import net.liftweb.util.Helpers.*
import org.apache.commons.text.StringEscapeUtils
import scala.xml.NodeSeq
import zio.json.*

/*
 * Configure what is call "reporting mode" in Rudder settings UI.
 * There is a mismatch between what we call "ComplianceMode" in the back-end,
 * and what the user call "reporting mode". The backend should be renamed to
 * match the user wording so that we converge toward a ubiquitous language.
 * In the meantime, the pivot between `reporting mode` and `compliance mode` is done here.
 */
class ReportingModeEditForm[T <: ComplianceMode](
    getConfigureCallback:     Box[T],
    saveConfigureCallback:    Function1[T, Box[Unit]],
    startNewPolicyGeneration: () => Unit,
    getGlobalConfiguration:   Box[GlobalComplianceMode]
)(implicit codec: JsonCodec[T])
    extends DispatchSnippet with Loggable {

  // Html template
  def complianceModeTemplate: NodeSeq = ChooseTemplate(
    List("templates-hidden", "components", "ComponentComplianceMode"),
    "property-compliancemode"
  )

  def dispatch: PartialFunction[String, NodeSeq => NodeSeq] = { case "complianceMode" => (xml) => complianceModeConfiguration }

  def submit(jsonMode: String): JsRaw = {
    jsonMode.fromJson[T] match {
      case Left(err)             =>
        val e = s"Error when trying to parse user data: '${StringEscapeUtils.escapeJson(jsonMode)}': ${err}"
        logger.error(e)
        JsRaw(s"""createErrorNotification("${e}")""")
      case Right(complianceMode) =>
        saveConfigureCallback(complianceMode) match {
          case eb: EmptyBox =>
            val e = eb ?~! s"Error when trying to store in base new compliance mode: '${StringEscapeUtils.escapeJson(jsonMode)}'"
            logger.error(e.messageChain)
            JsRaw(s"""createErrorNotification("${e.messageChain}")""")

          case Full(success) =>
            // start a promise generation, Since we check if there is change to save, if we got there it mean that we need to redeploy
            startNewPolicyGeneration()
            JsRaw("""createSuccessNotification("Compliance mode saved")""")
        }
    }
  }

  def complianceModeConfiguration: NodeSeq = {

    val transform = (for {
      reportingMode <- getConfigureCallback
      globalMode    <- getGlobalConfiguration
    } yield {
      val callback = AnonFunc("complianceMode", SHtml.ajaxCall(JsVar("complianceMode"), submit))
      s"""
      var main = document.getElementById("reportingmode-app")
         |var initValues = {
         |    contextPath    : "${S.contextPath}"
         |  , hasWriteRights : hasWriteRights
         |  , reportingMode : ${reportingMode.toJson}
         |  , globalMode     : ${globalMode.toJson}
         |};
         |var app = Elm.ReportingMode.init({node: main, flags: initValues});
         |var saveAction = ${callback.toJsCmd};
         |app.ports.saveMode.subscribe(function(mode) {
         |  saveAction(JSON.stringify(mode));
         |});
         |app.ports.errorNotification.subscribe(function(msg) {
         |  createErrorNotification(msg);
         |});
         |""".stripMargin
    }) match {
      case eb: EmptyBox =>
        val e = eb ?~! "Error when retrieving compliance mode from the database"
        logger.error(e.messageChain)
        e.rootExceptionCause.foreach(ex => logger.error(s"Root exception was: ${ex.getMessage}"))

        ("#complianceMode" #> "Error when retrieving compliance mode from the database. Please, contact an admin or try again later")
      case Full(initScheduleParam) =>
        ("#complianceMode *+" #> WithNonce.scriptWithNonce(Script(OnLoad(JsRaw(initScheduleParam)) & Noop)))
    }

    transform(complianceModeTemplate);
  }
}
