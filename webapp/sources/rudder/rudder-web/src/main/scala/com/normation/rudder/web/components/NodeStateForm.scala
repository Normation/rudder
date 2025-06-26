/*
 *************************************************************************************
 * Copyright 2014 Normation SAS
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

package com.normation.rudder.web.components

import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.web.ChooseTemplate
import net.liftweb.common.*
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.S
import net.liftweb.http.SHtml
import net.liftweb.http.SHtml.SelectableOption
import net.liftweb.http.js.*
import net.liftweb.util.Helpers.*
import scala.xml.NodeSeq

/**
 * Component to display and configure the Agent Schedule
 */
class NodeStateForm(
    nodeFact:      CoreNodeFact,
    saveNodeState: NodeState => Box[NodeState] // and save it
) extends DispatchSnippet with Loggable {

  // Html template
  val nodeStateTemplate: NodeSeq = ChooseTemplate(
    List("templates-hidden", "components", "ComponentNodeState"),
    "node-state"
  )

  def dispatch: PartialFunction[String, NodeSeq => NodeSeq] = { case "nodestate" => (xml) => nodeStateConfiguration }

  val states: List[SelectableOption[NodeState]] = NodeState.values.map { s =>
    s match {
      case NodeState.Enabled       => (0, SelectableOption(s, "Enabled"))
      case NodeState.Initializing  => (1, SelectableOption(s, "Initializing"))
      case NodeState.PreparingEOL  => (2, SelectableOption(s, "Preparing End Of Life"))
      case NodeState.Ignored       => (3, SelectableOption(s, "Ignored"))
      case NodeState.EmptyPolicies => (4, SelectableOption(s, "Empty policies"))
    }
  }.toList.sortBy(_._1).map(_._2)

  def nodeStateConfiguration: NodeSeq = {
    var state = nodeFact.rudderSettings.state

    def process(): JsCmd = {
      saveNodeState(state) match {
        case Full(a) =>
          S.notice("nodeStateMessage", "Change saved")

        case eb: EmptyBox =>
          val e = eb ?~! "Error when saving the new node state"
          S.notice("nodeStateMessage", e.messageChain)
      }
    }

    // bind button to logic
    val bind = (
      "#nodeStateSelect" #> SHtml.selectObj[NodeState](states, Full(state), state = _)
        & "#nodeStateSelect *+" #> SHtml.hidden(process)
        & "#currentNodeState *" #> state.name
    )
    bind(nodeStateTemplate)
  }
}
