/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.rudder.web.snippet.node

import scala.xml.NodeSeq
import scala.xml.NodeSeq.seqToNodeSeq

import com.normation.exceptions.TechnicalException
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.queries.Query
import com.normation.rudder.web.components.SearchNodeComponent
import com.normation.rudder.web.components.ShowNodeDetailsFromNode
import com.normation.rudder.web.components.popup.CreateCategoryOrGroupPopup

import bootstrap.liftweb.RudderConfig
import net.liftweb.common._
import net.liftweb.http.LocalSnippet
import net.liftweb.http.SHtml
import net.liftweb.http.SHtml.ElemAttr.pairToBasic
import net.liftweb.http.StatefulSnippet
import net.liftweb.http.Templates
import net.liftweb.http.js.JE.JsRaw
import net.liftweb.http.js.JE.JsVar
import net.liftweb.http.js.JsCmd
import net.liftweb.http.js.JsCmds._
import net.liftweb.util.Helpers.chooseTemplate


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


object SearchNodes {
  private val serverPortletPath = List("templates-hidden", "server", "server_details")
  private val serverPortletTemplateFile =  Templates(serverPortletPath) match {
    case Empty | Failure(_,_,_) =>
      throw new TechnicalException("Template for node details not found. I was looking for %s.html".format(serverPortletPath.mkString("/")))
    case Full(n) => n
  }
  private val serverDetailsTemplate = chooseTemplate("detail","server",serverPortletTemplateFile)
  private val searchNodes = chooseTemplate("query","SearchNodes",serverPortletTemplateFile)
}

class SearchNodes extends StatefulSnippet with Loggable {

  import SearchNodes._

  private[this] val queryParser = RudderConfig.cmdbQueryParser
  private[this] val getFullGroupLibrary = RudderConfig.roNodeGroupRepository.getFullGroupLibrary _

  //the popup component to create the group
  private[this] val creationPopup = new LocalSnippet[CreateCategoryOrGroupPopup]

  private[this] val groupLibrary = getFullGroupLibrary() match {
    case Full(x) => x
    case eb:EmptyBox =>
      val e = eb ?~! "Major error: can not get the node group library"
      logger.error(e.messageChain)
      throw new Exception(e.messageChain)
  }

  val searchNodeComponent = new LocalSnippet[SearchNodeComponent]


  var srvList : Box[Seq[NodeInfo]] = Empty

  setNodeGroupCategoryForm(None)

  var dispatch : DispatchIt = {
    case "showQuery" => searchNodeComponent.is match {
      case Full(component) => { _ => component.buildQuery }
      case _ => { _ => <div>The component is not set</div><div></div> }
    }
    case "head" => head _
    case "createGroup" => createGroup _
  }

  var activateSubmitButton = true


  def head(html:NodeSeq) : NodeSeq = {
    import net.liftweb.json._
    import net.liftweb.json.JsonDSL._

    { <head>
      {Script(parseHashtag()) ++ Script(OnLoad(JsRaw("""parseHashtag();
          $(window).on('hashchange',function(){ parseHashtag() });
      """)))}
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
        , rootCategory = groupLibrary
        , onSuccessCategory= { _ => Noop }
        , onSuccessGroup = { (node:NodeGroup, _) => RedirectTo("""/secure/nodeManager/groups#{"groupId":"%s"}""".format(node.id.value)) }
      )))
  }

  private[this] def setNodeGroupCategoryForm(query:Option[Query]) : SearchNodeComponent = {
    def showNodeDetails(nodeId:String) : JsCmd = {
      updateLocationHash(nodeId) &
      JsRaw("""scrollToElement("serverDetails");""".format(nodeId))
    }

    val sc = new SearchNodeComponent(
        "htmlIdCategory"
      , query
      , srvList
      , () => Noop
      , Some(showNodeDetails)
      , groupPage = false
    )
    searchNodeComponent.set(Full(sc))
    sc
  }

  def createGroup(html:NodeSeq) : NodeSeq = {
      SHtml.ajaxButton("Create node group from this query", {
       () =>   showPopup()  },
       ("class", "largeButton"))
  }

  /**
   * If a query is passed as argument, try to dejsoniffy-it, in a best effort
   * way - just don't take of errors.
   *
   * We want to look for #{ "ruleId":"XXXXXXXXXXXX" }
   */
  private[this] def parseHashtag(): JsCmd = {
    def displayDetails(nodeId:String) = {
      SetHtml("serverDetails", (new ShowNodeDetailsFromNode(new NodeId(nodeId), groupLibrary)).display())
    }

    def executeQuery(query:String) : JsCmd = {
      val q = queryParser(query)
      val sc = setNodeGroupCategoryForm(q)

      q match {
        case f:Failure =>
          logger.debug(f.messageChain)
          Noop
        case e:EmptyBox => Noop
        case Full(q)    =>
          Replace("SearchNodes", sc.buildQuery()) &
          JsRaw("correctButtons(); $('#SubmitSearch').click();")
      }
    }

    JsRaw(s"""
        var parseHashtag = function() {
          var hash = null;
          try {
            hash = JSON.parse(window.location.hash.substring(1));
          } catch(e) {
            hash = {}
          }
          if( hash.nodeId != null && hash.nodeId.length > 0) {
            ${SHtml.ajaxCall(JsVar("hash","nodeId"), displayDetails _ )._2.toJsCmd}
          }
          if( hash.query != null && JSON.stringify(hash.query).length > 0) {
            ${SHtml.ajaxCall(JsRaw("JSON.stringify(hash.query)"), executeQuery _ )._2.toJsCmd}
          }
        }
    """
    )
  }


  /**
   * Create the popup
   */
  private[this] def createPopup : NodeSeq = {
    creationPopup.is match {
      case Failure(m,_,_) =>  <span class="error">Error: {m}</span>
      case Empty => <div>The component is not set</div>
      case Full(popup) => popup.popupContent()
    }
  }

  private[this] def showPopup() : JsCmd = {
    searchNodeComponent.is match {
      case Full(r) => setCreationPopup(r.getQuery, r.getSrvList)
        //update UI
        SetHtml("createGroupContainer", createPopup) &
        JsRaw( """ createPopup("createGroupPopup") """)

      case eb:EmptyBox => Alert("Error when trying to retrieve the resquest, please try again")
    }
  }

  private def updateLocationHash(nodeId:String) =
    JsRaw("""this.window.location.hash = "#" + JSON.stringify({'nodeId':'%s'})""".format(nodeId))
}

