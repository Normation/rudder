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

import com.normation.inventory.ldap.core.InventoryDit
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.queries.Query
import com.normation.rudder.domain.policies.{RuleTarget, Rule}
import com.normation.rudder.domain.nodes.{NodeInfo, NodeGroup}
import com.normation.rudder.services.nodes._
import com.normation.rudder.web.services.ReportDisplayer
import com.normation.rudder.web.services.DisplayNode
import com.normation.rudder.web.model.JsTreeNode
import com.normation.rudder.web.components._
import com.normation.rudder.web.components.popup.CreateCategoryOrGroupPopup
import com.normation.rudder.web.components.ShowNodeDetailsFromNode
import com.normation.rudder.web.components.SearchNodeComponent
import com.normation.utils.Control.sequence
import bootstrap.liftweb.LiftSpringApplicationContext.inject
import scala.xml._
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.util._
import Helpers._
import net.liftweb.http.js._
import JsCmds._
import JE._
import net.liftweb.widgets.autocomplete._
import com.normation.exceptions.TechnicalException
import com.normation.inventory.ldap.core.LDAPConstants.OC_NODE
import com.normation.rudder.domain.queries.Or
import com.normation.rudder.services.queries.CmdbQueryParser

//////////////////////////////////////////////////////////////////////
//        Actual snippet implementation
//////////////////////////////////////////////////////////////////////

class SearchNodes extends StatefulSnippet with Loggable {
  val lock = new Object
  private[this] val quickSearchService = inject[QuickSearchService]
  private[this] val queryParser = inject[CmdbQueryParser]

  //the popup component to create the group
  private[this] val creationPopup = new LocalSnippet[CreateCategoryOrGroupPopup]

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
             ),
            onSuccessCategory= { _ => Noop },
            onSuccessGroup = { (node:NodeGroup) => RedirectTo("""secure/nodeManager/groups#{"groupId":"%s"}""".format(node.id.value)) }
         )))
  }
  
  val searchNodeComponent = new LocalSnippet[SearchNodeComponent] 
  
  private[this] def setNodeGroupCategoryForm(query:Option[Query]) : SearchNodeComponent = {    
     val sc = new SearchNodeComponent(
        "htmlIdCategory"
      , query
      , srvList
      , () => Noop
      , showNodeDetails
    )
    searchNodeComponent.set(Full(sc))
    sc
  }

  var srvList : Box[Seq[NodeInfo]] = Empty
    
  setNodeGroupCategoryForm(None)
  
  // The portlet for the server detail
  private def serverPortletPath = List("templates-hidden", "server", "server_details")
  private def serverPortletTemplateFile() =  Templates(serverPortletPath) match {
    case Empty | Failure(_,_,_) => 
      throw new TechnicalException("Template for node details not found. I was looking for %s.html".format(serverPortletPath.mkString("/")))
    case Full(n) => n
  }
  private def serverPortletTemplate = chooseTemplate("server","portlet",serverPortletTemplateFile)
  private def serverDetailsTemplate = chooseTemplate("detail","server",serverPortletTemplateFile)
  private def searchNodes = chooseTemplate("query","SearchNodes",serverPortletTemplateFile)
  
  // the container for the server selected
//  var currentSelectedNode = Option.empty[NodeInfo]
  
  
  var dispatch : DispatchIt = {
    case "showQuery" => searchNodeComponent.is match {
      case Full(component) => { _ => component.buildQuery }
      case _ => { _ => <div>The component is not set</div><div></div> }
    }
    case "head" => head _
    case "createGroup" => createGroup _
    case "serverPorlet" => serverPortlet _
  }


  var activateSubmitButton = true
  
  def head(html:NodeSeq) : NodeSeq = {
    import net.liftweb.json._
    import net.liftweb.json.JsonDSL._
    
    { <head>
      <script type="text/javascript" src="/javascript/jquery/ui/jquery.ui.datepicker.js"></script>
      <script type="text/javascript" src="/javascript/jquery/ui/i18n/jquery.ui.datepicker-fr.js"></script>
      {Script(OnLoad(parseJsArg()))}
    </head> } ++ ShowNodeDetailsFromNode.staticInit
  }
  
  
  /**
   * Display the server portlet 
   * @param html
   * @return
   */
  def serverPortlet(html:NodeSeq) : NodeSeq = {
    def buildQuery(current: String, limit: Int): Seq[String] = {
      quickSearchService.lookup(current,20) match {
        case Full(seq) => seq.map(nodeInfo => "%s [%s]".format(nodeInfo.hostname, nodeInfo.id.value))
        case e:EmptyBox => {
          logger.error("Error in quicksearch",e)
          Seq()
        }
      }
    }
    
    /*
     * parse the return of the text show in autocomplete, build
     * in buildQuery ( hostname name [uuuid] )
     */
    def parse(s:String) : JsCmd = {
      val regex = """.+\[(.+)\]""".r
      s match {
        case regex(id) => 
          SetHtml("serverDetails", (new ShowNodeDetailsFromNode(NodeId(id)).display())) &
          updateLocationHash(id)
        case _ => 
          Alert("No server was selected")
      }
    }
    
    bind("server", serverPortletTemplate,
        "quicksearch" -> AutoCompleteAutoSubmit("", buildQuery _, { s:String => parse(s) }, 
            ("style" -> "width:300px"), 
            ("placeholder" -> "Search")),
        /*"quicksearchSubmit" -> SHtml.ajaxSubmit("OK", { () => 
           nodeId match {
             case Some(id) => SetHtml("serverDetails", (new ShowNodeDetailsFromNode(id).display) )
             case None => Alert("No server was selected")
           }
        } ),*/
         "details" -> NodeSeq.Empty
     )
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
  private[this] def parseJsArg(): JsCmd = {
    def displayDetails(nodeId:String) = {
      SetHtml("serverDetails", (new ShowNodeDetailsFromNode(new NodeId(nodeId))).display())
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
    
    JsRaw("""
        var hash = null;
        try {
          hash = JSON.parse(window.location.hash.substring(1));
        } catch(e) {
          hash = {}
        }
        if( hash.nodeId != null && hash.nodeId.length > 0) { 
          %s;
        }
        if( hash.query != null && JSON.stringify(hash.query).length > 0) { 
          %s;
        }
    """.format(
        SHtml.ajaxCall(JsVar("hash","nodeId"), displayDetails _ )._2.toJsCmd
      , SHtml.ajaxCall(JsRaw("JSON.stringify(hash.query)"), executeQuery _ )._2.toJsCmd
    ))
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
    setCreationPopup(searchNodeComponent.is.open_!.getQuery(),
                     searchNodeComponent.is.open_!.getSrvList() )
    //update UI
    SetHtml("createGroupContainer", createPopup) &
    JsRaw( """ createPopup("createGroupPopup",300,400) """)

  }
  
  
  
  /**
   * This method tale the values from the JS call (variable in the td), and display the node details from it
   * @param s
   * @return
   */
  private def showNodeDetails(s:String) : JsCmd = {
    val arr = s.split("\\|")
    val nodeId = arr(1)
    SetHtml("serverDetails", (new ShowNodeDetailsFromNode(new NodeId(nodeId))).display()) &
    updateLocationHash(nodeId)
  }
  
  private def updateLocationHash(nodeId:String) =
    JsRaw("""this.window.location.hash = "#" + JSON.stringify({'nodeId':'%s'})""".format(nodeId)) 
}

