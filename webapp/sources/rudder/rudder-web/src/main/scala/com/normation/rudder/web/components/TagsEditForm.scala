package com.normation.rudder.web.components

import net.liftweb.common._
import net.liftweb.http.SHtml
import net.liftweb.http.js._
import JsCmds._
import JE._
import net.liftweb.util.Helpers._
import net.liftweb.util.CssSel
import com.normation.rudder.domain.policies.Tags
import com.normation.rudder.domain.policies.JsonTagSerialisation
import com.normation.rudder.repository.json.DataExtractor.CompleteJson
import com.normation.rudder.web.ChooseTemplate

class TagsEditForm(tags : Tags, objectId : String) extends Loggable {

  val templatePath = List("templates-hidden", "components", "ComponentTags")
  def tagsTemplate = ChooseTemplate(templatePath, "tags-form")

  def editTagsTemplate = ChooseTemplate(templatePath, "tags-editform")

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

    val filterId = if (isRule) {
      "showFiltersRules"
    } else {
      "directiveFilter"
    }
    val css: CssSel =
      "#tagsController [id]" #> (controllerId) &
      "#tagApp [id]" #> (appId)

    css(tagsTemplate) ++ Script(OnLoad(JsRaw(s"""
      if(!angular.element('#${appId}').scope()){
        angular.bootstrap('#${appId}', ['tags']);
      }
      var scope = angular.element($$("#${controllerId}")).scope();
      scope.$$apply(function(){
        scope.init(  ${jsTags}, "${filterId}" ,  ${isEditForm}, ${isRule}, "${objectId}");
      });
    """)))
  }
}
