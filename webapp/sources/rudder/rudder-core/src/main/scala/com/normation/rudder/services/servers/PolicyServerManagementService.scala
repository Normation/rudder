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

package com.normation.rudder.services.servers

import net.liftweb.common.Box
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.Constants
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.WoDirectiveRepository
import com.normation.rudder.domain.Constants
import net.liftweb.common.Loggable
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import sun.net.util.IPAddressUtil
import ca.mrvisser.sealerate
import net.liftweb.common.Failure
import net.liftweb.common.Full

import com.normation.box._

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

object PolicyServerManagementService {
  /**
   * Check if the given string is a network address,
   * i.e if it on the form IP(v4 or v6)/mask.
   * A single IP address will be accepted by the test.
   */
  def isValidNetwork(net:String) = {

    val parts = net.split("/")
    if(parts.size == 1) {
       IPAddressUtil.isIPv6LiteralAddress(parts(0)) ||
       IPAddressUtil.isIPv4LiteralAddress(parts(0))
    } else if(parts.size == 2) {
      (
       IPAddressUtil.isIPv6LiteralAddress(parts(0)) ||
       IPAddressUtil.isIPv4LiteralAddress(parts(0))
      ) && (
        try {
          val n = parts(1).toInt
          if(n >= 0 && n < 255) true else false
        } catch {
          case _:NumberFormatException => false
        }
      )
    } else false
  }
}

class PolicyServerManagementServiceImpl(
    roDirectiveRepository: RoDirectiveRepository
  , woDirectiveRepository: WoDirectiveRepository
) extends PolicyServerManagementService with Loggable {

  /**
   * The list of authorized network is not directly stored in an
   * entry. We have to look for the DistributePolicy directive for
   * that server and the rudderPolicyVariables: ALLOWEDNETWORK
   */
  override def getAuthorizedNetworks(policyServerId:NodeId) : Box[Seq[String]] = {
    for {
      directive <- roDirectiveRepository.getDirective(Constants.buildCommonDirectiveId(policyServerId))
    } yield {
      val allowedNetworks = directive.parameters.getOrElse(Constants.V_ALLOWED_NETWORK, List())
      allowedNetworks
    }
  }.toBox

  override def setAuthorizedNetworks(policyServerId:NodeId, networks:Seq[String], modId: ModificationId, actor:EventActor) : Box[Seq[String]] = {

    val directiveId = Constants.buildCommonDirectiveId(policyServerId)

    //filter out bad networks
    val validNets = networks.flatMap { case net =>
      if(PolicyServerManagementService.isValidNetwork(net)) Some(net)
      else {
        logger.error("Ignoring allowed network '%s' because it does not seem to be a valid network")
        None
      }
    }

    for {
      res <- roDirectiveRepository.getActiveTechniqueAndDirective(directiveId).chainError(s"Error when retrieving directive with ID ${directiveId.value}''")
      (activeTechnique, directive) = res
      newPi = directive.copy(parameters = directive.parameters + (Constants.V_ALLOWED_NETWORK -> validNets.map( _.toString)))
      msg = Some("Automatic update of system directive due to modification of accepted networks ")
      saved <- woDirectiveRepository.saveSystemDirective(activeTechnique.id, newPi, modId, actor, msg).chainError(s"Can not save directive for Active Technique '${activeTechnique.id.value}")
    } yield {
      networks
    }
  }.toBox
}

sealed trait RelaySynchronizationMethod { def value: String }
object RelaySynchronizationMethod {

  final case object Classic extends RelaySynchronizationMethod  { val value = "classic"  }

  final case object Rsync extends RelaySynchronizationMethod    { val value = "rsync"    }

  final case object Disabled extends RelaySynchronizationMethod { val value = "disabled" }

  final val all: Set[RelaySynchronizationMethod] = sealerate.values[RelaySynchronizationMethod]

  def parse(value: String): Box[RelaySynchronizationMethod] = {
    value match {
            case null|"" => Failure("An empty or null string can not be parsed as a relay synchronization method")
            case s => s.trim.toLowerCase match {
              case Classic.value  => Full(Classic)
              case Disabled.value => Full(Disabled)
              case Rsync.value    => Full(Rsync)
              case _              => Failure(s"Cannot parse the given value as a valid relay synchronization method: '${value}'. Authorised values are: '${all.map( _.value).mkString(", ")}'")
            }
          }
  }
}
