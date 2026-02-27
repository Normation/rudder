package com.normation.rudder.web.components

import com.normation.inventory.domain.NodeId
import com.normation.rudder.AuthorizationType
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.users.CurrentUser
import com.normation.rudder.web.ChooseTemplate
import com.normation.rudder.web.snippet.WithNonce
import net.liftweb.common.*
import net.liftweb.http.SecureDispatchSnippet
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import scala.xml.NodeSeq

class AgentPolicyModeEditForm extends SecureDispatchSnippet with Loggable {

  // Html template
  def agentPolicyModeTemplate: NodeSeq = ChooseTemplate(
    List("templates-hidden", "components", "ComponentAgentPolicyMode"),
    "agentpolicymode-form"
  )

  def secureDispatch: QueryContext ?=> PartialFunction[String, NodeSeq => NodeSeq] = {
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
    val js   = {
      s"""
         |var main = document.getElementById("agentpolicymode-app")
         |var initValues = {
         |    contextPath    : contextPath
         |  , hasWriteRights : ${CurrentUser.checkRights(AuthorizationType.Node.Write)}
         |  , nodeId         : "${jsid}"
         |};
         |
         |var app = Elm.Agentpolicymode.init({node: main, flags: initValues});
         |app.ports.successNotification.subscribe(function(str) {
         |  createSuccessNotification(str)
         |});
         |app.ports.errorNotification.subscribe(function(str) {
         |  createErrorNotification(str)
         |});
         |// Initialize tooltips
         |app.ports.initTooltips.subscribe(function(msg) {
         |  setTimeout(function(){
         |    initBsTooltips();
         |  }, 400);
         |});
         |""".stripMargin
    }
    agentPolicyModeTemplate ++ WithNonce.scriptWithNonce(Script(OnLoad(JsRaw(js))))
  }
}
