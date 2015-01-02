/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

package com.normation.rudder.web.snippet

//lift std import
import scala.xml._
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.util._
import Helpers._
import net.liftweb.http.js._
import JsCmds._
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.BuildFilter._
import com.normation.rudder.domain.NodeDit
import com.normation.rudder.domain.RudderLDAPConstants._
import com.normation.rudder.repository.RoRuleRepository
import JE._
import net.liftweb.http.SHtml._
import com.normation.ldap.sdk.RoLDAPConnection
import bootstrap.liftweb.RudderConfig
import com.normation.ldap.sdk.FALSE
import com.normation.rudder.domain.reports.ComplianceLevel
import com.normation.inventory.domain.AcceptedInventory
import com.normation.rudder.domain.logger.TimingDebugLogger
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.InventoryStatus
import com.normation.inventory.domain.Software
import com.normation.inventory.domain.Version
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.utils.Control.sequence
import com.unboundid.ldap.sdk.SearchRequest
import com.unboundid.ldap.sdk.controls.MatchedValuesRequestControl
import com.unboundid.ldap.sdk.controls.MatchedValuesFilter

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

case class GreenChart (value : Int) extends ComplianceLevelPieChart{
  val label = "Perfect (100%)"
  val color = "#5cb85c"
}

case class BlueChart (value : Int) extends ComplianceLevelPieChart{
  val label = "Good (> 75%)"
  val color = "#5bc0de"
}

case class OrangeChart (value : Int) extends ComplianceLevelPieChart{
  val label = "Average (> 50%)"
  val color = "#f0ad4e"
}

