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

class AgentPolicyModeEditForm extends DispatchSnippet with Loggable  {

  // Html template
  def templatePath = List("templates-hidden", "components", "ComponentAgentPolicyMode")
  def template() =  Templates(templatePath) match {
     case Empty | Failure(_,_,_) =>
       sys.error("Template for Agent Policy Mode configuration not found. I was looking for %s.html"
           .format(templatePath.mkString("/")))
     case Full(n) => n
  }
  def agentPolicyModeTemplate = chooseTemplate("agentpolicymode", "form", template)

  def dispatch = {
    case "cfagentPolicyModeConfiguration" => (xml) => cfagentPolicyModeConfiguration
  }

  def cfagentPolicyModeConfiguration = {
    agentPolicyModeTemplate ++ Script(OnLoad(JsRaw("angular.bootstrap('#auditMode', ['auditmode']);")))
  }
}
