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
import scala.xml._
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.http.js._
import JsCmds._
import com.normation.ldap.sdk.BuildFilter._
import com.normation.rudder.domain.RudderLDAPConstants._
import JE._
import bootstrap.liftweb.RudderConfig
import com.normation.ldap.sdk.FALSE
import com.normation.rudder.domain.reports.ComplianceLevel
import com.normation.rudder.domain.logger.TimingDebugLogger
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.Version
import com.normation.inventory.domain.WindowsType
import com.normation.inventory.domain.LinuxType
import com.normation.inventory.domain.SolarisOS
import com.normation.inventory.domain.AixOS
import com.normation.inventory.domain.BsdType
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.utils.Control.sequence
import com.unboundid.ldap.sdk.SearchRequest
import com.normation.inventory.domain.VirtualMachineType
import com.normation.inventory.domain.PhysicalMachineType
import com.normation.inventory.domain.AgentType
import com.unboundid.ldap.sdk.controls.MatchedValuesRequestControl
import com.unboundid.ldap.sdk.controls.MatchedValuesFilter
import com.unboundid.ldap.sdk.DN

import com.normation.box._
import com.normation.errors._
import scalaz.zio._
import scalaz.zio.syntax._

sealed trait ComplianceLevelPieChart{
  def color : String
  def label : String
  def value : Int

  def jsValue = {
    JsArray(label, value)
  }

  def jsColor = {
    (label -> Str(color))
  }
}

case class DisabledChart (value : Int) extends ComplianceLevelPieChart{
  val label = "Reports Disabled"
  val color = "#b4b4b4"
}

case class GreenChart (value : Int) extends ComplianceLevelPieChart{
  val label = "Perfect (100%)"
  val color = "#5cb85c"
}

case class BlueChart (value : Int) extends ComplianceLevelPieChart{
  val label = "Good (> 75%)"
  val color = "#9bc832"
}

case class OrangeChart (value : Int) extends ComplianceLevelPieChart{
  val label = "Average (> 50%)"
  val color = "#f0ad4e"
}

case class RedChart (value : Int) extends ComplianceLevelPieChart{
  val label = "Poor (< 50%)"
  val color = "#c9302c"
}

case class PendingChart (value : Int) extends ComplianceLevelPieChart{
  val label = "Applying"
  val color = "#5bc0de"
}

object HomePage {
  private val nodeInfosService = RudderConfig.nodeInfoService

  object boxNodeInfos extends RequestVar[Box[Map[NodeId, NodeInfo]]](initNodeInfos) {
    override def doSync[F](f: => F): F = this.synchronized(f)
  }

  def initNodeInfos(): Box[Map[NodeId, NodeInfo]] = {
    TimingDebugLogger.debug(s"Start timing homepage")
    val n1 = System.currentTimeMillis
    val n = nodeInfosService.getAll
    val n2 = System.currentTimeMillis
    TimingDebugLogger.debug(s"Getting node infos: ${n2 - n1}ms")
    n
  }

}

class HomePage extends Loggable {

  private[this] val ldap             = RudderConfig.roLDAPConnectionProvider
  private[this] val pendingNodesDit  = RudderConfig.pendingNodesDit
  private[this] val acceptedNodesDit = RudderConfig.acceptedNodesDit
  private[this] val nodeDit          = RudderConfig.nodeDit
  private[this] val rudderDit        = RudderConfig.rudderDit
  private[this] val reportingService = RudderConfig.reportingService
  private[this] val mapper           = RudderConfig.ldapInventoryMapper
  private[this] val roRuleRepo       = RudderConfig.roRuleRepository

  def pendingNodes(html : NodeSeq) : NodeSeq = {
    displayCount(() => countPendingNodes, "pending nodes")
  }

  def acceptedNodes(html : NodeSeq) : NodeSeq = {
    displayCount(() => countAcceptedNodes, "accepted nodes")
  }

  def rules(html : NodeSeq) : NodeSeq = {
    displayCount(() => countAllRules, "rules")
  }

  def directives(html : NodeSeq) : NodeSeq = {
    displayCount(() => countAllDirectives,"directives")
  }

  def groups(html : NodeSeq) : NodeSeq = {
    displayCount(() => countAllGroups,"groups")
  }

  def techniques(html : NodeSeq) : NodeSeq = {
    displayCount(() => countAllTechniques,"techniques")
  }

