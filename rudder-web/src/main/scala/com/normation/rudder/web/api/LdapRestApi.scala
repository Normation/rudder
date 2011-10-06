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
package api

import com.normation.rudder.web.services._
import com.normation.inventory.ldap.core._
import com.normation.inventory.ldap.core.LDAPConstants._
import com.normation.ldap.sdk._
import BuildFilter._

import com.unboundid.ldap.sdk.{LDAPException,DN}

import scala.collection.mutable.Buffer
import scala.collection.JavaConversions._
import bootstrap.liftweb.LiftSpringApplicationContext.inject

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

object LDAPRestApi {
  val hasChildren = "hassubordinates"
  val ldap = inject[LDAPConnectionProvider]
  
  def dispatch: LiftRules.DispatchPF = {
    case Req(List("api", "ldap", "children", dn), "", GetRequest) =>
      () => getChidren(dn)
    case Req(List("api", "ldap", "children"), "", GetRequest) if(S.param("id").isDefined)=>
      () => getChidren(S.param("id").get)
    case Req(List("api", "ldap", "entry", dn), "", GetRequest) =>
      () => getEntry(dn)
    // Invalid API request - route to our error handler
    case Req(List("api", "ldap", _), "", _) => failure _
  }
  
  def failure() : Box[LiftResponse] = {
    Full(NotFoundResponse("** The requested page was not found on the server **"))
  }
  
  /*
   * Return an entry in the format awaited by jstree.org:
   * { data : "dn" } if it's a leaf
   * { data : "dn", children : [] } if it's a node with children.
   * The [] means that children will be retrieved async
   */
  def getEntry(param:String) : Box[LiftResponse] = {
    for {
      dn <- getDn(param)
      entry <- ldap flatMap { _.get(dn.toString, "dn", hasChildren) }
      json <- entryToJson(entry)
    } yield JsonResponse(json) 
  }    
  
  /*
   * Return a Json tab of the children for the entry
   * whose dn was given as param.
   */ 
  def getChidren(param:String) : Box[LiftResponse] = {
    for {
      dn <- getDn(param)
      entries <- ldap map { _.searchOne(dn.toString, ALL, "dn", hasChildren) }
    } yield JsonResponse(childrenToJson(entries))
  }

  private def getDn(s:String) : Box[DN] = {
    try {
      Some(new DN(s))
    } catch {
      case ex:LDAPException => None
    } 
  }
  
  private def childrenToJson(entries:Seq[LDAPEntry]) : JsExp = {
    JsArray((entries.map(entryToJson(_)).filter(_.isDefined).map(_.get)):_*)
  }

  //max is total length of the string, i.e "..." includes when shorten
  private def shorten(s:String,max:Int) = {
    require(max > 3, "You can not ask for shorten node title to a lenght inferior to 4")
    if(s.length < max) s else s.substring(0,max-3) + "..."
  }
  
  private def entryToJson(entry:LDAPEntry) : Box[JsExp] = {
    entry.rdn.map { r =>
      var node = JsObj(
            ("attributes", JsObj(("id",entry.dn.toString))),
            ("data", JsObj(
                ("title", shorten(r.toString,45)),
                ("attributes", JsObj(("title",r.toString)))
            ))
          )
      entry.getAsBoolean(hasChildren) foreach { x =>
        node = node +* JsObj(("state","closed"),("chidren",JsArray()))
      }
      node
    }
  }
}

