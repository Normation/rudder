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
import com.normation.errors.*
import com.normation.inventory.domain.AixOS
import com.normation.inventory.domain.BsdType
import com.normation.inventory.domain.LinuxType
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.PhysicalMachineType
import com.normation.inventory.domain.SolarisOS
import com.normation.inventory.domain.VirtualMachineType
import com.normation.inventory.domain.WindowsType
import com.normation.inventory.ldap.core.TimingDebugLoggerPure
import com.normation.ldap.sdk.BuildFilter.*
import com.normation.ldap.sdk.FALSE
import com.normation.rudder.domain.RudderLDAPConstants.*
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.rudder.domain.logger.ComplianceLogger
import com.normation.rudder.domain.logger.TimingDebugLogger
import com.normation.rudder.domain.reports.ComplianceLevel
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.users.CurrentUser
import com.normation.zio.*
import net.liftweb.common.*
import net.liftweb.http.*
import net.liftweb.http.js.*
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import scala.collection.MapView
import scala.xml.*

sealed trait ComplianceLevelPieChart {
  def color: String
  def label: String
  def value: Int

  def jsValue: JsArray = {
    JsArray(label, value)
  }

  def jsColor: (String, Str) = {
    (label -> Str(color))
  }
}

final case class DisabledChart(value: Int) extends ComplianceLevelPieChart {
  val label = "Reports Disabled"
  val color = "#B1BBCB"
}

final case class GreenChart(value: Int) extends ComplianceLevelPieChart {
  val label = "Perfect (100%)"
  val color = "#13BEB7"
}

final case class BlueChart(value: Int) extends ComplianceLevelPieChart {
  val label = "Good (> 75%)"
  val color = "#B1EDA4"
}

final case class OrangeChart(value: Int) extends ComplianceLevelPieChart {
  val label = "Average (> 50%)"
  val color = "#EF9600"
}

final case class RedChart(value: Int) extends ComplianceLevelPieChart {
  val label = "Poor (< 50%)"
  val color = "#DA291C"
}

