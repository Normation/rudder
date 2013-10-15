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

package bootstrap.liftweb
package checks


import com.normation.rudder.repository.ldap.LDAPNodeConfigurationRepository
import net.liftweb.common._
import javax.servlet.UnavailableException
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.inventory.domain.NodeId
import com.normation.rudder.services.nodes.NodeInfoService

/**
 * Check that an unique root server exists.
 * If more than one is defined, throws an error.
 * In none is defined, add a flag that force the user to configure the root
 * server. Before anything else.
 */
class CheckRootNodeUnicity(
    ldapNodeRepository: LDAPNodeConfigurationRepository
  , nodeInfoService   : NodeInfoService
) extends BootstrapChecks {

  @throws(classOf[ UnavailableException ])
  override def checks() : Unit = {

    ldapNodeRepository.getRootNodeIds match {
      case Failure(m,_,_) =>
        val msg = "Fatal error when trying to retrieve Root server in LDAP repository. Error message was: " + m
        ApplicationLogger.error(msg)
        throw new UnavailableException(msg)
      case Empty =>
        val msg = "Fatal error when trying to retrieve Root server in LDAP repository. No message was left"
        ApplicationLogger.error(msg)
        throw new UnavailableException(msg)
      case Full(seq) =>
        //set-up flag to redirect all request to init wizard
        RudderContext.rootNodeNotDefined = seq.contains(NodeId("root"))
        // if we have more than one entry, probably relay server, we are going to list them
        for {
          id       <- seq
          if id    != NodeId("root")
          hostname = nodeInfoService.getNodeInfo(id).map(_.hostname).openOr("Hostname unavailable")
        } yield {
          val msg = s"Relay server configured on ${hostname} (Rudder ID ${id.value.toUpperCase()})"
          ApplicationLogger.info(msg)
        }
    }
  }
}