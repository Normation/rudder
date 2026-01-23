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

package com.normation.rudder.web.snippet

//lift std import
import bootstrap.liftweb.RudderConfig
import com.normation.box.*
import com.normation.inventory.domain.LinuxType
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.PhysicalMachineType
import com.normation.inventory.domain.VirtualMachineType
import com.normation.inventory.domain.WindowsType
import com.normation.inventory.ldap.core.TimingDebugLoggerPure
import com.normation.ldap.sdk.BuildFilter.*
import com.normation.ldap.sdk.FALSE
import com.normation.rudder.domain.RudderLDAPConstants.*
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.rudder.domain.logger.ApplicationLoggerPure
import com.normation.rudder.domain.logger.ComplianceLogger
import com.normation.rudder.domain.logger.TimingDebugLogger
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.domain.reports.ComplianceLevel
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.facts.nodes.SelectNodeStatus
import com.normation.rudder.score.ScoreValue
import com.normation.rudder.score.ScoreValue.NoScore
import com.normation.rudder.tenants.QueryContext
import com.normation.rudder.users.CurrentUser
import com.normation.zio.*
import net.liftweb.common.*
import net.liftweb.http.*
import net.liftweb.http.js.*
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import scala.xml.*
import zio.json.*

case class ScoreChart(scoreValue: ScoreValue, value: Int, noScoreLegend: Option[String]) {
  import com.normation.rudder.score.ScoreValue.*

  def color: String = {
    scoreValue match {
      case A       => "#13beb7"
      case B       => "#68c96a"
      case C       => "#b3d337"
      case D       => "#fedc04"
      case E       => "#f0940e"
      case F       => "#da291c"
      case NoScore => "#d8dde5"
    }
  }
  def label: String = {
    // We don't want "X" as legend label
    (scoreValue, noScoreLegend) match {
      case (NoScore, Some(l)) => l
      case (score, _)         => score.value
    }
  }

  /**
    * The value used to map the label to the query filter for the score value (e.g. node table uses "X" for NoScore)
    */
  def labelQueryFilter: String = scoreValue.value

  def jsColor: (String, Str) = {
    (label -> Str(color))
  }
}

object HomePageUtils {
  // Format different version naming type into one
  def formatAgentVersion(version: String): String = {
    // All that is before '.release' (rpm releases, like 3.0.6.release)
    // OR DSC agent version, => value before dash + dash + first two numbers separated by a dot (a.b-x.y)
    // OR all until first dash ( debian releases, like 3.0.6-wheezy)
    // We don't want epoch, and we don't want the cfe- prefix either anymore (it's 3.x area), so only group 3 is interesting
    val versionRegexp = """(cfe-)?(\d+:)?((.+)(?=\.release)|([^-]+-\d+\.\d+)|([^-]+))""".r
    versionRegexp
      .findFirstMatchIn(version)
      .map(_.group(3))
      .getOrElse(version)
      .
      // Replace all '~' by '.' to normalize alpha/beta/rc releases and nightlies
      replace("~", ".")
  }
}

class HomePage extends StatefulSnippet {

  private val nodeFactRepo     = RudderConfig.nodeFactRepository
  private val ldap             = RudderConfig.roLDAPConnectionProvider
  private val rudderDit        = RudderConfig.rudderDit
  private val reportingService = RudderConfig.reportingService
  private val roRuleRepo       = RudderConfig.roRuleRepository
  private val scoreService     = RudderConfig.rci.scoreService
  private val directiveRepo    = RudderConfig.roDirectiveRepository

  override val dispatch: DispatchIt = {

    implicit val qc: QueryContext = CurrentUser.queryContext // bug https://issues.rudder.io/issues/26605
    // get one coherent view of nodes for the whole page

    val nodes: Map[NodeId, CoreNodeFact] = nodeFactRepo
      .getAll()
      .map(_.toMap)
      .tapError(err => ApplicationLoggerPure.warn(s"Error when getting nodes: ${err.fullMsg}"))
      .option
      .runNow
      .getOrElse(Map())

    // partial function syntax is a bit strange
    {
      case "pendingNodes"       => pendingNodes
      case "acceptedNodes"      => acceptedNodes(nodes.size)
      case "rules"              => rules
      case "directives"         => directives
      case "groups"             => groups
      case "techniques"         => techniques
      case "getAllCompliance"   => _ => getAllCompliance(nodes)
      case "inventoryInfo"      => _ => inventoryInfo(nodes)
      case "rudderAgentVersion" => _ => rudderAgentVersion(nodes)
    }
  }

