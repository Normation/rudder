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

package com.normation.rudder.web.snippet

import scala.xml.NodeSeq
import com.normation.inventory.domain.NodeId
import com.normation.rudder.web.components.AutoCompleteAutoSubmit
import com.normation.rudder.web.model.JsInitContextLinkUtil
import bootstrap.liftweb.RudderConfig
import net.liftweb.common._
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.js.JsCmd
import net.liftweb.http.js.JE.JsRaw
import net.liftweb.util.Helpers.strToSuperArrowAssoc
import net.liftweb.http.js.JsCmds._
import net.liftweb.http.S

/**
 * This snippet allow to display the node "quick search" field.
 * It autocompletes on node hostname or id, and redirect
 * to the search node page to display node details.
 */
class QuickSearchNode extends DispatchSnippet with Loggable {

  private[this] val quickSearchService = RudderConfig.quickSearchService

  def dispatch = {
    case "render" => chooseSearch
  }

  def chooseSearch(html: NodeSeq): NodeSeq = {

    if(RudderConfig.RUDDER_FEATURE_ENABLE_QUICKSEARCH) {
    (
      <div ng-app="quicksearch" id="quicksearch" ng-controller="QuicksearchCtrl" class="navbar-form navbar-left">
        <div angucomplete-ie8=""
             placeholder="Search anything"
             maxlength="100"
             pause="500"
             selected-object="selectedObject"
             remote-url={s"${S.contextPath}/secure/api/quicksearch/"}
             remote-url-data-field="data"
             title-field="name"
             description-field="desc"
             minlength="3"
             input-class="form-control ac_input"
             match-class="highlight"
        ></div>
      </div>
    )
    } else {
      quickSearch(html)
    }
  }

  def quickSearch(html:NodeSeq) : NodeSeq = {
    def buildQuery(current: String, limit: Int): Seq[String] = {
      quickSearchService.lookup(current,100) match {
        case Full(seq) => seq.map(nodeInfo => "%s [%s]".format(nodeInfo.hostname, nodeInfo.id.value))
        case e:EmptyBox => {
          logger.error("Error in quick search request",e)
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
          JsInitContextLinkUtil.redirectToNodeLink(NodeId(id)) &
          Run(s"if(window.location.href.indexOf('nodeManager/searchNodes') > -1) { forceParseHashtag(); }")
        case _ =>
          Alert("No node was selected")
      }
    }


    //actual XML returned by the method
    <lift:form class="navbar-form navbar-left">
      <div class="form-group">
      {AutoCompleteAutoSubmit (
          ""
        , buildQuery _
        , { s:String => parse(s) }
          //json option, see: https://code.google.com/p/jquery-autocomplete/wiki/Options
        , ("resultsClass", "'topQuickSearchResults ac_results '") :: Nil
        , ("placeholder" -> "Search nodes")
        ,  ("class" -> "form-control")
      )}
      </div>
    </lift:form>

  }
}
