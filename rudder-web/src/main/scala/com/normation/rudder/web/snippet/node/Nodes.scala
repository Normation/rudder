/*
*************************************************************************************
* Copyright 2011-2013 Normation SAS
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

import net.liftweb.http._
import net.liftweb.common._
import bootstrap.liftweb.RudderConfig
import scala.xml.NodeSeq
import net.liftweb.http.js.JsCmds._


class Nodes extends StatefulSnippet with Loggable {
  private[this] val nodeInfoService = RudderConfig.nodeInfoService
  val srvGrid =  RudderConfig.srvGrid

  val dispatch : DispatchIt = {
    case "table" => table _
  }

  def table(html:NodeSeq)= {
  val nodes = nodeInfoService.getAllIds match {
      case Full(ids) => ids.flatMap{id => val nodeInfo =   nodeInfoService.getNodeInfo(id)
        nodeInfo match { case eb:EmptyBox => val fail = eb?~ s"could not find Node ${id}"
        logger.error(fail.msg)
        case _ =>
        }
      nodeInfo
      }
      case eb:EmptyBox => val fail = eb?~ s"could not find Nodes "
           logger.error(fail.msg)
           Seq()
    }
    srvGrid.displayAndInit(nodes, "nodes", ((nodeId:String) => RedirectTo(s"""/secure/nodeManager/searchNodes#{\"nodeId\":\"${nodeId.split("\\|")(1)}\"}""")))

}

}
