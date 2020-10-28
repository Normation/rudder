package com.normation.rudder.rest.internal

import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.Software
import com.normation.inventory.services.core.ReadOnlySoftwareDAO
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.policies.GlobalPolicyMode
import com.normation.rudder.domain.policies.PolicyModeOverrides.Always
import com.normation.rudder.domain.policies.PolicyModeOverrides.Unoverridable
import com.normation.rudder.reports.execution.AgentRunWithNodeConfig
import com.normation.rudder.reports.execution.RoReportsExecutionRepository
import com.normation.rudder.repository.json.DataExtractor.OptionnalJson
import com.normation.rudder.rest.RestExtractorService
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.web.components.DateFormaterService
import com.typesafe.config.ConfigRenderOptions
import net.liftweb.common.Box
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import net.liftweb.http.JsonResponse
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.http.rest.RestHelper
import net.liftweb.json.JsonAST.JArray
import net.liftweb.json.JsonAST.JField
import net.liftweb.json.JsonAST.JNothing
import net.liftweb.json.JsonAST.JObject
import net.liftweb.json.JsonAST.JString
import net.liftweb.json.parse
import zio.ZIO

class NodeDetailsAPI (
  nodeInfoService: NodeInfoService
  , restExtractor : RestExtractorService
  , reportsExecutionRepository: RoReportsExecutionRepository
  , getGlobalMode : () => Box[GlobalPolicyMode]
  , readOnlySoftwareDAO: ReadOnlySoftwareDAO

) extends  RestHelper with  Loggable {


  def serialize(agentRunWithNodeConfig: Option[AgentRunWithNodeConfig], globalPolicyMode: GlobalPolicyMode, nodeInfo : NodeInfo, properties : List[String], softs: List[Software]) = {
    import net.liftweb.json.JsonDSL._

    val (policyMode,explanation) =
      (globalPolicyMode.overridable,nodeInfo.policyMode) match {
        case (Always,Some(mode)) =>
          (mode,"<p>This mode is an override applied to this node. You can change it in the <i><b>node's settings</b></i>.</p>")
        case (Always,None) =>
          val expl = """<p>This mode is the globally defined default. You can change it in <i><b>settings</b></i>.</p><p>You can also override it on this node in the <i><b>node's settings</b></i>.</p>"""
          (globalPolicyMode.mode, expl)
        case (Unoverridable,_) =>
          (globalPolicyMode.mode, "<p>This mode is the globally defined default. You can change it in <i><b>Settings</b></i>.</p>")
      }
    (  ("name" -> nodeInfo.hostname)
      ~  ("policyServerId" -> nodeInfo.policyServerId.value)
      ~  ("policyMode" -> policyMode.name)
      ~  ("explanation" -> explanation)
      ~  ("kernel" -> nodeInfo.osDetails.kernelVersion.value)
      ~  ("agentVersion" -> nodeInfo.agentsName.headOption.flatMap(_.version.map(_.value)))
      ~  ("id" -> nodeInfo.id.value)
      ~  ("ram" -> nodeInfo.ram.map(_.toStringMo()))
      ~  ("machineType" -> nodeInfo.machine.map(_.machineType.toString))
      ~  ("os" -> nodeInfo.osDetails.fullName)
      ~  ("state" -> nodeInfo.state.name)
      ~  ("ipAddresses" -> nodeInfo.ips.filter(ip => ip != "127.0.0.1" && ip != "0:0:0:0:0:0:0:1"))
      ~  ("lastRun" -> agentRunWithNodeConfig.map(d => DateFormaterService.getDisplayDate(d.agentRunId.date)).getOrElse("Never"))
      ~  ("software" -> JObject(softs.toList.map(s => JField(s.name.getOrElse(""), JString(s.version.map(_.value).getOrElse("N/A"))))))
      ~  ("property" -> JObject(nodeInfo.properties.filter(p => properties.contains(p.name)).map(p => JField(p.name, parse(p.value.render(ConfigRenderOptions.concise()) ) )) ))
      )
  }
  def requestDispatch: PartialFunction[Req, () => Box[LiftResponse]] = {
    case Post("software" :: software :: Nil , req) =>
      import com.normation.box._

      val n1 = System.currentTimeMillis
      for {

        optNodeIds <- req.json.flatMap(restExtractor.extractNodeIdsFromJson)

        nodes <- optNodeIds match {
          case None => nodeInfoService.getAll()
          case Some(nodeIds) => com.normation.utils.Control.sequence(nodeIds)( nodeInfoService.getNodeInfo(_).map(_.map(n => (n.id, n)))).map(_.flatten.toMap)
        }
        softs <- readOnlySoftwareDAO.getNodesbySofwareName(software).toBox.map(_.toMap)
      } yield {
        JsonResponse(JObject(nodes.keySet.toList.flatMap(id => softs.get(id).flatMap(_.version.map(v => JField(id.value, JString(v.value)))))))

      }

    case Post("property" :: property :: Nil , req) =>
      import com.normation.box._

      val n1 = System.currentTimeMillis
      for {

        optNodeIds <- req.json.flatMap(restExtractor.extractNodeIdsFromJson)

        nodes <- optNodeIds match {
          case None => nodeInfoService.getAll()
          case Some(nodeIds) => com.normation.utils.Control.sequence(nodeIds)( nodeInfoService.getNodeInfo(_).map(_.map(n => (n.id, n)))).map(_.flatten.toMap)
        }
      } yield {
        JsonResponse(JObject(nodes.flatMap{ case (id,nodeInfo) => nodeInfo.properties.find(_.name == property).map( p => JField(id.value, JString(p.value.render())))}.toList))

      }
    case Post(Nil, req) =>
      import com.normation.box._

      val n1 = System.currentTimeMillis

      for {

        optNodeIds <- req.json.flatMap(j => OptionnalJson.extractJsonListString(j, "nodeIds",( values => Full(values.map(NodeId(_))))))
        _ = println(optNodeIds)
        _ = println(restExtractor.extractNodeIdsFromJson(req.json.getOrElse(JNothing)))
        _ = println(req.json)
        nodes <- optNodeIds match {
          case None => nodeInfoService.getAll()
          case Some(nodeIds) => com.normation.utils.Control.sequence(nodeIds)( nodeInfoService.getNodeInfo(_).map(_.map(n => (n.id, n)))).map(_.flatten.toMap)
        }
        n2 = System.currentTimeMillis
        _ = println(s"Getting node infos: ${n2 - n1}ms")
        runs <- reportsExecutionRepository.getNodesLastRun(nodes.keySet)
        n3 = System.currentTimeMillis
        _ = println(s"Getting run infos: ${n3 - n2}ms")
        globalMode <- getGlobalMode()
        n4 = System.currentTimeMillis
        _ = println(s"Getting global mode: ${n4 - n3}ms")
        softToLookAfter = req.params.getOrElse("software", Nil)
        softs <-
          ZIO.foreach(softToLookAfter) {
            soft => readOnlySoftwareDAO.getNodesbySofwareName(soft)
          }.toBox.map(_.flatten.groupMap(_._1)(_._2))
        n5 = System.currentTimeMillis
        _ = println(s"response: ${n5 - n4}ms")
      } yield {

        val res = JsonResponse(JArray(nodes.values.toList.map(n => serialize(runs.get(n.id).flatten,globalMode,n, req.params.get("properties").getOrElse(Nil), softs.get(n.id).getOrElse(Nil)))))

        val n6 = System.currentTimeMillis
        println(s"response: ${n6 - n5}ms")
        res
      }
  }


  serve("secure" / "api" / "nodeDetails" prefix requestDispatch)
}
