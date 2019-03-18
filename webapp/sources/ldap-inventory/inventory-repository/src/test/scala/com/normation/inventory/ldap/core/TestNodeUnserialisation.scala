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

package com.normation.inventory.ldap.core

import com.normation.inventory.domain._
import com.normation.ldap.sdk.LDAPEntry
import com.normation.zio.ZioRuntime
import com.unboundid.ldap.sdk.DN
import com.unboundid.ldap.sdk.Entry
import org.junit.runner._
import org.specs2.mutable._
import org.specs2.runner._


/**
 * Test node unserialisation frome entries, in particular
 * agent types unserialisation compatibility between
 * versions and OS
 */
@RunWith(classOf[JUnitRunner])
class TestNodeUnserialisation extends Specification {
  val mapper: InventoryMapper = {
    val softwareDN = new DN("ou=Inventories, cn=rudder-configuration")
    val acceptedNodesDitImpl: InventoryDit = new InventoryDit(
        new DN("ou=Accepted Inventories, ou=Inventories, cn=rudder-configuration")
      , softwareDN
      , "Accepted inventories"
    )
    val pendingNodesDitImpl: InventoryDit = new InventoryDit(
        new DN("ou=Pending Inventories, ou=Inventories, cn=rudder-configuration")
      , softwareDN
      , "Pending inventories"
    )
    val removedNodesDitImpl = new InventoryDit(
        new DN("ou=Removed Inventories, ou=Inventories, cn=rudder-configuration")
      , softwareDN
      ,"Removed Servers"
    )
    val inventoryDitService: InventoryDitService = new InventoryDitServiceImpl(pendingNodesDitImpl, acceptedNodesDitImpl, removedNodesDitImpl)

    new InventoryMapper(inventoryDitService, pendingNodesDitImpl, acceptedNodesDitImpl, removedNodesDitImpl)
  }

  val linux41Ldif =
    """dn: nodeId=root,ou=Nodes,ou=Accepted Inventories,ou=Inventories,cn=rudder-configuration
      |objectClass: top
      |objectClass: node
      |objectClass: unixNode
      |objectClass: linuxNode
      |nodeId: root
      |localAdministratorAccountName: root
      |nodeHostname: server.rudder.local
      |policyServerId: root
      |osFullName: Debian GNU/Linux 9.4 (stretch)
      |keyStatus: certified
      |ram: 1572864000
      |swap: 1072693248
      |osArchitectureType: x86_64
      |lastLoggedUser: vagrant
      |lastLoggedUserTime: 20000714125900.000Z
      |publicKey: publickey
      |timezoneName: UTC
      |timezoneOffset: +0000
      |osKernelVersion: 4.9.0-6-amd64
      |osName: Debian
      |osVersion: 9.4
      |inventoryDate: 20180713130033.000Z
      |receiveDate: 20180713130534.772Z
      |agentName: {"agentType":"Community","version":"4.1.14"}""".stripMargin

  val linux42Ldif =
    """dn: nodeId=root,ou=Nodes,ou=Accepted Inventories,ou=Inventories,cn=rudder-configuration
      |objectClass: top
      |objectClass: node
      |objectClass: unixNode
      |objectClass: linuxNode
      |nodeId: root
      |localAdministratorAccountName: root
      |nodeHostname: server.rudder.local
      |policyServerId: root
      |osFullName: Ubuntu 16.04 LTS
      |keyStatus: certified
      |ram: 1568669696
      |swap: 804257792
      |osArchitectureType: x86_64
      |lastLoggedUser: vagrant
      |timezoneName: EDT
      |timezoneOffset: -0400
      |osKernelVersion: 4.4.0-21-generic
      |osName: Ubuntu
      |osVersion: 16.04
      |lastLoggedUserTime: 20000710133300.000Z
      |inventoryDate: 20180716134036.000Z
      |receiveDate: 20180716134537.505Z
      |agentName: {"agentType":"cfengine-community","version":"4.2.8","securityToken":{"value":"publickey","type":"publicKey"}}""".stripMargin

