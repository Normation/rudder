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

import bootstrap.liftweb.RudderConfig
import com.normation.box._
import com.normation.inventory.domain.NodeId
import com.normation.rudder.web.model.JsNodeId
import com.normation.rudder.web.services.DisplayNode
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.http.js._
import net.liftweb.http.js.JsCmds._
import net.liftweb.util.Helpers._
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import scala.xml._

/**
 * A simple service that displays a NodeDetail widget from
 * a list of LDIF entries
 */
class NodeHistoryViewer extends StatefulSnippet {
  lazy val historyRepos = RudderConfig.inventoryHistoryJdbcRepository

  var uuid:         NodeId                  = NodeId("temporary")
  var selectedDate: DateTime                = null
  var dates:        Seq[(DateTime, String)] = Seq()
  // id of html element to update
  var hid = ""

  var dispatch: DispatchIt = { case "render" => render _ }

  def render(xml: NodeSeq): NodeSeq = {
    S.attr("uuid") match {
      case Full(s) => // new id of id change, init for that id
        initState(s)

        <div>
          <p>{SHtml.ajaxSelectObj[DateTime](dates, Full(selectedDate), onSelect _)}</p>
          {
          historyRepos.get(uuid, selectedDate).toBox match {
            case Failure(m, _, _)   => <div class="error">Error while trying to display node history. Error message: {m}</div>
            case Empty | Full(None) => <div class="error">No history was retrieved for the chosen date</div>
            case Full(Some(sm))     =>
              <div id={hid}>{
                DisplayNode.showPannedContent(None, sm.data.fact.toFullInventory, sm.data.status, "hist") ++
                Script(DisplayNode.jsInit(sm.id, "hist"))
              }</div>
          }
        }
        </div>

      case _ => <div class="error">Missing node ID information: can not display history information</div>
    }
  }

  private def initState(suuid: String): Unit = {
    val newUuid = NodeId(suuid)
    if (newUuid != this.uuid) {
      this.uuid = newUuid
      this.hid = JsNodeId(uuid, "hist_").toString
      this.dates = historyRepos
        .versions(uuid)
        .toBox
        .getOrElse(
          throw new RuntimeException("Error when trying to parse version date. Please report as it is most likely a bug")
        )
        .map(d => (d, d.toString()))
      if (dates.nonEmpty) { selectedDate = dates.head._1 }
    }

    // if a version is available, try to use it
    for {
      version <- S.attr("version")
      date    <- tryo(ISODateTimeFormat.dateTimeParser.parseDateTime(version))
    } {
      selectedDate = date
    }

  }

  private def onSelect(date: DateTime): JsCmd = {
    historyRepos.get(uuid, date).toBox match {
      case Failure(m, _, _)   => Alert("Error while trying to display node history. Error message:" + m)
      case Empty | Full(None) => Alert("No history was retrieved for the chosen date")
      case Full(Some(sm))     =>
        SetHtml(hid, DisplayNode.showPannedContent(None, sm.data.fact.toFullInventory, sm.data.status, "hist")) &
        DisplayNode.jsInit(sm.id, "hist")
    }
  }

}
