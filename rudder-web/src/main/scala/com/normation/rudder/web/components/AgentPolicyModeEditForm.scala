package com.normation.rudder.web.components

import net.liftweb.http.DispatchSnippet
import net.liftweb.common._
import net.liftweb.http.{SHtml,S}
import scala.xml._
import net.liftweb.http.js._
import JsCmds._
import JE._
import net.liftweb.util.Helpers
import net.liftweb.util.Helpers._
import net.liftweb.http.Templates
import com.normation.rudder.web.ChooseTemplate

class AgentPolicyModeEditForm extends DispatchSnippet with Loggable  {

  // Html template
  def agentPolicyModeTemplate = ChooseTemplate(
      List("templates-hidden", "components", "ComponentAgentPolicyMode")
    , "agentpolicymode-form"
  )

  def dispatch = {
    case "cfagentPolicyModeConfiguration" => (xml) => cfagentPolicyModeConfiguration
  }

  def cfagentPolicyModeConfiguration = {
    agentPolicyModeTemplate ++ Script(OnLoad(JsRaw("angular.bootstrap('#auditMode', ['auditmode']);")))
  }
}
