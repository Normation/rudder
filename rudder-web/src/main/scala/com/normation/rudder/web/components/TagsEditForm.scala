package com.normation.rudder.web.components

import net.liftweb.http.DispatchSnippet
import net.liftweb.common._
import net.liftweb.http.{SHtml,S}
import scala.xml._
import net.liftweb.http.js._
import JsCmds._
import JE._
import net.liftweb.util.Helpers._
import net.liftweb.http.Templates
import net.liftweb.util.CssSel

class TagsEditForm(
  tags : JsObj
) extends DispatchSnippet {

  def templatePath = List("templates-hidden", "components", "ComponentTags")
  def template() =  Templates(templatePath) match {
     case Empty | Failure(_,_,_) =>
       sys.error("Template for Tags not found. I was looking for %s.html"
           .format(templatePath.mkString("/")))
     case Full(n) => n
  }
  def tagsTemplate = chooseTemplate("tags", "form", template)

  def dispatch = {
    case "cfTagsDirectiveConfiguration" => (xml) => cfTagsConfiguration("tagsDirectiveCtrl")
    case "cfTagsRuleConfiguration"      => (xml) => cfTagsConfiguration("tagsRuleCtrl")
  }

  def cfTagsConfiguration(controllerID:String) = {
    val css: CssSel = "#tagsController [ng-controller]" #> (controllerID)
    css(tagsTemplate) ++ Script(OnLoad(JsRaw(s"""
      var tags = ${tags};
      var treeId = "#activeTechniquesTree";
      angular.bootstrap('#tagField', ['tags']);
      var scope = angular.element($$("#tagField [ng-controller='${controllerID}']")).scope();
      scope.$$apply(function(){
        scope.init(treeId, tags);
      });
    """)))
  }
}

