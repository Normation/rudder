/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This file is part of Rudder.
*
* Rudder is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU General Public License version 3, the copyright holders add
* the following Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
* Public License version 3, when you create a Related Module, this
* Related Module is not considered as a part of the work and may be
* distributed under the license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* Rudder is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

*
*************************************************************************************
*/

package com.normation.rudder.web.snippet.node

import scala.xml.NodeSeq
import scala.xml.NodeSeq.seqToNodeSeq
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.queries.Query
import com.normation.rudder.web.components.SearchNodeComponent
import com.normation.rudder.web.components.ShowNodeDetailsFromNode
import com.normation.rudder.web.components.popup.CreateCategoryOrGroupPopup
import bootstrap.liftweb.RudderConfig
import com.normation.rudder.domain.policies.NonGroupRuleTarget
import net.liftweb.common._
import net.liftweb.http.LocalSnippet
import net.liftweb.http.SHtml
import net.liftweb.http.SHtml.ElemAttr.pairToBasic
import net.liftweb.http.StatefulSnippet
import net.liftweb.http.js.JE.JsRaw
import net.liftweb.http.js.JE.JsVar
import net.liftweb.http.js.JsCmd
import net.liftweb.http.js.JsCmds._

import com.normation.box._

/**
 *
 * Snippet that handle the "searchNodes" page.
 *
 * Two main feature:
 * - diplay details of a node
 * - search for nodes based on a query
 *
 * Node details are ALWAYS load via Ajax (see parseHashtag method).
 * Hashtag modification are detected with the hashchange" events,
 * supported since ie8/ff3.6/chrome5/safari5 - as to say,
 * immemorial times (see http://caniuse.com/hashchange for details)
 *
 */

class SearchNodes extends StatefulSnippet with Loggable {

  private[this] val queryParser = RudderConfig.cmdbQueryParser
  private[this] val getFullGroupLibrary = RudderConfig.roNodeGroupRepository.getFullGroupLibrary _
  private[this] val linkUtil            = RudderConfig.linkUtil

  //the popup component to create the group
  private[this] val creationPopup = new LocalSnippet[CreateCategoryOrGroupPopup]

  private[this] val groupLibrary = getFullGroupLibrary().toBox match {
    case Full(x) => x
    case eb:EmptyBox =>
      val e = eb ?~! "Major error: can not get the node group library"
      logger.error(e.messageChain)
      throw new Exception(e.messageChain)
  }

  val searchNodeComponent = new LocalSnippet[SearchNodeComponent]

  var srvList : Box[Seq[NodeInfo]] = Empty

  setSearchComponent(None)

  var dispatch : DispatchIt = {
    case "showQuery" => searchNodeComponent.get match {
      case Full(component) => { _ => queryForm(component) }
      case _ => { _ => <div>The component is not set</div><div></div> }
    }
    case "head" => head _
    case "createGroup" => createGroup _
  }

  var activateSubmitButton = true

  def head(html:NodeSeq) : NodeSeq = {

    //add a function name to force reparse hashtag for other js elt of the page

    { <head>
      {Script(
         JsRaw(s"function forceParseHashtag() { ${parseHashtag().toJsCmd } }") &
         OnLoad(JsRaw("forceParseHashtag()"))
     )}
    </head> }
  }

  private[this] def setCreationPopup(query : Option[Query], serverList : Box[Seq[NodeInfo]]) : Unit = {
      creationPopup.set(Full(new CreateCategoryOrGroupPopup(
          // create a totally invalid group
          Some(new NodeGroup(
              null,
              null,
              null,
              query,
              true,
              serverList.openOr(Seq[NodeInfo]()).map(_.id).toSet,
              true,
              false
              )
          )
        , rootCategory     = groupLibrary
        , selectedCategory = None
        , onSuccessCategory= { _ => Noop }
        , onSuccessGroup   = { (group:Either[NonGroupRuleTarget, NodeGroup], _) => group.fold(t => linkUtil.redirectToTargteLink(t), g => linkUtil.redirectToGroupLink(g.id)) }
      )))
  }

