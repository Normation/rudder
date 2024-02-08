package com.normation.rudder.web.components

import com.normation.rudder.domain.policies.JsonTagSerialisation
import com.normation.rudder.domain.policies.Tags
import com.normation.rudder.repository.json.DataExtractor.CompleteJson
import com.normation.rudder.web.ChooseTemplate
import net.liftweb.common._
import net.liftweb.http.SHtml
import net.liftweb.http.js.JE._
import net.liftweb.http.js.JsCmds._
import net.liftweb.util.CssSel
import net.liftweb.util.Helpers._
import scala.xml.NodeSeq

class TagsEditForm(tags: Tags, objectId: String) extends Loggable {

  val templatePath: List[String] = List("templates-hidden", "components", "ComponentTags")
  def tagsTemplate: NodeSeq      = ChooseTemplate(templatePath, "tag-form")

  val jsTags: String = net.liftweb.json.compactRender(JsonTagSerialisation.serializeTags(tags))

  def parseResult(s: String): Box[Tags] = CompleteJson.unserializeTags(s)

  def tagsForm(controllerId: String, appId: String, update: Box[Tags] => Unit, isRule: Boolean): NodeSeq = {

    // TODO: THIS MUST BE CHANGE WHEN TAGS APP IS REWRITTEN
    val valueInput = SHtml.textarea(
      "",
      s => update(parseResult(s)),
      ("id", "tags-result")
    )
    val css: CssSel = {
      s"#${controllerId} *+" #> valueInput
    }

    css(tagTemplate(controllerId, appId, true, isRule))
  }

  def viewTags(controllerId: String, appId: String, isRule: Boolean): NodeSeq = {
    tagTemplate(controllerId, appId, false, isRule)
  }

  private[this] def tagTemplate(controllerId: String, appId: String, isEditForm: Boolean, isRule: Boolean): NodeSeq = {

    val (filterId, objectType) = if (isRule) {
      ("showFiltersRules", "rule")
    } else {
      ("directiveFilter", "directive")
    }
    val css: CssSel = {
      "#tagsController [id]" #> (controllerId) &
      "#tagApp [id]" #> (appId)
    }

    css(tagsTemplate) ++ Script(OnLoad(JsRaw(s"""
      var main = document.getElementById("tags-app")
                                                |var initValues = {
                                                |    contextPath    : contextPath
                                                |  , hasWriteRights : hasWriteRights
                                                |  , tags           : ${jsTags}
                                                |  , filterId       : "${filterId}"
                                                |  , isEditForm     : ${isEditForm}
                                                |  , objectType     : "${objectType}"
                                                |  , objectId       : "${objectId}"
                                                |};
                                                |tagsApp = Elm.Tags.init({node: main, flags: initValues});
                                                |tagsApp.ports.updateResult.subscribe(function(result) {
                                                |  $$('#tags-result').val(result);
                                                |});
                                                |tagsApp.ports.addToFilters.subscribe(function(tag) {
                                                |  if (typeof filterApp === "undefined") return false;
                                                |
                                                |  filterApp.ports.addToFilter.send(tag);
                                                |});
    """.stripMargin)))
  }
}
