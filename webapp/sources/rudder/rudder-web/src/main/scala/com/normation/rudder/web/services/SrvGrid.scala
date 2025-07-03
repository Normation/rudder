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

package com.normation.rudder.web.services

import com.normation.appconfig.ReadConfigService
import com.normation.box.*
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.reports.execution.RoReportsExecutionRepository
import com.normation.rudder.score.ScoreService
import com.normation.rudder.web.snippet.WithNonce
import com.normation.zio.*
import net.liftweb.common.*
import net.liftweb.http.*
import net.liftweb.http.js.*
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import org.apache.commons.text.StringEscapeUtils
import org.slf4j
import org.slf4j.LoggerFactory
import scala.xml.*

/**
 * Very much like the NodeGrid, but with the new WB and without ldap information
 */
object SrvGrid {
  val logger: slf4j.Logger = LoggerFactory.getLogger(classOf[SrvGrid])
}

/**
 * Present a grid of server in a jQuery Datatable
 * widget.
 *
 * To use it:
 * - add the need js/css dependencies by adding the result
 *   of head() in the calling template head
 * - call the display(servers) method
 */
class SrvGrid(
    roAgentRunsRepository: RoReportsExecutionRepository,
    configService:         ReadConfigService,
    scoreService:          ScoreService
) extends Loggable {

  def jsVarNameForId(tableId: String): String = "oTable" + tableId

  /**
   * Display and init the display for the list of server
   * @param tableId : the id of the table
   */
  def displayAndInit(
      nodes:        Option[Seq[NodeInfo]],
      tableId:      String,
      additionalJs: Option[JsCmd] = None
  ): NodeSeq = {
    val script = {
      configService.rudder_global_policy_mode().toBox match {
        case Full(globalPolicyMode) => WithNonce.scriptWithNonce(Script(OnLoad(initJs(tableId, nodes, additionalJs))))
        case eb: EmptyBox =>
          val fail = eb ?~! "Could not find global policy Mode"
          logger.error(fail.messageChain)
          NodeSeq.Empty
      }
    }
    tableXml(tableId) ++ script
  }

  /**
   * Initialize the table by javascript
   * takes the id of the table as param
   * the nodes to compute the datas
   * and the optionnal callback
   */
  def initJs(
      tableId:      String,
      nodes:        Option[Seq[NodeInfo]],
      additionalJs: Option[JsCmd]
  ): JsCmd = {

    val scoreNames = JsArray(scoreService.getAvailableScore().map(_.map(s => JsObj(("id", s._1), ("name", s._2)))).runNow)
    val jsTableId  = StringEscapeUtils.escapeEcmaScript(tableId)
    val nodeIds    = nodes.map(nodes => JsArray(nodes.map(n => Str(n.id.value)).toList).toJsCmd).getOrElse("undefined")
    JsRaw(
      s"""createNodeTable("${jsTableId}", ${nodeIds},function() {reloadTable("${jsTableId}", ${nodeIds}, ${scoreNames.toJsCmd})}, ${scoreNames.toJsCmd});"""
    ) & (additionalJs.getOrElse(Noop)) // JsRaw ok, escaped
  }

  def refreshData(
      refreshNodes: () => Option[Seq[CoreNodeFact]],
      callback:     Option[(String, Boolean) => JsCmd],
      tableId:      String
  ): AnonFunc = {

    val scoreNames = JsArray(scoreService.getAvailableScore().map(_.map(s => JsObj(("id", s._1), ("name", s._2)))).runNow)
    val ajaxCall   = SHtml.ajaxCall(
      JsNull,
      (s) => {
        val nodeIds: String = refreshNodes() match {
          case None        => "undefined"
          case Some(nodes) => JsArray(nodes.map(n => Str(n.id.value)).toList).toJsCmd
        }

        JsRaw(s"""
          reloadTable("${tableId}", ${nodeIds}, ${scoreNames.toJsCmd});
      """) // JsRaw ok, escaped
      }
    )

    AnonFunc("", ajaxCall)
  }

  /**
   * Html template of the table, id is in parameter
   */
  def tableXml(tableId: String): NodeSeq = {
    <table id={tableId} cellspacing="0"/>
  }

}
