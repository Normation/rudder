/*
 *************************************************************************************
 * Copyright 2021 Normation SAS
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

import com.normation.inventory.domain.NodeId
import inet.ipaddr.IPAddressString
import io.github.arainko.ducktape.*
import zio.json._

/*
 * All policy servers. A root and 0 or more relays
 */
final case class PolicyServers(
    root:   PolicyServer,
    relays: List[PolicyServer]
)

/*
 * A data structure to hold information about policy servers like their
 * allowed networks, description, policy regarding managed nodes, etc.
 */
final case class PolicyServer(
    id:              NodeId,
    allowedNetworks: List[AllowedNetwork]
)

/*
 * An allowed network. The address can an IP, or IP+subnet (192.168.2.0/24)
 * Name is a human readable name used to qualify that allowed network.
 * The address is used a identifier.
 */
final case class AllowedNetwork(
    inet: String,
    name: String
)

object AllowedNetwork {

  /**
   * Check if the given string is a network address,
   * i.e if it on the form IP(v4 or v6)/mask.
   * A single IP address will be accepted by the test.
   */
  def isValid(net: String) = {
    new IPAddressString(net).isValid
  }
}

// diff command available on the list of servers
sealed trait PolicyServersUpdateCommand extends Product
object PolicyServersUpdateCommand {

  final case class Add(policyServer: PolicyServer)                                extends PolicyServersUpdateCommand
  final case class Delete(policyServerId: NodeId)                                 extends PolicyServersUpdateCommand
  // execute the given update action on policy servers in ids (generally on one). If ids is empty, exec on all
  final case class Update(action: PolicyServer => List[PolicyServerUpdateAction]) extends PolicyServersUpdateCommand

}

// diff command available on one server
sealed trait PolicyServerUpdateAction extends Product
object PolicyServerUpdateAction {

  final case class AddNetwork(allowedNetwork: AllowedNetwork)               extends PolicyServerUpdateAction
  final case class DeleteNetwork(inet: String)                              extends PolicyServerUpdateAction
  // execute the given update action on policy servers in ids (generally on one). If ids is empty, exec on all
  final case class UpdateNetworks(action: AllowedNetwork => AllowedNetwork) extends PolicyServerUpdateAction
  final case class SetNetworks(allowedNetworks: List[AllowedNetwork])       extends PolicyServerUpdateAction

}

object json {

  import com.normation.rudder.domain.Constants.ROOT_POLICY_SERVER_ID

  /*
   * Policy servers
   */
  final case class JAllowedNetwork(inet: String, name: String) {
    def toAllowedNetwork: AllowedNetwork = this.to[AllowedNetwork]
  }

  object JAllowedNetwork {
    def from(allowedNetwork: AllowedNetwork) = {
      allowedNetwork.to[JAllowedNetwork]
    }
  }

  final case class JPolicyServer(
      id:                                             String,
      @jsonField("allowed-networks") allowedNetworks: List[JAllowedNetwork]
  ) {
    def toPolicyServer: PolicyServer = {
      this
        .into[PolicyServer]
        .transform(
          Field.computed(_.id, x => NodeId(x.id)),
          Field.computed(_.allowedNetworks, _.allowedNetworks.map(_.toAllowedNetwork))
        )
    }

  }
  object JPolicyServer   {
    def from(server: PolicyServer) = {
      server
        .into[JPolicyServer]
        .transform(
          Field.computed(_.id, _.id.value),
          Field.computed(_.allowedNetworks, _.allowedNetworks.map(JAllowedNetwork.from(_)))
        )
    }
  }

  final case class JPolicyServers(root: JPolicyServer, relays: List[JPolicyServer]) {
    def toPolicyServers() = PolicyServers(root.toPolicyServer, relays.map(_.toPolicyServer))
  }

  object JPolicyServers {
    def from(servers: PolicyServers) = JPolicyServers(JPolicyServer.from(servers.root), servers.relays.map(JPolicyServer.from(_)))
  }

  implicit val allowedNetworkEncoder: JsonEncoder[JAllowedNetwork] = DeriveJsonEncoder.gen
  implicit val policyServerEncoder:   JsonEncoder[JPolicyServer]   = DeriveJsonEncoder.gen
  implicit val policyServersEncoder:  JsonEncoder[JPolicyServers]  =
    JsonEncoder[List[JPolicyServer]].contramap(s => (s.root :: s.relays))

  // /!\: *JR* structures
  implicit val allowedNetworkDecoder: JsonDecoder[JAllowedNetwork] = DeriveJsonDecoder.gen
  implicit val policyServerDecoder:   JsonDecoder[JPolicyServer]   = DeriveJsonDecoder.gen
  implicit val policyServersDecoder:  JsonDecoder[JPolicyServers]  = JsonDecoder[List[JPolicyServer]].mapOrFail(l => {
    l.find(_.id == ROOT_POLICY_SERVER_ID.value) match {
      case None    => Left(s"Error when decoding the list of policy servers from setting: root is missing")
      case Some(x) => Right(JPolicyServers(x, l.filterNot(_.id == ROOT_POLICY_SERVER_ID.value)))
    }
  })
}