final case class PendingChart(value: Int) extends ComplianceLevelPieChart {
  val label = "Applying"
  val color = "#5bc0de"
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

object HomePage {
  private val nodeFactRepo = RudderConfig.nodeFactRepository

  def initNodeInfos()(implicit qc: QueryContext): MapView[NodeId, CoreNodeFact] = {
    (for {
      _  <- TimingDebugLoggerPure.debug(s"Start timing homepage")
      n1 <- currentTimeMillis
      n  <- nodeFactRepo.getAll()
      n2 <- currentTimeMillis
      _  <- TimingDebugLoggerPure.debug(s"Getting node infos: ${n2 - n1}ms")
    } yield {
      n
    }).either.runNow match {
      case Right(n)  => n
      case Left(err) =>
        ApplicationLogger.warn(s"Error when getting nodes: ${err.fullMsg}")
        MapView.empty[NodeId, CoreNodeFact]
    }
  }

  object nodeFacts extends SessionVar[MapView[NodeId, CoreNodeFact]](initNodeInfos()(CurrentUser.queryContext))
}

class HomePage extends StatefulSnippet {

  private[this] val ldap             = RudderConfig.roLDAPConnectionProvider
  private[this] val pendingNodesDit  = RudderConfig.pendingNodesDit
  private[this] val rudderDit        = RudderConfig.rudderDit
  private[this] val reportingService = RudderConfig.reportingService
  private[this] val roRuleRepo       = RudderConfig.roRuleRepository

  override val dispatch: DispatchIt = {
    case "pendingNodes"       => pendingNodes
    case "acceptedNodes"      => acceptedNodes
    case "rules"              => rules
    case "directives"         => directives
    case "groups"             => groups
    case "techniques"         => techniques
    case "getAllCompliance"   => _ => getAllCompliance()
    case "inventoryInfo"      => _ => inventoryInfo()
    case "rudderAgentVersion" => _ => rudderAgentVersion()
  }

  def pendingNodes(html: NodeSeq): NodeSeq = {
    displayCount(() => countPendingNodes(), "pending nodes")
  }

  def acceptedNodes(html: NodeSeq): NodeSeq = {
    displayCount(() => countAcceptedNodes(HomePage.nodeFacts.get), "accepted nodes")
  }

  def rules(html: NodeSeq): NodeSeq = {
    displayCount(() => countAllRules(), "rules")
  }

  def directives(html: NodeSeq): NodeSeq = {
    displayCount(() => countAllDirectives(), "directives")
  }

  def groups(html: NodeSeq): NodeSeq = {
    displayCount(() => countAllGroups(), "groups")
  }

  def techniques(html: NodeSeq): NodeSeq = {
    displayCount(() => countAllTechniques(), "techniques")
  }

  def getAllCompliance(): NodeSeq = {

    sealed trait ChartType
    case object PendingChartType                     extends ChartType
    case object DisabledChartType                    extends ChartType
    final case class ColoredChartType(value: Double) extends ChartType

    (for {
      n2                <- currentTimeMillis
      userRules         <- roRuleRepo.getIds()
      n3                <- currentTimeMillis
      _                  = TimingDebugLogger.trace(s"Get rules: ${n3 - n2}ms")
      // reports contains the reports for user rules, used in the donut
      reports           <-
        reportingService.findRuleNodeStatusReports(HomePage.nodeFacts.get.keys.toSet, userRules)(CurrentUser.queryContext).toIO
      n4                <- currentTimeMillis
      _                  = TimingDebugLogger.trace(s"Compute Rule Node status reports for all nodes: ${n4 - n3}ms")
      // global compliance is a unique number, used in the top right hand size, based on
      // user rules, and ignoring pending nodes

      // TODO : we could first compute per nodes, and then compute the globalCompliance by excluding the pending nodes
      compliancePerNodes =
        reports.map { case (nodeId, status) => (nodeId, ComplianceLevel.sum(status.reports.map(_.compliance))) }
      global             = if (reports.isEmpty) {
                             None
                           } else {
                             val complianceLevel = ComplianceLevel.sum(compliancePerNodes.map(_._2))
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
    } yield {

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

      val complianceByNode: List[ChartType] = compliancePerNodes.values.map { r =>
        if (r.pending == r.total) { PendingChartType }
        else if (r.reportsDisabled == r.total) { DisabledChartType }
        else { ColoredChartType(r.withoutPending.computePercent().compliance) }
      }.toList

      val complianceDiagram: List[ComplianceLevelPieChart] = (complianceByNode.groupBy { compliance =>
        compliance match {
          case PendingChartType               => PendingChart
          case DisabledChartType              => DisabledChart
          case ColoredChartType(100)          => GreenChart
          case ColoredChartType(x) if x >= 75 => BlueChart
          case ColoredChartType(x) if x >= 50 => OrangeChart
          case ColoredChartType(_)            => RedChart
        }
      }.map {
        case (PendingChart, compliance)  => PendingChart(compliance.size)
        case (DisabledChart, compliance) => DisabledChart(compliance.size)
        case (GreenChart, compliance)    => GreenChart(compliance.size)
        case (BlueChart, compliance)     => BlueChart(compliance.size)
        case (OrangeChart, compliance)   => OrangeChart(compliance.size)
        case (RedChart, compliance)      => RedChart(compliance.size)
        case (_, compliance)             => RedChart(compliance.size)
      }).toList

      val sorted = complianceDiagram.sortWith {
        case (_: PendingChart, _)                             => false
        case (_: DisabledChart, _)                            => false
        case (_: GreenChart, _)                               => false
        case (_: BlueChart, _: GreenChart)                    => true
        case (_: BlueChart, _)                                => false
        case (_: OrangeChart, (_: GreenChart | _: BlueChart)) => true
        case (_: OrangeChart, _)                              => false
        case (_: RedChart, _)                                 => true
      }

      val numberOfNodes = complianceByNode.size
      val pendingNodes  = complianceDiagram.collectFirst { case p: PendingChart => p.value } match {

        case None =>
          JsObj(
            "pending" -> JsNull,
            "active"  -> numberOfNodes
          )

        case Some(pending) =>
          JsObj(
            "pending" ->
            JsObj(
              "nodes"   -> pending,
              "percent" -> (pending * 100.0 / numberOfNodes).round
            ),
            "active"  -> (numberOfNodes - pending)
          )
      }

      val diagramData = sorted.foldLeft((Nil: List[JsExp], Nil: List[JsExp], Nil: List[JsExp])) {
        case ((labels, values, colors), diag) => (diag.label :: labels, diag.value :: values, diag.color :: colors)
      }

      val data =
        JsObj("labels" -> JsArray(diagramData._1), "values" -> JsArray(diagramData._2), "colors" -> JsArray(diagramData._3))

      val diagramColor = JsObj(sorted.map(_.jsColor)*)

      // Data used for compliance bar, compliance without pending
      val (complianceBar, globalCompliance) = global match {
        case Some((bar, value)) =>
          import com.normation.rudder.domain.reports.ComplianceLevelSerialisation.*
          (bar.copy(pending = 0).toJsArray, value)
        case None               =>
          (JsArray(Nil), -1L)
      }

      val n4 = System.currentTimeMillis
      TimingDebugLogger.debug(s"Compute compliance for HomePage: ${n4 - n2}ms")

      Script(OnLoad(JsRaw(s"""
        homePage(
            ${complianceBar.toJsCmd}
          , ${globalCompliance}
          , ${data.toJsCmd}
          , ${diagramColor.toJsCmd}
          , ${pendingNodes.toJsCmd}
        )""")))
    }).either.runNow match {
      case Right(homePageCompliance) => homePageCompliance
      case Left(err)                 =>
        ApplicationLogger.error(err.fullMsg)
        NodeSeq.Empty
    }
  }

  def inventoryInfo(): NodeSeq = {

    val osTypes = AixOS ::
      SolarisOS ::
      BsdType.allKnownTypes :::
      LinuxType.allKnownTypes :::
      WindowsType.allKnownTypes

    // mapping between os name and their string representation (printed on screen).

    val osNames = JsObj(osTypes.map(os => (S.?("os.name." + os.name), Str(os.name)))*)

    val machines             = HomePage.nodeFacts.get.values.map {
      _.machine.machineType match {
        case VirtualMachineType(_) => "Virtual"
        case PhysicalMachineType   => "Physical"
        case _                     => "Unknown machine type"
      }
    }.groupBy(identity).view.mapValues(_.size).toList.sortBy(_._2).foldLeft((Nil: List[JsExp], Nil: List[JsExp])) {
      case ((labels, values), (label, value)) => (label :: labels, value :: values)
    }
    val machinesArray        = JsObj("labels" -> JsArray(machines._1), "values" -> JsArray(machines._2))
    val (osLabels, osValues) = HomePage.nodeFacts.get.values
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
        , ${HomePage.nodeFacts.get.size}
        , ${osNames.toJsCmd}
      )""")))
  }

  def rudderAgentVersion(): Node = {

    val n4     = System.currentTimeMillis
    val agents = getRudderAgentVersion(HomePage.nodeFacts.get)
    TimingDebugLogger.debug(s"Get software: ${System.currentTimeMillis - n4}ms")

    val agentsValue = agents.toList.sortBy(_._2)(Ordering[Int]).foldLeft((Nil: List[JsExp], Nil: List[JsExp])) {
      case ((labels, values), (label, value)) => (label :: labels, value :: values)
    }
    val agentsData  = JsObj("labels" -> JsArray(agentsValue._1), "values" -> JsArray(agentsValue._2))

    Script(OnLoad(JsRaw(s"""
        homePageSoftware(
            ${agentsData.toJsCmd}
          , ${agents.map(_._2).sum}
     )""")))
  }

  /**
   * Get the count of agent version name -> size for accepted nodes
   */
  private[this] def getRudderAgentVersion(nodeFacts: MapView[NodeId, CoreNodeFact]): Map[String, Int] = {
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

  private[this] def countPendingNodes(): Box[Int] = {
    ldap.flatMap(con => con.searchOne(pendingNodesDit.NODES.dn, ALL, "1.1")).map(x => x.size)
  }.toBox

  private[this] def countAcceptedNodes(nodeFacts: MapView[NodeId, CoreNodeFact]): Box[Int] = {
    Full(nodeFacts.size)
  }

  private[this] def countAllRules(): Box[Int] = {
    roRuleRepo.getIds().map(_.size).toBox
  }

  private[this] def countAllDirectives(): Box[Int] = {
    ldap.flatMap { con =>
      con.searchSub(rudderDit.ACTIVE_TECHNIQUES_LIB.dn, AND(IS(OC_DIRECTIVE), EQ(A_IS_SYSTEM, FALSE.toLDAPString)), "1.1")
    }.map(x => x.size)
  }.toBox

  private[this] def countAllTechniques(): Box[Int] = {
    ldap.flatMap { con =>
      con.searchSub(rudderDit.ACTIVE_TECHNIQUES_LIB.dn, AND(IS(OC_ACTIVE_TECHNIQUE), EQ(A_IS_SYSTEM, FALSE.toLDAPString)), "1.1")
    }.map(x => x.size)
  }.toBox

  private[this] def countAllGroups(): Box[Int] = {
    ldap
      .flatMap(con => con.searchSub(rudderDit.GROUP.dn, OR(IS(OC_RUDDER_NODE_GROUP), IS(OC_SPECIAL_TARGET)), "1.1"))
      .map(x => x.size)
  }.toBox

  private[this] def displayCount(count: () => Box[Int], name: String) = {
    Text((count() match {
      case Empty => 0
      case m: Failure =>
        ApplicationLogger.error(s"Could not fetch the number of ${name}. reason : ${m.messageChain}")
        0
      case Full(x) => x
    }).toString)
  }

}