case class RedChart (value : Int) extends ComplianceLevelPieChart{
  val label = "Poor (< 50%)"
  val color = "#d9534f"
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
  private[this] val softwareService  = RudderConfig.readOnlySoftwareDAO
  private[this] val mapper           = RudderConfig.ldapInventoryMapper


  def pendingNodes(html : NodeSeq) : NodeSeq = {
    displayCount(countPendingNodes, "pending nodes")
  }

  def acceptedNodes(html : NodeSeq) : NodeSeq = {
    displayCount(countAcceptedNodes, "accepted nodes")
  }

  def rules(html : NodeSeq) : NodeSeq = {
    displayCount(countAllRules, "rules")
  }

  def directives(html : NodeSeq) : NodeSeq = {
    displayCount(countAllDirectives,"directives")
  }

  def groups(html : NodeSeq) : NodeSeq = {
    displayCount(countAllGroups,"groups")
  }

  def techniques(html : NodeSeq) : NodeSeq = {
    displayCount(countAllTechniques,"techniques")
  }

  def getAllCompliance = {
    ( for {
      nodeInfos <- HomePage.boxNodeInfos.is
      n2 = System.currentTimeMillis
      reports   <- reportingService.findRuleNodeStatusReports(nodeInfos.keySet, Set())
      n3 = System.currentTimeMillis
      _ = TimingDebugLogger.debug(s"Compute compliance: ${n3 - n2}ms")
    } yield {

      val compliance = ComplianceLevel.sum(reports.map(_.compliance))


      val complianceByNode : List[Float] = reports.groupBy(_.nodeId).mapValues(reports => ComplianceLevel.sum(reports.map(_.compliance)).compliance).values.toList

      val complianceDiagram : List[ComplianceLevelPieChart] = (complianceByNode.groupBy{compliance =>
      if (compliance == 100) GreenChart else
        if (compliance >= 75) BlueChart else
          if (compliance >= 50) OrangeChart else
              RedChart
      }.map {
        case (GreenChart,compliance) => GreenChart(compliance.size)
        case (BlueChart,compliance) => BlueChart(compliance.size)
        case (OrangeChart,compliance) => OrangeChart(compliance.size)
        case (RedChart,compliance) => RedChart(compliance.size)
        case (_,compliance) => RedChart(compliance.size)
      }).toList

     val sorted = complianceDiagram.sortWith{
        case (a:GreenChart,_) => true
        case (a:BlueChart,_:GreenChart) => false
        case (a:BlueChart,_) => true
        case (_:OrangeChart,(_:GreenChart|_:BlueChart)) => false
        case (a:OrangeChart,_) => true
        case (a:RedChart,_) => false
      }

     val diagramData = JsArray(sorted.map(_.jsValue):_*)

     val diagramColor = JsObj(sorted.map(_.jsColor):_*)

     val array = JsArray(
         JE.Num(compliance.pc_notApplicable)
       , JE.Num(compliance.pc_success)
       , JE.Num(compliance.pc_repaired)
       , JE.Num(compliance.pc_error)
       , JE.Num(compliance.pc_pending)
       , JE.Num(compliance.pc_noAnswer)
       , JE.Num(compliance.pc_missing)
       , JE.Num(compliance.pc_unexpected)
     )


     val globalCompliance = compliance.compliance.round

     Script(OnLoad(JsRaw(s"""
        homePage(
            ${array.toJsCmd}
          , ${globalCompliance}
          , ${diagramData.toJsCmd}
          , ${diagramColor.toJsCmd}
        )""")))
    } ) match {
      case Full(complianceBar) => complianceBar
      case eb : EmptyBox =>
        logger.error(eb)
        NodeSeq.Empty
    }
  }

  def inventoryInfo() = {
    ( for {
      nodeInfos <- HomePage.boxNodeInfos.is
    } yield {
      val machines = nodeInfos.values.groupBy(_.machineType).mapValues(_.size).map{case (a,b) => JsArray(a, b)}
      val machinesArray = JsArray(machines.toList)
      val os = nodeInfos.values.groupBy(_.osName).mapValues(_.size).map{case (a,b) => JsArray(a, b)}
      val osArray = JsArray(os.toList)

      Script(OnLoad(JsRaw(s"""
        homePageInventory(
            ${machinesArray.toJsCmd}
          , ${osArray.toJsCmd}
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

     val agentsValue = agents.map{case (a,b) => JsArray(a, b)}
     val agentsData =JsArray(agentsValue.toList)

     Script(OnLoad(JsRaw(s"""
        homePageSoftware(
            ${agentsData.toJsCmd}
     )""")))
  }


  /**
   * Get the count of agent version name -> size for accepted nodes
   */
  private[this] def getRudderAgentVersion() : Box[Map[String, Int]] = {
    import com.normation.ldap.sdk._
    import com.normation.ldap.sdk.BuildFilter.{EQ,OR}
    import com.normation.inventory.ldap.core.LDAPConstants.{A_NAME, A_SOFTWARE_UUID, A_NODE_UUID, A_SOFTWARE_DN}
    import com.unboundid.ldap.sdk.DN

    val unknown = new Version("Unknown")

    val n1 = System.currentTimeMillis
    for {
      con <- ldap
      nodeInfos        <- HomePage.boxNodeInfos.is
      n2               =  System.currentTimeMillis
      agentSoftEntries =  con.searchOne(acceptedNodesDit.SOFTWARE.dn, EQ(A_NAME, "rudder-agent"))
      agentSoftDn      =  agentSoftEntries.map(_.dn.toString).toSet
      agentSoft        <- sequence(agentSoftEntries){ entry =>
                            mapper.softwareFromEntry(entry) ?~! "Error when mapping LDAP entry %s to a software".format(entry)
                          }
      n3               =  System.currentTimeMillis
      _                =  TimingDebugLogger.debug(s"Get agent software entries: ${n3-n2}ms")
      nodeEntries      =  {
                            val sr = new SearchRequest(
                                acceptedNodesDit.NODES.dn.toString
                              , One
                              , OR(agentSoft.map(x => EQ(A_SOFTWARE_DN, acceptedNodesDit.SOFTWARE.SOFT.dn(x.id).toString)):_*)
                              , A_NODE_UUID, A_SOFTWARE_DN
                            )
                            //only get interesting entries control - that make a huge difference in perf
                            sr.addControl(new MatchedValuesRequestControl(agentSoftDn.map(dn => MatchedValuesFilter.createEqualityFilter(A_SOFTWARE_DN, dn)).toSeq:_*))
                            con.search(sr)
                          }
      n4               =  System.currentTimeMillis
      _                =  TimingDebugLogger.debug(s"Get nodes for agent: ${n4-n3}ms")
    } yield {

      val agentMap = agentSoft.map(x => (x.id.value, x)).toMap
      val agents = agentMap.keySet

      val agentVersionByNodeEntries = nodeEntries.map { e =>
        (
            NodeId(e.value_!(A_NODE_UUID))
          , e.valuesFor(A_SOFTWARE_DN).intersect(agentSoftDn).flatMap { x =>
              acceptedNodesDit.SOFTWARE.SOFT.idFromDN(new DN(x)).flatMap(s => agentMap(s.value).version)
            }
        )
      }.toMap

      // take back the initial set of nodes to be sure to have one agent for each
      val allAgents = nodeInfos.keySet.toSeq.flatMap(nodeId => agentVersionByNodeEntries.getOrElse(nodeId, Set(unknown)).toSeq )

      //now, count and version display
      val res = allAgents.groupBy(identity).mapValues(_.size).map{case (a,b) => (a.value.split("-").head, b)}

      TimingDebugLogger.debug(s"=> group and count agents: ${System.currentTimeMillis-n4}ms")
      res
    }
  }

  private[this] def countPendingNodes() : Box[Int] = {
    ldap.map { con =>
      con.searchOne(pendingNodesDit.NODES.dn, ALL, "1.1")
    }.map(x => x.size)
  }

  private[this] def countAcceptedNodes() : Box[Int] = {
    ldap.map { con =>
      con.searchOne(nodeDit.NODES.dn, NOT(IS(OC_POLICY_SERVER_NODE)), "1.1")
    }.map(x => x.size)
  }

  private[this] def countAllRules() : Box[Int] = {
    ldap.map { con =>
      con.searchOne(rudderDit.RULES.dn, EQ(A_IS_SYSTEM, FALSE.toLDAPString), "1.1")
    }.map(x => x.size)
  }

  private[this] def countAllDirectives() : Box[Int] = {
    ldap.map { con =>
      con.searchSub(rudderDit.ACTIVE_TECHNIQUES_LIB.dn, AND(IS(OC_DIRECTIVE), EQ(A_IS_SYSTEM, FALSE.toLDAPString)), "1.1")
    }.map(x => x.size)
  }

  private[this] def countAllTechniques() : Box[Int] = {
    ldap.map { con =>
      con.searchSub(rudderDit.ACTIVE_TECHNIQUES_LIB.dn, AND(IS(OC_ACTIVE_TECHNIQUE), EQ(A_IS_SYSTEM, FALSE.toLDAPString)), "1.1")
    }.map(x => x.size)
  }

  private[this] def countAllGroups() : Box[Int] = {
    ldap.map { con =>
      con.searchSub(rudderDit.GROUP.dn, AND(IS(OC_RUDDER_NODE_GROUP), EQ(A_IS_SYSTEM, FALSE.toLDAPString)), "1.1")
    }.map(x => x.size)
  }


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