  def pendingNodes(html: NodeSeq)(implicit qc: QueryContext): NodeSeq = {
    displayCount(countPendingNodes, "pending nodes")
  }

  def acceptedNodes(nodeCount: Int)(html: NodeSeq): NodeSeq = {
    displayCount(Full(nodeCount), "accepted nodes")
  }

  def rules(html: NodeSeq): NodeSeq = {
    displayCount(countAllRules(), "rules")
  }

  def directives(html: NodeSeq): NodeSeq = {
    displayCount(countAllDirectives(), "directives")
  }

  def groups(html: NodeSeq): NodeSeq = {
    displayCount(countAllGroups(), "groups")
  }

  def techniques(html: NodeSeq): NodeSeq = {
    displayCount(countAllTechniques(), "techniques")
  }

  def getAllCompliance(allNodes: Map[NodeId, CoreNodeFact])(implicit qc: QueryContext): NodeSeq = {
    // this needs to be outside of the zio for-comprehension context to see the right node facts
    val nodes = allNodes.filterNot(_._2.rudderSettings.state == NodeState.Ignored).keys.toSet
    (for {
      n2                <- currentTimeMillis
      userRules         <- roRuleRepo.getIds()
      n3                <- currentTimeMillis
      _                  = TimingDebugLogger.trace(s"Get rules: ${n3 - n2}ms")
      // reports contains the reports for user rules, used in the donut
      reports           <-
        reportingService.findRuleNodeStatusReports(nodes, userRules)
      n4                <- currentTimeMillis
      _                  = TimingDebugLogger.trace(s"Compute Rule Node status reports for all nodes: ${n4 - n3}ms")
      // global compliance is a unique number, used in the top right hand size, based on
      // user rules, and ignoring pending nodes

      // TODO : we could first compute per nodes, and then compute the globalCompliance by excluding the pending nodes
      compliancePerNodes =
        reports.map { case (nodeId, status) => (nodeId, ComplianceLevel.sum(status.reports.map(_._2.compliance))) }
      global             = if (reports.isEmpty) {
                             None
                           } else {
                             val complianceLevel = ComplianceLevel.sum(compliancePerNodes.values)
                             Some(
                               (
                                 complianceLevel,
                                 complianceLevel.withoutPending.computePercent().compliance.round
                               )
                             )
                           }
      n5                <- currentTimeMillis
      _                 <- TimingDebugLoggerPure.trace(s"Compute global compliance in: ${n5 - n4}ms")
      _                 <- TimingDebugLoggerPure.debug(s"Compute compliance: ${n5 - n2}ms")
      unfilteredScores  <- scoreService.getAll()
      existingScore     <- scoreService.getAvailableScore()
    } yield {
      val scores = unfilteredScores.filter(n => nodes.contains(n._1))
      // log global compliance info (useful for metrics on number of components and log data analysis)
      ComplianceLogger.info(
        s"[metrics] global compliance (number of components): ${global.map(g => g._1.total.toString + " " + g._1.toString).getOrElse("undefined")}"
      )

      /*
       * Here, for the compliance by node, we want to distinguish (but NOT ignore, like in globalCompliance) the
       * case where the node is pending.
       *
       * We are using a coarse grain here: if a node as even ONE report not pending, we compute it's compliance.
       * Else, if the node's reports are ALL pending, we use a special pending case.
       *
       * Note: node without reports are also put in "pending".
       */

      val numberOfPendingNodes = compliancePerNodes.values.count(r => r.pending == r.total)

      val complianceDiagram: List[ScoreChart] =
        scores.values.groupBy(_.value).map(v => ScoreChart(v._1, v._2.size, None)).toList.sortBy(_.scoreValue.value).reverse

      val detailsScore = {
        // Filter score if all score are "No score"
        scores.values.flatMap(_.details).toList.groupBy(_.scoreId).filter(_._2.exists(_.value != NoScore)).map { c =>
          (
            c._1,
            c._2
              .groupBy(_.value)
              .map(v => {
                ScoreChart(
                  v._1,
                  v._2.size,
                  existingScore.find(_._1 == c._1).map { case (_, scoreName) => s"No score" }
                )
              })
              .toList
              .sortBy(_.scoreValue.value)
              .reverse
          )
        }
      }

      val numberOfNodes = compliancePerNodes.values.size
      val pendingNodes  = numberOfPendingNodes match {

        case 0 =>
          JsObj(
            "pending" -> JsNull,
            "active"  -> numberOfNodes
          )

        case pending =>
          JsObj(
            "pending" ->
            JsObj(
              "nodes"   -> pending,
              "percent" -> (pending * 100.0 / numberOfNodes).round
            ),
            "active"  -> (numberOfNodes - pending)
          )
      }

      val diagramData = complianceDiagram.foldLeft((Nil: List[JsExp], Nil: List[JsExp], Nil: List[JsExp])) {
        case ((labels, values, colors), diag) => (diag.label :: labels, diag.value :: values, diag.color :: colors)
      }

      val data =
        JsObj("labels" -> JsArray(diagramData._1), "values" -> JsArray(diagramData._2), "colors" -> JsArray(diagramData._3))

      val scoreDetailsData = JsArray(for {
        (scoreId, detailChart) <- detailsScore.toList.sortBy(_._1)
      } yield {

        val diagramDetailData = detailChart.foldLeft((Nil: List[JsExp], Nil: List[JsExp], Nil: List[JsExp], Nil: List[JsExp])) {
          case ((labels, labelQueryFilters, values, colors), diag) =>
            (diag.label :: labels, diag.labelQueryFilter :: labelQueryFilters, diag.value :: values, diag.color :: colors)
        }
        val detailData        = {
          JsObj(
            "labels"            -> JsArray(diagramDetailData._1),
            "labelQueryFilters" -> JsArray(diagramDetailData._2),
            "values"            -> JsArray(diagramDetailData._3),
            "colors"            -> JsArray(diagramDetailData._4)
          )
        }

        val detailDiagramColor = JsObj(complianceDiagram.map(_.jsColor)*)
        val name: String = existingScore.find(_._1 == scoreId).map(_._2).getOrElse(scoreId)
        JsObj(
          "scoreId" -> scoreId.replace(":", "_"),
          "data"    -> detailData,
          "colors"  -> detailDiagramColor,
          "count"   -> detailChart.map(_.value).sum,
          "name"    -> name
        )
      })

      // Data used for compliance bar, compliance without pending
      val (complianceBar, globalCompliance) = global match {
        case Some((bar, value)) =>
          import com.normation.rudder.domain.reports.ComplianceLevelSerialisation.*
          (bar.copy(pending = 0).toJsArray, value)
        case None               =>
          (ast.Json.Arr(), -1L)
      }

      val n4 = System.currentTimeMillis
      TimingDebugLogger.debug(s"Compute compliance for HomePage: ${n4 - n2}ms")

      Script(OnLoad(JsRaw(s"""
        homePage(
            ${complianceBar.toJson}
          , ${globalCompliance}
          , ${data.toJsCmd}
          , ${pendingNodes.toJsCmd}
          , ${scoreDetailsData.toJsCmd}
        )"""))) // JsRaw ok, escaped
    }).either.runNow match {
      case Right(homePageCompliance) => homePageCompliance
      case Left(err)                 =>
        ApplicationLogger.error(err.fullMsg)
        NodeSeq.Empty
    }
  }

