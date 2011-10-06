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

package com.normation.rudder.web
package snippet

import com.normation.inventory.ldap.core._
import LDAPConstants._
import com.normation.ldap.sdk._
import BuildFilter._

import bootstrap.liftweb.LiftSpringApplicationContext.inject

import com.unboundid.ldap.sdk.{SearchScope,SearchResult}

import scala.collection.mutable.Buffer
import scala.collection.JavaConversions._

//lift std import
import scala.xml._
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.util._
import Helpers._
import net.liftweb.http.js._
import JsCmds._ // For implicits
import JE._
import net.liftweb.http.SHtml._

class LdapTree {
  val ldap = inject[LDAPConnectionProvider]
  val acceptedServersDit = inject[InventoryDit]("acceptedServersDit")
  
  def render(content:NodeSeq) : NodeSeq = {
    /*
     * Mandatory: there is a nasty bug with 
     * jsTree / Firefox and text/xhtml+xml 
     * content type
     */
    S.setHeader("content-type","text/html")
    bind("tree",content,
      "head" -> head("tree",S.contextPath + "/api/ldap/children" )
    )
  }
  
  private def head(treeHtmlId:String,jsonUrl:String) : NodeSeq = {
    //display details of a node on click on it
    def displayNodeDetails(dn:String) :JsCmd = {
      val xml = ldap flatMap { _.get(dn) } match {
        case Full(e) => 
          <div>
            <h4>{dn}</h4>
            { e.attributes.flatMap { a =>
              <h5>{a.getName}</h5>
              <ul><li>{a.getValues.flatMap(v => <span class="attributeValue">{v}</span><br/>).toSeq}</li></ul>
              //<ul><li>{a.getValues.mkString(" ")}</li></ul>
            } }
          </div>
        case _ => <p>No details found for entry {dn}</p>
        //TODO : create a real LDAPEntry.toXml, or at least a real template binding
      }
      
      SetHtml("node_details", xml)
    }
    
    <head>
      <script type="text/javascript" src="/javascript/jstree/jquery.jstree.js" id="jstree"></script>
      {Script(OnLoad(
      JsCrVar("rootNode", JsRaw("""[{ attributes : { "id" : "%s" }, data : "%s", state : "closed", children: [] }]""".format(
          acceptedServersDit.BASE_DN,acceptedServersDit.BASE_DN))) & 
      JsRaw("""jQuery('#%s').tree({
        data : { 
          type : "json",
          async : true,
          opts : {
            url : "%s"
          }
        },
        callback : { 
        // Take care of refresh calls - n will be false only when the whole tree is refreshed or loaded of the first time
        // Make sure static is not used once the tree has loaded for the first time
          beforedata : function (n, t) { 
            if(n == false) t.settings.data.opts.static = rootNode; 
            else t.settings.data.opts.static = false; 
            return { id : jQuery(n).attr("id") || 0 }
          },
          onselect : function (n,t) {
            %s;
          
          }
        }
      }
    )""".format(
      treeHtmlId,
      jsonUrl,
      SHtml.ajaxCall(JsVar("n","id"),  displayNodeDetails )._2.toJsCmd
    ))))}
    </head>
  }
}
