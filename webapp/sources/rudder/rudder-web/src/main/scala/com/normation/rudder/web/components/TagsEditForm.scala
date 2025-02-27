package com.normation.rudder.web.components

import com.normation.box.*
import com.normation.rudder.domain.policies.Tags
import com.normation.rudder.web.ChooseTemplate
import net.liftweb.common.*
import net.liftweb.http.SHtml
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import net.liftweb.util.CssSel
import net.liftweb.util.Helpers.*
import org.apache.commons.text.StringEscapeUtils
import scala.xml.NodeSeq
import zio.json.*

class TagsEditForm(tags: Tags, objectId: String) extends Loggable {

  val templatePath: List[String] = List("templates-hidden", "components", "ComponentTags")
  def tagsTemplate: NodeSeq      = ChooseTemplate(templatePath, "tag-form")

  val jsTags: String = tags.toJson

  def parseResult(s: String): Box[Tags] = s.fromJson[Tags].toBox

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

    css(tagTemplate(controllerId, appId, isEditForm = true, isRule = isRule))
  }

  def viewTags(controllerId: String, appId: String, isRule: Boolean): NodeSeq = {
    tagTemplate(controllerId, appId, isEditForm = false, isRule = isRule)
  }

  private def tagTemplate(controllerId: String, appId: String, isEditForm: Boolean, isRule: Boolean): NodeSeq = {

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
                                                |  , objectId       : "${StringEscapeUtils.escapeEcmaScript(objectId)}"
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