  def inventoryInfo(nodes: Map[NodeId, CoreNodeFact]): NodeSeq = {

    val osTypes = {
      LinuxType.allKnownTypes :::
      WindowsType.allKnownTypes
    }

    // mapping between os name and their string representation (printed on screen).

    val osNames = JsObj(osTypes.map(os => (S.?("os.name." + os.name), Str(os.name)))*)

    val machines             = nodes.values.map {
      _.machine.machineType match {
        case VirtualMachineType(_) => "Virtual"
        case PhysicalMachineType   => "Physical"
        case _                     => "Unknown machine type"
      }
    }.groupBy(identity).view.mapValues(_.size).toList.sortBy(_._2).foldLeft((Nil: List[JsExp], Nil: List[JsExp])) {
      case ((labels, values), (label, value)) => (label :: labels, value :: values)
    }
    val machinesArray        = JsObj("labels" -> JsArray(machines._1), "values" -> JsArray(machines._2))
    val (osLabels, osValues) = nodes.values
      .groupBy(_.os.os.name)
      .map { case (os, value) => (S.?(s"os.name.${os}"), value.size) }
      .toList
      .sortBy(_._2)
      .foldLeft((Nil: List[JsExp], Nil: List[JsExp])) {
        case ((labels, values), (label, value)) => (label :: labels, value :: values)
      }

    val osArray = JsObj("labels" -> JsArray(osLabels), "values" -> JsArray(osValues))
    Script(OnLoad(JsRaw(s"""
      homePageInventory(
          ${machinesArray.toJsCmd}
        , ${osArray.toJsCmd}
        , ${osNames.toJsCmd}
      )"""))) // JsRaw ok, escaped
  }

