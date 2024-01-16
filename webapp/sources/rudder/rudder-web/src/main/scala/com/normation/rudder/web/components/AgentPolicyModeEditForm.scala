package com.normation.rudder.web.components

import com.normation.inventory.domain.NodeId
import com.normation.rudder.web.ChooseTemplate
import net.liftweb.common.*
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import scala.xml.NodeSeq

class AgentPolicyModeEditForm extends DispatchSnippet with Loggable {

  // Html template
  def agentPolicyModeTemplate: NodeSeq = ChooseTemplate(
    List("templates-hidden", "components", "ComponentAgentPolicyMode"),
    "agentpolicymode-form"
  )

  def dispatch: PartialFunction[String, NodeSeq => NodeSeq] = {
    case "cfagentPolicyModeConfiguration" => (xml) => cfagentPolicyModeConfiguration(None)
  }

  /*
   * If a node ID is given, then it's a node only detail. If none, then it's the global
   * configuration.
   */
  def cfagentPolicyModeConfiguration(optNodeId: Option[NodeId]): NodeSeq = {
    val jsid = optNodeId match {
      case None     => ""
      case Some(id) => id.value
    }
    val js   = s"""angular.bootstrap('#auditMode', ['auditmode']);
                |var scope = angular.element($$("#auditMode")).scope();
                |scope.$$apply(function(){
                |scope.init('${jsid}');
                |});""".stripMargin

    agentPolicyModeTemplate ++ Script(OnLoad(JsRaw(js)))
  }
}