  private[this] def setSearchComponent(query:Option[Query]) : SearchNodeComponent = {
    def showNodeDetails(nodeId:String, displayCompliance : Boolean) : JsCmd = {
      updateLocationHash(nodeId, displayCompliance) &
      JsRaw("""scrollToElement("serverDetails", ".rudder_col");""".format(nodeId))
    }

    val sc = new SearchNodeComponent(
        "htmlIdCategory"
      , query
      , srvList
      , () => Noop
      , Some(showNodeDetails)
      , onSearchCallback = updateQueryHash
      , groupPage = false
    )
    searchNodeComponent.set(Full(sc))
    sc
  }

  def createGroup(html:NodeSeq) : NodeSeq = {
      SHtml.ajaxButton("Create node group from this query", {
       () =>   showPopup()  },
       ("class", "btn btn-success new-icon space-top"))
  }

  def queryForm(sc : SearchNodeComponent) = {
    SHtml.ajaxForm(sc.buildQuery)
  }

  /**
   * If a query is passed as argument, try to dejsoniffy-it, in a best effort
   * way - just don't take of errors.
   *
   * We want to look for #{ "nodeId":"XXXXXXXXXXXX" }
   */
  private[this] def parseHashtag(): JsCmd = {
    def displayDetails(jsonData: String) = {
      import net.liftweb.json._
      val json = parse(jsonData)
      json \ "nodeId" match {
        case JString(nodeId) =>
          val displayCompliance = json \ "displayCompliance" match {
            case JBool(displayCompliance) => displayCompliance
            case _ => false
          }
          val nodeDetails = new ShowNodeDetailsFromNode(new NodeId(nodeId), groupLibrary).display(false, displayCompliance)
          SetHtml("serverDetails", nodeDetails)
        case _ =>
          SetHtml("serverDetails", NodeSeq.Empty)
      }
    }

    def executeQuery(query:String) : JsCmd = {
      val q = queryParser(query)
      val sc = setSearchComponent(q)

      q match {
        case e:EmptyBox =>
          val fail = e ?~! s"Could not parse ${query} as a valid query"
          logger.error(fail.messageChain)
          Noop
        case Full(q)    =>
          Replace("SearchNodes", queryForm(sc)) &
          JsRaw("$('#SubmitSearch').click();")
      }
    }

    JsRaw(s"""parseSearchHash(
        function(x) { ${SHtml.ajaxCall(JsVar("x"), displayDetails _ )._2.toJsCmd} }
      , function(x) { ${SHtml.ajaxCall(JsVar("x") , executeQuery   _ )._2.toJsCmd} }
    )""")
  }

  /**
   * Create the popup
   */
  private[this] def createPopup : NodeSeq = {
    creationPopup.get match {
      case Failure(m,_,_) =>  <span class="error">Error: {m}</span>
      case Empty => <div>The component is not set</div>
      case Full(popup) => popup.popupContent()
    }
  }

  private[this] def showPopup() : JsCmd = {
    searchNodeComponent.get match {
      case Full(r) => setCreationPopup(r.getQuery, r.getSrvList)
        //update UI
        SetHtml("createGroupContainer", createPopup) &
        JsRaw( """ createPopup("createGroupPopup") """)

      case eb:EmptyBox => Alert("Error when trying to retrieve the resquest, please try again")
    }
  }
  private def updateQueryHash(button: Boolean, query: Option[Query]): JsCmd = {
    query match {
      case Some(q) =>
        JsRaw(s"updateHashString('query', ${q.toJSONString})")
      case None => Noop
    }

  }

  private def updateLocationHash(nodeId:String, displayCompliance : Boolean) = {
    //JsRaw("""this.window.location.hash = "#" + JSON.stringify({'nodeId':'%s'})""".format(nodeId))
    JsRaw(s"updateHashString('nodeId', '${nodeId}')") &
    JsRaw(s"updateHashString('displayCompliance', ${displayCompliance})") &
    SetHtml("serverDetails", (new ShowNodeDetailsFromNode(new NodeId(nodeId), groupLibrary)).display(false,displayCompliance))
  }
}
