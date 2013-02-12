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

package com.normation.rudder.services.servers

import java.net.InetAddress
import net.liftweb.common.Box
import com.normation.inventory.domain.NodeId
import com.normation.rudder.repository.DirectiveRepository
import com.normation.rudder.domain.Constants
import com.normation.utils.Control.bestEffort
import net.liftweb.util.Helpers._
import com.normation.rudder.batch.{AsyncDeploymentAgent,AutomaticStartDeployment}
import net.liftweb.common.Loggable
import com.normation.utils.NetUtils.isValidNetwork
import com.normation.rudder.domain.eventlog._
import com.normation.rudder.domain.policies._
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId

/**
 * This service allows to manage properties linked to the root policy server,
 * like authorized network, policy server hostname, etc.
 *
 * For now, the hypothesis that there is one and only one policy server is made.
 */
trait PolicyServerManagementService {


  /**
   * Get the list of authorized networks, i.e the list of networks such that
   * a node with an IP in it can ask for updated policies.
   */
  def getAuthorizedNetworks(policyServerId:NodeId) : Box[Seq[String]]

  /**
   * Update the list of authorized networks with the given list
   */
  def setAuthorizedNetworks(policyServerId:NodeId, networks:Seq[String], modId: ModificationId, actor:EventActor) : Box[Seq[String]]
}


class PolicyServerManagementServiceImpl(
    directiveRepository:DirectiveRepository
  , asyncDeploymentAgent:AsyncDeploymentAgent
) extends PolicyServerManagementService with Loggable {

  /**
   * The list of authorized network is not directly stored in an
   * entry. We have to look for the DistributePolicy directive for
   * that server and the rudderPolicyVariables: ALLOWEDNETWORK
   */
  override def getAuthorizedNetworks(policyServerId:NodeId) : Box[Seq[String]] = {
    for {
      directive <- directiveRepository.getDirective(Constants.buildCommonDirectiveId(policyServerId))
    } yield {
      val allowedNetworks = directive.parameters.getOrElse(Constants.V_ALLOWED_NETWORK, List())
      allowedNetworks
    }
  }


  override def setAuthorizedNetworks(policyServerId:NodeId, networks:Seq[String], modId: ModificationId, actor:EventActor) : Box[Seq[String]] = {

    val directiveId = Constants.buildCommonDirectiveId(policyServerId)

    //filter out bad networks
    val validNets = networks.flatMap { case net =>
      if(isValidNetwork(net)) Some(net)
      else {
        logger.error("Ignoring allowed network '%s' because it does not seem to be a valid network")
        None
      }
    }

    for {
      directive <- directiveRepository.getDirective(directiveId) ?~! "Error when retrieving directive with ID '%s'".format(directiveId.value)
      activeTechnique <- directiveRepository.getActiveTechnique(directiveId) ?~! "Error when getting active technique for directive with ID '%s'".format(directiveId.value)
      newPi = directive.copy(parameters = directive.parameters + (Constants.V_ALLOWED_NETWORK -> networks.map( _.toString)))
      msg = Some("Automatic update of system directive due to modification of accepted networks ")
      saved <- directiveRepository.saveDirective(activeTechnique.id, newPi, modId, actor, msg) ?~! "Can not save directive for Active Technique '%s'".format(activeTechnique.id.value)
    } yield {
      //ask for a new policy deployment
      asyncDeploymentAgent ! AutomaticStartDeployment(modId, RudderEventActor)
      networks
    }
  }
}
