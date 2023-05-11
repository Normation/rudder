package com.normation.rudder.web.components

import com.normation.inventory.domain.NodeId
import com.normation.rudder.web.ChooseTemplate
import net.liftweb.common._
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.js.JE._
import net.liftweb.http.js.JsCmds._
import scala.xml.NodeSeq

class AgentPolicyModeEditForm extends DispatchSnippet with Loggable {

  // Html template
  def agentPolicyModeTemplate: NodeSeq = ChooseTemplate(
    List("templates-hidden", "components", "ComponentAgentPolicyMode"),
    "agentpolicymode-form"
  )

  def dispatch = { case "cfagentPolicyModeConfiguration" => (xml) => cfagentPolicyModeConfiguration(None) }

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
         |  , hasWriteRights : hasWriteRights
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
         |    $$('.bs-tooltip').bsTooltip();
         |  }, 400);
         |});
         |""".stripMargin
    }
    agentPolicyModeTemplate ++ Script(OnLoad(JsRaw(js)))
  }
}