  def getAllCompliance = {

    trait ChartType
    case object PendingChartType extends ChartType
    case object DisabledChartType extends ChartType
    case class ColoredChartType(value: Double) extends ChartType

    ( for {
      nodeInfos <- HomePage.boxNodeInfos.get
      n2 = System.currentTimeMillis
      userRules <- roRuleRepo.getIds().toBox
      n3 = System.currentTimeMillis
      _ = TimingDebugLogger.trace(s"Get rules: ${n3 - n2}ms")
      reports   <- reportingService.findRuleNodeStatusReports(nodeInfos.keySet, userRules)
      global    <- reportingService.getGlobalUserCompliance()
      n4 = System.currentTimeMillis
      _ = TimingDebugLogger.trace(s"Compute Rule Node status reports for all nodes: ${n4 - n3}ms")
      _ = TimingDebugLogger.debug(s"Compute compliance: ${n4 - n2}ms")
    } yield {

      val reportsByNode = reports.mapValues { status => ComplianceLevel.sum(status.report.reports.map(_.compliance)) }

      /*
       * Here, for the compliance by node, we want to distinguish (but NOT ignore, like in globalCompliance) the
       * case where the node is pending.
       *
       * We are using a coarse grain here: if a node as even ONE report not pending, we compute it's compliance.
       * Else, if the node's reports are ALL pending, we use a special pending case.
       *
       * Note: node without reports are also put in "pending".
       */

      val complianceByNode : List[ChartType] = reportsByNode.values.map { r =>
        if(r.pending == r.total) { PendingChartType }
        else if(r.reportsDisabled == r.total) { DisabledChartType }
        else { ColoredChartType(r.complianceWithoutPending) }
      }.toList

      val complianceDiagram : List[ComplianceLevelPieChart] = (complianceByNode.groupBy{compliance => compliance match {
        case PendingChartType               => PendingChart
        case DisabledChartType              => DisabledChart
        case ColoredChartType(100)          => GreenChart
        case ColoredChartType(x) if x >= 75 => BlueChart
        case ColoredChartType(x) if x >= 50 => OrangeChart
        case ColoredChartType(_)            => RedChart
      } }.map {
        case (PendingChart , compliance) => PendingChart(compliance.size)
        case (DisabledChart, compliance) => DisabledChart(compliance.size)
        case (GreenChart   , compliance) => GreenChart(compliance.size)
        case (BlueChart    , compliance) => BlueChart(compliance.size)
        case (OrangeChart  , compliance) => OrangeChart(compliance.size)
        case (RedChart     , compliance) => RedChart(compliance.size)
        case (_            , compliance) => RedChart(compliance.size)
      }).toList

      val sorted = complianceDiagram.sortWith{
        case (_:PendingChart  ,_)            => false
        case (_:DisabledChart ,_)            => false
        case (_:GreenChart    ,_)            => false
        case (_:BlueChart     ,_:GreenChart) => true
        case (_:BlueChart     ,_)            => false
        case (_:OrangeChart   , ( _:GreenChart| _:BlueChart)) => true
        case (_:OrangeChart   ,_)            => false
        case (_:RedChart      ,_)            => true
      }

      val numberOfNodes = complianceByNode.size
      val pendingNodes = complianceDiagram.collectFirst{
        case p : PendingChart => p.value
      } match {

        case None =>
          JsObj (
            "pending" -> JsNull
          , "active"  -> numberOfNodes
        )

        case Some(pending) =>
          JsObj (
            "pending" ->
              JsObj (
                  "nodes"   -> pending
                , "percent" -> (pending * 100.0  / numberOfNodes).round
              )
          , "active"  -> (numberOfNodes - pending)
        )
      }

      val diagramData = sorted.foldLeft((Nil : List[JsExp], Nil : List[JsExp], Nil : List[JsExp]))
      { case ((labels,values,colors),diag) => (diag.label :: labels, diag.value :: values, diag.color :: colors) }

      val data =  JsObj("labels" -> JsArray(diagramData._1), "values" -> JsArray(diagramData._2), "colors" -> JsArray(diagramData._3))

      val diagramColor = JsObj(sorted.map(_.jsColor):_*)

      // Data used for compliance bar, compliance without pending
      val (complianceBar, globalCompliance) = global match {
        case Some((bar, value)) =>
          import com.normation.rudder.domain.reports.ComplianceLevelSerialisation._
          (bar.copy(pending = 0).toJsArray, value)
        case None =>
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
    } ) match {
      case Full(homePageCompliance) => homePageCompliance
      case eb : EmptyBox =>
        logger.error(eb)
        NodeSeq.Empty
    }
  }

  def inventoryInfo() = {


    val osTypes = AixOS       ::
      SolarisOS               ::
      BsdType.allKnownTypes   :::
      LinuxType.allKnownTypes :::
      WindowsType.allKnownTypes

    // mapping between os name and their string representation (printed on screen).

    val osNames = JsObj(osTypes.map {os => (S.?("os.name." + os.name), Str(os.name))}:_*)

    ( for {
      nodeInfos <- HomePage.boxNodeInfos.get
    } yield {
      val machines = nodeInfos.values.map { _.machine.map(_.machineType) match {
                        case Some(_: VirtualMachineType) => "Virtual"
                        case Some(PhysicalMachineType)   => "Physical"
                        case _                           => "No Machine Inventory"
                      } }.groupBy(identity).mapValues(_.size).toList.sortBy(_._2).foldLeft((Nil : List[JsExp], Nil : List[JsExp]))
      { case ((labels,values),(label,value)) => (label :: labels, value :: values) }
      val machinesArray = JsObj("labels" -> JsArray(machines._1), "values" -> JsArray(machines._2))
      val (osLabels,osValues) = nodeInfos.values.groupBy(_.osDetails.os.name).map{ case (os,value) => (S.?(s"os.name.${os}"), value.size) }.toList.sortBy(_._2).foldLeft((Nil : List[JsExp], Nil : List[JsExp]))
      { case ((labels,values),(label,value)) => (label :: labels, value :: values) }

      val osArray = JsObj("labels" -> JsArray(osLabels), "values" -> JsArray(osValues))
      Script(OnLoad(JsRaw(s"""
        homePageInventory(
            ${machinesArray.toJsCmd}
          , ${osArray.toJsCmd}
          , ${nodeInfos.size}
          , ${osNames.toJsCmd}
        )""")))
    } ) match {
      case Full(inventory) => inventory
      case _ => NodeSeq.Empty
    }
  }

  def rudderAgentVersion() = {

     val n4 = System.currentTimeMillis
     val agents = getRudderAgentVersion match {
       case Full(x) => x
       case eb: EmptyBox =>
         val e = eb ?~! "Error when getting installed agent version on nodes"
         logger.debug(e.messageChain)
         e.rootExceptionCause.foreach { ex =>
           logger.debug("Root exception was:", ex)
         }
         Map("Unknown" -> 1)
     }
     TimingDebugLogger.debug(s"Get software: ${System.currentTimeMillis-n4}ms")

     val agentsValue = agents.toList.sortBy(_._2)(Ordering[Int]).foldLeft((Nil : List[JsExp], Nil : List[JsExp]))
      { case ((labels,values),(label,value)) => (label :: labels, value :: values) }
     val agentsData =  JsObj("labels" -> JsArray(agentsValue._1), "values" -> JsArray(agentsValue._2))

     Script(OnLoad(JsRaw(s"""
        homePageSoftware(
            ${agentsData.toJsCmd}
          , ${agents.map(_._2).sum}
     )""")))
  }

  /**
   * Get the count of agent version name -> size for accepted nodes
   */
  private[this] def getRudderAgentVersion() : Box[Map[String, Int]] = {
    import com.normation.ldap.sdk._
    import com.normation.ldap.sdk.BuildFilter.SUB
    import com.normation.ldap.sdk.BuildFilter.OR
    import com.normation.inventory.ldap.core.LDAPConstants.{A_NAME, A_NODE_UUID, A_SOFTWARE_DN}

    val unknown = new Version("Unknown")

    for {
      con              <- ldap
      nodeInfos        <- HomePage.boxNodeInfos.get.toIO
      n2               =  System.currentTimeMillis
      agentSoftEntries <-  con.searchOne(acceptedNodesDit.SOFTWARE.dn, OR(AgentType.allValues.toList.map(t => SUB(A_NAME, null, Array(t.inventorySoftwareName), null)):_*))
      agentSoftDn      =  agentSoftEntries.map(_.dn.toString).toSet

      agentSoft        <- ZIO.foreach(agentSoftEntries){ entry =>
                            (mapper.softwareFromEntry(entry).chainError(s"Error when mapping LDAP entry ${entry} to a software")).map { s =>
                              //here, we want to use Agent Version display name, not the software one
                              s.name match {
                                case None => s
                                // only keep before first "." because in some case, the distrib reports "rudder-agent.x86-64"
                                case Some(name) => name.toLowerCase.split("""\.""").head match {
                                  case AgentType.CfeEnterprise.inventorySoftwareName =>
                                    s.copy(version = s.version.map(v => new Version(AgentType.CfeEnterprise.toAgentVersionName(v.value))))
                                  case ag if ag == AgentType.Dsc.inventorySoftwareName.toLowerCase =>
                                    s.copy(version = s.version.map(v => new Version(AgentType.Dsc.toAgentVersionName(v.value))))
                                  case _ => s
                                }
                              }
                            }.toIO
                          }
      n3               =  System.currentTimeMillis
      _                =  TimingDebugLogger.debug(s"Get agent software entries: ${n3-n2}ms")
      nodeEntries      <-  {
                            val sr = new SearchRequest(
                                acceptedNodesDit.NODES.dn.toString
                              , One
                              , OR(agentSoft.map(x => EQ(A_SOFTWARE_DN, acceptedNodesDit.SOFTWARE.SOFT.dn(x.id).toString)):_*)
                              , A_NODE_UUID, A_SOFTWARE_DN
                            )
                            // Skip if there is no rudder-agent packages in software DN
                            if (! agentSoftDn.isEmpty) {
                              //only get interesting entries control - that make a huge difference in perf
                              sr.addControl(new MatchedValuesRequestControl(agentSoftDn.map(dn => MatchedValuesFilter.createEqualityFilter(A_SOFTWARE_DN, dn)).toSeq:_*))
                              con.search(sr)
                            } else {
                              Seq().succeed
                            }
                          }
      n4               =  System.currentTimeMillis
      _                =  TimingDebugLogger.debug(s"Get nodes for agent: ${n4-n3}ms")
    } yield {

      val agentMap = agentSoft.map(x => (x.id.value, x)).toMap

      val agentVersionByNodeEntries = nodeEntries.map { e =>
        (
            NodeId(e.value_!(A_NODE_UUID))
          , e.valuesFor(A_SOFTWARE_DN).intersect(agentSoftDn).flatMap { x =>
              acceptedNodesDit.SOFTWARE.SOFT.idFromDN(new DN(x)).toOption.flatMap(s => agentMap(s.value).version)
            }
        )
      }.toMap.mapValues{_.maxBy(_.value)} // Get the max version available

      // take back the initial set of nodes to be sure to have one agent for each
      val allAgents = nodeInfos.keySet.toSeq.map(nodeId => agentVersionByNodeEntries.get(nodeId) match {
        case Some(v) => v
        case None    =>
          logger.debug(s"Node with ID '${nodeId.value}' have an unknow agent version")
          unknown
      })

      // Format different version naming type into one
      def formatVersion (version : String) : String= {
        // All that is before '.release' (rpm relases, like 3.0.6.release)
        // OR DSC agent version, => value before dash + dash + first two numbers separated by a dot (a.b-x.y)
        // OR all until first dash ( debian releases, like 3.0.6-wheezy)
        val versionRegexp = "(cfe-)?((.+)(?=\\.release)|([^-]+-\\d+\\.\\d+)|([^-]+))".r
        versionRegexp.
          findFirstIn(version).
          getOrElse(version).
          // Replace all '~' by '.' to normalize alpha/beta/rc releases and nightlies
          replace("~", ".")
      }

      val res = allAgents.groupBy(ag => formatVersion(ag.value)).mapValues( _.size)

      TimingDebugLogger.debug(s"=> group and count agents: ${System.currentTimeMillis-n4}ms")
      res
    }
  }.toBox

  private[this] def countPendingNodes() : Box[Int] = {
    ldap.flatMap { con =>
      con.searchOne(pendingNodesDit.NODES.dn, ALL, "1.1")
    }.map(x => x.size)
  }.toBox

  private[this] def countAcceptedNodes() : Box[Int] = {
    ldap.flatMap { con =>
      con.searchOne(nodeDit.NODES.dn, ALL, "1.1")
    }.map(x => x.size)
  }.toBox

  private[this] def countAllRules() : Box[Int] = {
    roRuleRepo.getIds().map(_.size).toBox
  }

  private[this] def countAllDirectives() : Box[Int] = {
    ldap.flatMap { con =>
      con.searchSub(rudderDit.ACTIVE_TECHNIQUES_LIB.dn, AND(IS(OC_DIRECTIVE), EQ(A_IS_SYSTEM, FALSE.toLDAPString)), "1.1")
    }.map(x => x.size)
  }.toBox

  private[this] def countAllTechniques() : Box[Int] = {
    ldap.flatMap { con =>
      con.searchSub(rudderDit.ACTIVE_TECHNIQUES_LIB.dn, AND(IS(OC_ACTIVE_TECHNIQUE), EQ(A_IS_SYSTEM, FALSE.toLDAPString)), "1.1")
    }.map(x => x.size)
  }.toBox

  private[this] def countAllGroups() : Box[Int] = {
    ldap.flatMap { con =>
      con.searchSub(rudderDit.GROUP.dn, AND(IS(OC_RUDDER_NODE_GROUP), EQ(A_IS_SYSTEM, FALSE.toLDAPString)), "1.1")
    }.map(x => x.size)
  }.toBox

  private[this] def displayCount( count : () => Box[Int], name : String) ={
    Text((count() match {
      case Empty => 0
      case m:Failure =>
          logger.error(s"Could not fetch the number of ${name}. reason : ${m.messageChain}")
          0
      case Full(x) => x
    }).toString)
  }

}
