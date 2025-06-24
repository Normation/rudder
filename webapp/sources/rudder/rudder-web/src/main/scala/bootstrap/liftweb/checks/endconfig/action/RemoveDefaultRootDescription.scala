/*
 *************************************************************************************
 * Copyright 2025 Normation SAS
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

package bootstrap.liftweb.checks.endconfig.action

import bootstrap.liftweb.BootstrapChecks
import bootstrap.liftweb.BootstrapLogger
import com.normation.inventory.domain.NodeId
import com.normation.rudder.facts.nodes.ChangeContext
import com.normation.rudder.facts.nodes.NodeFactRepository
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.zio.*
import zio.syntax.ToZio

class RemoveDefaultRootDescription(nodeFactRepository: NodeFactRepository) extends BootstrapChecks {

  override val description = "Remove Default node documentation "

  override def checks(): Unit = {
    (for {
      root <- nodeFactRepository.get(NodeId("root"))(using QueryContext.systemQC).notOptional("no root node in inventory")
      _    <- if (root.documentation == Some("the policy server")) {
                nodeFactRepository.save(root.copy(documentation = None))(using
                  ChangeContext.newForRudder(Some("Removing default not documentation"))
                )
              } else {
                ().succeed
              }
    } yield {
      ()
    }).catchAll(err => BootstrapLogger.error(s"Error while deleting default node documentation, error details: ${err.fullMsg}"))
      .runNow

  }

}
