/*
 *************************************************************************************
 * Copyright 2018 Normation SAS
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
import com.normation.box.*
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.rudder.web.components.ShowNodeDetailsFromNode
import net.liftweb.common.*
import net.liftweb.http.S
import net.liftweb.http.StatefulSnippet

/**
 * Snippet that handle the "node details" (/node/uuid) page.
 */
class Node extends StatefulSnippet {

  private val getFullGroupLibrary = () => RudderConfig.roNodeGroupRepository.getFullGroupLibrary()

  private val groupLibrary = getFullGroupLibrary().toBox match {
    case Full(x) => x
    case eb: EmptyBox =>
      val e = eb ?~! "Major error: can not get the node group library"
      ApplicationLogger.error(e.messageChain)
      throw new Exception(e.messageChain)
  }

  var dispatch: DispatchIt = {
    case "details" =>
      S.param("nodeId") match {
        case _: EmptyBox =>
          _ => <p>No node ID was given in URL. How did you get there?</p>
        case Full(nodeId) =>
          val displayMode = (S.param("displayCompliance"), S.param("systemStatus")) match {
            case (Full("true"), _) => ShowNodeDetailsFromNode.Compliance
            case (_, Full("true")) => ShowNodeDetailsFromNode.System
            case (_, _)            => ShowNodeDetailsFromNode.Summary
          }
          _ => new ShowNodeDetailsFromNode(NodeId(nodeId), groupLibrary).display(false, displayMode)
      }
  }
}