  val linux43Ldif =
    """dn: nodeId=root,ou=Nodes,ou=Accepted Inventories,ou=Inventories,cn=rudder-configuration
      |objectClass: top
      |objectClass: node
      |objectClass: unixNode
      |objectClass: linuxNode
      |nodeId: root
      |localAdministratorAccountName: root
      |policyServerId: root
      |osFullName: SUSE Linux Enterprise Server 11 (x86_64)
      |osServicePack: 3
      |ram: 1572864000
      |swap: 781189120
      |lastLoggedUser: root
      |osKernelVersion: 3.0.76-0.11-default
      |osName: Suse
      |osVersion: 11
      |keyStatus: certified
      |nodeHostname: orchestrateur-3.labo.normation.com
      |osArchitectureType: x86_64
      |timezoneOffset: +0200
      |timezoneName: Europe/Paris
      |agentName: {"agentType":"cfengine-community","version":"4.3.2","securityToken":{"value":"publickey","type":"publicKey"}}
      |inventoryDate: 20180717000031.000Z
      |receiveDate: 20180717000527.050Z
      |lastLoggedUserTime: 20000714084300.000Z""".stripMargin

  val dsc42Ldif =
    s"""dn: nodeId=aff80e6d-68fb-43dd-9a33-a5204b7e3153,ou=Nodes,ou=Accepted Inventories,ou=Inventories,cn=rudder-configuration
      |nodeId: aff80e6d-68fb-43dd-9a33-a5204b7e3153
      |objectClass: node
      |objectClass: top
      |objectClass: windowsNode
      |osName: Windows2012R2
      |windowsRegistrationCompany: Vagrant
      |windowsKey: PN79T-M7QXW-R8FVX-FDF89-7XKCB
      |windowsId: 00252-00105-69793-AA339
      |osFullName: Microsoft Windows Server 2012 R2 Standard
      |osVersion: N/A
      |osKernelVersion: 6.3.9600
      |localAdministratorAccountName: vagrant-2012-r2
      |nodeHostname: vagrant-2012-r2
      |keyStatus: undefined
      |policyServerId: root
      |ram: 535822336
      |osArchitectureType: x86_64
      |lastLoggedUser: vagrant
      |inventoryDate: 20180716104920.000Z
      |receiveDate: 20180716135035.945Z
      |agentName: {"agentType":"dsc","version":"4.2-1.9","securityToken": {"value":"certificate","type":"certificate"}}
      |timezoneName: Pacific Standard Time
      |timezoneOffset: -0700""".stripMargin


  def node(ldif: String): NodeInventory = {
    val nodeEntry = new LDAPEntry(new Entry(ldif.split("\n").toSeq:_*))
    ZioRuntime.unsafeRun(mapper.nodeFromEntry(nodeEntry).either).getOrElse(throw new Exception("Error when getting node"))
  }

  "Agent type " should {
    "correctly unserialize Linux node from 4_1" in {
      node(linux41Ldif).agents(0) must beEqualTo(AgentInfo(AgentType.CfeCommunity, Some(AgentVersion("4.1.14")), PublicKey("publickey")))
    }

    "correctly unserialize Linux node from 4_2" in {
      node(linux42Ldif).agents(0) must beEqualTo(AgentInfo(AgentType.CfeCommunity, Some(AgentVersion("4.2.8")), PublicKey("publickey")))
    }

    "correctly unserialize Linux node from 4_3" in {
      node(linux43Ldif).agents(0) must beEqualTo(AgentInfo(AgentType.CfeCommunity, Some(AgentVersion("4.3.2")), PublicKey("publickey")))
    }


    "correctly unserialize DSC node from 4_2" in {
      node(dsc42Ldif).agents(0) must beEqualTo(AgentInfo(AgentType.Dsc, Some(AgentVersion("4.2-1.9")), Certificate("certificate")))
    }
  }
}

