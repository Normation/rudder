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
import com.normation.rudder.domain.policies.Tags
import com.normation.rudder.domain.policies.JsonTagSerialisation
import com.normation.rudder.repository.json.DataExtractor.CompleteJson

class TagsEditForm(
    tags : Tags
) extends Loggable {

  def templatePath = List("templates-hidden", "components", "ComponentTags")
  def template() =  Templates(templatePath) match {
     case Empty | Failure(_,_,_) =>
       sys.error("Template for Tags not found. I was looking for %s.html"
           .format(templatePath.mkString("/")))
     case Full(n) => n
  }
  def tagsTemplate = chooseTemplate("tags", "form", template)

  def editTagsTemplate = chooseTemplate("tags", "editform", template)

  val jsTags = JsonTagSerialisation.serializeTags(tags)

  def parseResult(s : String) : Box[Tags] = CompleteJson.unserializeTags(s)

  def tagsForm(controllerId:String, appId : String, update : Box[Tags] => Unit, isRule  : Boolean) = {

    val valueInput = SHtml.textarea("", {s => update(parseResult(s))}, ("ng-model","result"), ("ng-hide", "true") )
    val css: CssSel =
      s"#${controllerId} *+" #> valueInput &
      s"#${controllerId} #tagForm" #> editTagsTemplate

    css(tagTemplate(controllerId, appId, true,isRule))
  }

  def viewTags(controllerId:String, appId : String, isRule  : Boolean) = {
    tagTemplate(controllerId, appId, false, isRule)
  }

  private[this] def tagTemplate(controllerId:String, appId : String, isEditForm  : Boolean, isRule  : Boolean) = {

    val filterController = if (isRule) {
      "filterTagRuleCtrl"
    } else {
      "filterTagDirectiveCtrl"
    }
    val css: CssSel =
      "#tagsController [id]" #> (controllerId) &
      "#tagApp [id]" #> (appId)

    css(tagsTemplate) ++ Script(OnLoad(JsRaw(s"""
      angular.bootstrap('#${appId}', ['tags']);
      var scope = angular.element($$("#${controllerId}")).scope();
      scope.$$apply(function(){
        scope.init(  ${jsTags}, "${filterController}" ,  ${isEditForm});
      });
    """)))
  }
}