  def rudderAgentVersion(nodes: Map[NodeId, CoreNodeFact]): Node = {

    val n4     = System.currentTimeMillis
    val agents = getRudderAgentVersion(nodes)
    TimingDebugLogger.debug(s"Get software: ${System.currentTimeMillis - n4}ms")

    val agentsValue = agents.toList.sortBy(_._2)(using Ordering[Int]).foldLeft((Nil: List[JsExp], Nil: List[JsExp])) {
      case ((labels, values), (label, value)) => (label :: labels, value :: values)
    }
    val agentsData  = JsObj("labels" -> JsArray(agentsValue._1), "values" -> JsArray(agentsValue._2))

    Script(OnLoad(JsRaw(s"""
        homePageSoftware(
            ${agentsData.toJsCmd}
     )"""))) // JsRaw ok, escaped
  }

  /**
   * Get the count of agent version name -> size for accepted nodes
   */
  private def getRudderAgentVersion(nodeFacts: Map[NodeId, CoreNodeFact]): Map[String, Int] = {
    val n2            = System.currentTimeMillis
    val agentSoftware = nodeFacts.map(_._2.rudderAgent.toAgentInfo)
    val agentVersions = agentSoftware.flatMap(_.version)
    val n3            = System.currentTimeMillis
    TimingDebugLogger.debug(s"Get nodes agent: ${n3 - n2}ms")

    val res = agentVersions.groupBy(ag => HomePageUtils.formatAgentVersion(ag.value)).map(x => (x._1, x._2.size))
    val n4  = System.currentTimeMillis
    TimingDebugLogger.debug(s"=> group and count agents: ${System.currentTimeMillis - n4}ms")
    res
  }

  private def countPendingNodes(implicit qc: QueryContext): Box[Int] = {
    nodeFactRepo.getAll()(using qc, SelectNodeStatus.Pending).map(_.size)
  }.toBox

  private def countAllRules(): Box[Int] = {
    roRuleRepo.getIds().map(_.size).toBox
  }

  private def countAllDirectives(): Box[Int] = {
    ldap.flatMap { con =>
      con.searchSub(rudderDit.ACTIVE_TECHNIQUES_LIB.dn, AND(IS(OC_DIRECTIVE), EQ(A_IS_SYSTEM, FALSE.toLDAPString)), "1.1")
    }.map(x => x.size)
  }.toBox

  private def countAllTechniques(): Box[Int] = {
    // for techniques, we can't easily rely on LDAP attribute, because we can have a mix of isSystem/policyType.
    // So just use the repo.
    directiveRepo
      .getFullDirectiveLibrary()
      .map(_.allActiveTechniques.collect {
        // here, we only want to count one technique whatever the number of versions, but only the ones enabled and of type "base"
        case (_, at) if at.policyTypes.isBase && at.isEnabled => Math.min(1, at.techniques.count(_._2.policyTypes.isBase))
      }.sum)
  }.toBox

  private def countAllGroups(): Box[Int] = {
    ldap
      .flatMap(con => con.searchSub(rudderDit.GROUP.dn, OR(IS(OC_RUDDER_NODE_GROUP), IS(OC_SPECIAL_TARGET)), "1.1"))
      .map(x => x.size)
  }.toBox

  private def displayCount(count: Box[Int], name: String) = {
    Text((count match {
      case Empty => 0
      case m: Failure =>
        ApplicationLogger.error(s"Could not fetch the number of ${name}. reason : ${m.messageChain}")
        0
      case Full(x) => x
    }).toString)
  }

}
