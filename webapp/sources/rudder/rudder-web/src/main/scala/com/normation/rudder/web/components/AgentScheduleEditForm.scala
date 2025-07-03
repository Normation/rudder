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

import com.normation.rudder.reports.AgentRunInterval
import com.normation.rudder.web.ChooseTemplate
import com.normation.rudder.web.snippet.WithNonce
import io.scalaland.chimney.*
import io.scalaland.chimney.syntax.*
import net.liftweb.common.*
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.S
import net.liftweb.http.SHtml
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import net.liftweb.util.Helpers.*
import scala.xml.NodeSeq
import zio.json.*

/**
 * Component to display and configure the Agent Schedule
 */
class AgentScheduleEditForm(
    getConfigureCallback:     () => Box[AgentRunInterval],
    saveConfigureCallback:    AgentRunInterval => Box[Unit],
    startNewPolicyGeneration: () => Unit,
    getGlobalConfiguration:   () => Option[Box[AgentRunInterval]] = () => None
) extends DispatchSnippet with Loggable {

  // Html template
  def agentScheduleTemplate: NodeSeq = ChooseTemplate(
    List("templates-hidden", "components", "ComponentAgentSchedule"),
    "schedule-agentschedule"
  )

  def dispatch: PartialFunction[String, NodeSeq => NodeSeq] = { case "cfagentSchedule" => (xml) => cfagentScheduleConfiguration }

  def submit(jsonSchedule: String): JsRaw = {
    parseJsonSchedule(jsonSchedule) match {
      case eb: EmptyBox =>
        val e = eb ?~! s"Error when trying to parse user data: '${jsonSchedule}'"
        logger.error(e.messageChain)
        JsRaw(s"""createErrorNotification("${e.messageChain}")""")
      case Full(agentInterval) =>
        saveConfigureCallback(agentInterval) match {
          case eb: EmptyBox =>
            val e = eb ?~! s"Error when trying to store in base new agent schedule: '${jsonSchedule}'"
            logger.error(e.messageChain)
            JsRaw(s"""createErrorNotification("${e.messageChain}")""")
          case Full(success) =>
            // start a promise generation, Since we check if there is change to save, if we got there it mean that we need to redeploy
            startNewPolicyGeneration()
            JsRaw(s"""createSuccessNotification("Agent Schedule saved.")""")
        }
    }
  }

  /**
   * Parse a json input into a cf-agent Scedule
   */
  def parseJsonSchedule(s: String): Box[AgentRunInterval] = {
    import net.liftweb.json.*
    val json = parse(s)

    (for {
      case JObject(child) <- json
      case JField("overrides", ov) <- child
      case JField("interval", JInt(i)) <- child
      case JField("startHour", JInt(h)) <- child
      case JField("startMinute", JInt(m)) <- child
      case JField("splayHour", JInt(sh)) <- child
      case JField("splayMinute", JInt(sm)) <- child
    } yield {
      val splayTime     = (sh.toInt * 60) + sm.toInt
      val overrideValue = ov match {
        case JBool(ov) => Some(ov)
        case _         => None
      }

      AgentRunInterval(
        overrideValue,
        i.toInt,
        m.toInt,
        h.toInt,
        splayTime
      )
    }) match {
      case head :: _ =>
        if (head.interval <= head.splaytime) {
          Failure("Cannot save an agent schedule with a splaytime higher than or equal to agent run interval")
        } else {
          Full(head)
        }
      case Nil       =>
        Failure(s"Could not parse ${s} as a valid cf-agent schedule")
    }
  }

  implicit class ToElmInitAgentRunInterval(x: AgentRunInterval) {
    def jsonWithSplay: String = {
      x.transformInto[ElmInitAgentRunInterval].toJson
    }
  }

  def cfagentScheduleConfiguration: NodeSeq = {

    val transform = (for {
      schedule  <- getConfigureCallback().map(_.jsonWithSplay)
      globalRun <- getGlobalConfiguration() match {
                     case None    => Full("null")
                     case Some(g) => g.map(_.jsonWithSplay)
                   }
    } yield {
      val callback = AnonFunc("schedule", SHtml.ajaxCall(JsVar("schedule"), submit))
      s"""
      var main = document.getElementById("agentschedule-app")
         |var initValues = {
         |    contextPath    : "${S.contextPath}"
         |  , hasWriteRights : hasWriteRights
         |  , schedule       : ${schedule}
         |  , globalRun      : ${globalRun}
         |};
         |var app = Elm.Agentschedule.init({node: main, flags: initValues});
         |var saveAction = ${callback.toJsCmd};
         |app.ports.saveSchedule.subscribe(function(schedule) {
         |  saveAction(JSON.stringify(schedule));
         |});
         |""".stripMargin
    }) match {
      case eb: EmptyBox =>
        val e = eb ?~! "Error when retrieving agent schedule from the database"
        logger.error(e.messageChain)
        e.rootExceptionCause.foreach(ex => logger.error(s"Root exception was: ${ex.getMessage}"))

        ("#cfagentSchedule" #> "Error when retrieving agent schedule from the database. Please, contact an admin or try again later")
      case Full(initScheduleParam) =>
        ("#cfagentSchedule *+" #> WithNonce.scriptWithNonce(Script(OnLoad(JsRaw(initScheduleParam)) & Noop)))
    }
    transform(agentScheduleTemplate);
  }
}

// this one is only needed for the elm init in cfagentScheduleConfiguration above
@jsonExplicitNull
final case class ElmInitAgentRunInterval(
    overrides:   Option[Boolean], // true, false, null because used in Elm flag with Elm automatic translation to Maybe
    interval:    Int,
    startHour:   Int,
    startMinute: Int,
    splayHour:   Int,
    splayMinute: Int
)
object ElmInitAgentRunInterval {
  implicit val encoderJsonAgentRunInterval: JsonEncoder[ElmInitAgentRunInterval]                   = DeriveJsonEncoder.gen
  implicit val transformAgentRunInterval:   Transformer[AgentRunInterval, ElmInitAgentRunInterval] = {
    Transformer
      .define[AgentRunInterval, ElmInitAgentRunInterval]
      .withFieldComputed(_.splayHour, _.splaytime / 60)
      .withFieldComputed(_.splayMinute, _.splaytime % 60)
      .buildTransformer
  }
}
