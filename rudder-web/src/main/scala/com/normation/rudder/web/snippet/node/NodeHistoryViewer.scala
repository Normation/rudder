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

import com.normation.inventory.ldap.core.InventoryHistoryLogRepository

import com.normation.rudder.web.services.DisplayNode
import com.normation.rudder.web.model.JsNodeId

import com.normation.inventory.domain.NodeId
import com.normation.inventory.ldap.core.LDAPConstants._
import com.normation.ldap.sdk._
import BuildFilter._

import bootstrap.liftweb.LiftSpringApplicationContext.inject


import org.joda.time.DateTime

import org.slf4j.LoggerFactory
import org.joda.time.format.ISODateTimeFormat
import scala.collection.mutable.{Map => MutMap}

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

/**
 * A simple service that displays a NodeDetail widget from
 * a list of LDIF entries
 */
class NodeHistoryViewer extends StatefulSnippet {
  lazy val diffRepos = inject[InventoryHistoryLogRepository]

  var uuid : NodeId = null
  var selectedDate : DateTime = null
  var dates : Seq[(DateTime,String)] = Seq()
  //id of html element to update
  var hid = ""

  var dispatch : DispatchIt = {
    case "render" => render _
  }

  def render(xml:NodeSeq) : NodeSeq = {
    S.attr("uuid") match {
      case Full(s) => //new id of id change, init for that id
        initState(s)

        <div>
          <p>{SHtml.ajaxSelectObj[DateTime](dates, Full(selectedDate), onSelect _)}</p>
          { diffRepos.get(uuid, selectedDate) match {
              case Failure(m,_,_) => <div class="error">Error while trying to display node history. Error message: {m}</div>
              case Empty => <div class="error">No history was retrieved for the chosen date</div>
              case Full(sm) =>
                <div id={hid}>{DisplayNode.showPannedContent(sm.data, "hist") ++ Script(DisplayNode.jsInit(sm.data.node.main.id,sm.data.node.softwareIds,"hist", Some("node_tabs")))}</div>
          } }
        </div>

      case _ => <div class="error">Missing node ID information: can not display history information</div>
    }
  }


  private def initState(suuid:String) : Unit = {
    val newUuid = NodeId(suuid)
    if(newUuid != this.uuid) {
      this.uuid = newUuid
      this.hid =  JsNodeId(uuid,"hist_").toString
      this.dates = diffRepos.versions(uuid).get. //TODO : manage errors here
        map(d => (d, d.toString()))
      if(dates.nonEmpty) { selectedDate = dates.head._1 }
    }

    //if a version is available, try to use it
    for {
      version <- S.attr("version")
      date <- tryo(ISODateTimeFormat.dateTimeParser.parseDateTime(version))
    } {
      selectedDate = date
    }

  }

  private def onSelect(date:DateTime) : JsCmd = {
    diffRepos.get(uuid, date) match {
      case Failure(m,_,_) => Alert("Error while trying to display node history. Error message:" + m)
      case Empty => Alert("No history was retrieved for the chosen date")
      case Full(sm) =>
        SetHtml(hid,
          DisplayNode.showPannedContent(sm.data, "hist")) &
          DisplayNode.jsInit(sm.data.node.main.id, sm.data.node.softwareIds,"hist", Some("node_tabs")
        )
    }
  }

}
