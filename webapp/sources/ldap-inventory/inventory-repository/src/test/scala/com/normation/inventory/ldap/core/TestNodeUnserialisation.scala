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

import com.normation.inventory.domain.*
import com.normation.ldap.sdk.LDAPEntry
import com.normation.zio.ZioRuntime
import com.unboundid.ldap.sdk.DN
import com.unboundid.ldap.sdk.Entry
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*
import zio.*
import zio.json.ast.*

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
      new DN("ou=Accepted Inventories, ou=Inventories, cn=rudder-configuration"),
      softwareDN,
      "Accepted inventories"
    )
    val pendingNodesDitImpl:  InventoryDit = new InventoryDit(
      new DN("ou=Pending Inventories, ou=Inventories, cn=rudder-configuration"),
      softwareDN,
      "Pending inventories"
    )
    val removedNodesDitImpl = new InventoryDit(
      new DN("ou=Removed Inventories, ou=Inventories, cn=rudder-configuration"),
      softwareDN,
      "Removed Servers"
    )
    val inventoryDitService: InventoryDitService =
      new InventoryDitServiceImpl(pendingNodesDitImpl, acceptedNodesDitImpl, removedNodesDitImpl)

    new InventoryMapper(inventoryDitService, pendingNodesDitImpl, acceptedNodesDitImpl, removedNodesDitImpl)
  }

  val dsc61Ldif: String = {
    """dn: nodeId=aff80e6d-68fb-43dd-9a33-a5204b7e3153,ou=Nodes,ou=Accepted Inventories,ou=Inventories,cn=rudder-configuration
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
      |agentName: {"agentType":"dsc","version":"6.1-1.9","securityToken": {"value":"-----BEGIN CERTIFICATE-----\nMIIFTjCCAzagAwIBAgIUfa0+S+CyJahRzuwNNOFLNQQjIH8wDQYJKoZIhvcNAQEL\nBQAwFzEVMBMGCgmSJomT8ixkAQEMBW5vZGU3MB4XDTI0MDMwMTExMDIxM1oXDTM0\nMDIyNzExMDIxM1owFzEVMBMGCgmSJomT8ixkAQEMBW5vZGU3MIICIjANBgkqhkiG\n9w0BAQEFAAOCAg8AMIICCgKCAgEAsmBUYI1A5vqkOTCW24m/mQhBaRu4WaAUXDAr\nArdIAAMN0KyHhEXP1/X32Flw90E0VjtUH4P+DYLBozXYWhJrUxdWLyn3TRSbv3Kr\npXoCWhMhMp8OK2s/mG+rfiMpTXIwaMhPnBaJFNV3c+bkijGAMHtFjl3+leXJvNZ7\nw3bIg4cA3e77kz7EWMyqxOUvvLMyY6wpd03ahe/By+iLgtOkgUwl9hMqMU8tJeaz\nNIeUporsHk5rrk8bSf6Mxxdknm43Sk6oflnueNCIUFdd4rS2JLieMugsTh8n/oH+\nk29ZyirE3ikhftmZ3vY8GQ3IcIzaXwiAOGnCKcze79zVx5jmOTGSZitGZaU/cZc6\nLjzEp+ZDmE8caVIksiA+hIlaZeBXNHB+YRv/gV1Rbt7kS1am+XUZJSfVFf+99YqE\nlZ5p0hqJsczkBb2RuMxWxxsWO3pesPUNCuL/yaeggTIp7eK06tQWi1EfXr40ctrf\noimbzMAKNBpRKWruL7652SlF75Usaq+PaPi1TtqYQjLRZmbpr+IR4uMeKnEMIZPw\n3dLDKBV6d71XkTAalCmcJU+fgYrRmgz1dEnaZDIXY+f+fYR15hsFpDrZ0avHgkzJ\nca/nT/rKeX7136BttxVSbZaTU9hnmjAvl+v0BF+JUWPQ3VPTcrmyUNAICt8xdVae\nuo1tsvkCAwEAAaOBkTCBjjAMBgNVHRMEBTADAQH/MB0GA1UdDgQWBBSRG0uHQRIA\ngl91YqzSjaeaw+F7PDBSBgNVHSMESzBJgBSRG0uHQRIAgl91YqzSjaeaw+F7PKEb\npBkwFzEVMBMGCgmSJomT8ixkAQEMBW5vZGU3ghR9rT5L4LIlqFHO7A004Us1BCMg\nfzALBgNVHQ8EBAMCArwwDQYJKoZIhvcNAQELBQADggIBABR2vTH/6WlqjaTZ/hQc\nB+crqRlFimqCiVTRdX6qfkt0tLX2dK6scHNTBT04ORLjLTAn0KbcOUz3k6i0mIqB\nnG5UjqCFEQR+i2V4Hz7aK6+7LBQuwXWRhDhvO5xMn7MxjvP0LGgNf5iiA4r/N22L\nEMc1prDESIOGmWdKg9XlfLxd877R2d8/3hXyT82Y2uJHO25b53skj4pbUOWLsGSw\nd4FBNnWqM+7Hbg2v9xFvmtfs2G2Inqk4Xjtjnj8qkVk3ft6KzClUIMXGXQ8QqaRp\nYPkTJm5g2UBYDiguD/tlz3VmbsNYU6fkap7DKqhttbaseIx1zDwdAv4jtVOWDyjx\nWq4b7pljYiczfRa84X/9v1x05pT3raELy5udY+Pxmnz+hOXOM+jY5bzSKS24b8rS\n5Sklmutm0IdflMKd5vNrXd9yPFLu3QzN50ArzHHXczwBLgjaMlZsPAp1wITlqM7+\nWCjy3qM5/KgAjH3L24MPTq23o9PokBVh1NH7lesZqgJPgsj+OG7FMQDvKzg7ytrs\nQlIDF1c8Ko+9/RrnRVAS8C4GZOqbmMmfJjMp09GBz2d0ixlTusF6m6iwfIVMf/nI\nP/V0D7STRiV62cfnZ3e0w8kIeZwWAgXI7RMHJU3skLyurUu8yxkp635IQyzQsW2A\nYo7t7O7fxjqD9yVI2QfkERZ7\n-----END CERTIFICATE-----\n","type":"certificate"}}
      |timezoneName: Pacific Standard Time
      |timezoneOffset: -0700""".stripMargin
  }

  val linux61Ldif: String = {
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
      |agentName: {"agentType":"cfengine-community","version":"6.1.0","securityToken":{"value":"-----BEGIN CERTIFICATE-----\nMIIFTjCCAzagAwIBAgIUfa0+S+CyJahRzuwNNOFLNQQjIH8wDQYJKoZIhvcNAQEL\nBQAwFzEVMBMGCgmSJomT8ixkAQEMBW5vZGU3MB4XDTI0MDMwMTExMDIxM1oXDTM0\nMDIyNzExMDIxM1owFzEVMBMGCgmSJomT8ixkAQEMBW5vZGU3MIICIjANBgkqhkiG\n9w0BAQEFAAOCAg8AMIICCgKCAgEAsmBUYI1A5vqkOTCW24m/mQhBaRu4WaAUXDAr\nArdIAAMN0KyHhEXP1/X32Flw90E0VjtUH4P+DYLBozXYWhJrUxdWLyn3TRSbv3Kr\npXoCWhMhMp8OK2s/mG+rfiMpTXIwaMhPnBaJFNV3c+bkijGAMHtFjl3+leXJvNZ7\nw3bIg4cA3e77kz7EWMyqxOUvvLMyY6wpd03ahe/By+iLgtOkgUwl9hMqMU8tJeaz\nNIeUporsHk5rrk8bSf6Mxxdknm43Sk6oflnueNCIUFdd4rS2JLieMugsTh8n/oH+\nk29ZyirE3ikhftmZ3vY8GQ3IcIzaXwiAOGnCKcze79zVx5jmOTGSZitGZaU/cZc6\nLjzEp+ZDmE8caVIksiA+hIlaZeBXNHB+YRv/gV1Rbt7kS1am+XUZJSfVFf+99YqE\nlZ5p0hqJsczkBb2RuMxWxxsWO3pesPUNCuL/yaeggTIp7eK06tQWi1EfXr40ctrf\noimbzMAKNBpRKWruL7652SlF75Usaq+PaPi1TtqYQjLRZmbpr+IR4uMeKnEMIZPw\n3dLDKBV6d71XkTAalCmcJU+fgYrRmgz1dEnaZDIXY+f+fYR15hsFpDrZ0avHgkzJ\nca/nT/rKeX7136BttxVSbZaTU9hnmjAvl+v0BF+JUWPQ3VPTcrmyUNAICt8xdVae\nuo1tsvkCAwEAAaOBkTCBjjAMBgNVHRMEBTADAQH/MB0GA1UdDgQWBBSRG0uHQRIA\ngl91YqzSjaeaw+F7PDBSBgNVHSMESzBJgBSRG0uHQRIAgl91YqzSjaeaw+F7PKEb\npBkwFzEVMBMGCgmSJomT8ixkAQEMBW5vZGU3ghR9rT5L4LIlqFHO7A004Us1BCMg\nfzALBgNVHQ8EBAMCArwwDQYJKoZIhvcNAQELBQADggIBABR2vTH/6WlqjaTZ/hQc\nB+crqRlFimqCiVTRdX6qfkt0tLX2dK6scHNTBT04ORLjLTAn0KbcOUz3k6i0mIqB\nnG5UjqCFEQR+i2V4Hz7aK6+7LBQuwXWRhDhvO5xMn7MxjvP0LGgNf5iiA4r/N22L\nEMc1prDESIOGmWdKg9XlfLxd877R2d8/3hXyT82Y2uJHO25b53skj4pbUOWLsGSw\nd4FBNnWqM+7Hbg2v9xFvmtfs2G2Inqk4Xjtjnj8qkVk3ft6KzClUIMXGXQ8QqaRp\nYPkTJm5g2UBYDiguD/tlz3VmbsNYU6fkap7DKqhttbaseIx1zDwdAv4jtVOWDyjx\nWq4b7pljYiczfRa84X/9v1x05pT3raELy5udY+Pxmnz+hOXOM+jY5bzSKS24b8rS\n5Sklmutm0IdflMKd5vNrXd9yPFLu3QzN50ArzHHXczwBLgjaMlZsPAp1wITlqM7+\nWCjy3qM5/KgAjH3L24MPTq23o9PokBVh1NH7lesZqgJPgsj+OG7FMQDvKzg7ytrs\nQlIDF1c8Ko+9/RrnRVAS8C4GZOqbmMmfJjMp09GBz2d0ixlTusF6m6iwfIVMf/nI\nP/V0D7STRiV62cfnZ3e0w8kIeZwWAgXI7RMHJU3skLyurUu8yxkp635IQyzQsW2A\nYo7t7O7fxjqD9yVI2QfkERZ7\n-----END CERTIFICATE-----\n","type":"certificate"},"capabilities":["https"]}
      |inventoryDate: 20180717000031.000Z
      |receiveDate: 20180717000527.050Z
      |lastLoggedUserTime: 20000714084300.000Z""".stripMargin
  }

  val linux70Ldif: String = {
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
      |agentName: {"agentType":"cfengine-community","version":"6.1.0","securityToken":{"type":"certificate","value":"-----BEGIN CERTIFICATE-----\nMIIFTjCCAzagAwIBAgIUfa0+S+CyJahRzuwNNOFLNQQjIH8wDQYJKoZIhvcNAQEL\nBQAwFzEVMBMGCgmSJomT8ixkAQEMBW5vZGU3MB4XDTI0MDMwMTExMDIxM1oXDTM0\nMDIyNzExMDIxM1owFzEVMBMGCgmSJomT8ixkAQEMBW5vZGU3MIICIjANBgkqhkiG\n9w0BAQEFAAOCAg8AMIICCgKCAgEAsmBUYI1A5vqkOTCW24m/mQhBaRu4WaAUXDAr\nArdIAAMN0KyHhEXP1/X32Flw90E0VjtUH4P+DYLBozXYWhJrUxdWLyn3TRSbv3Kr\npXoCWhMhMp8OK2s/mG+rfiMpTXIwaMhPnBaJFNV3c+bkijGAMHtFjl3+leXJvNZ7\nw3bIg4cA3e77kz7EWMyqxOUvvLMyY6wpd03ahe/By+iLgtOkgUwl9hMqMU8tJeaz\nNIeUporsHk5rrk8bSf6Mxxdknm43Sk6oflnueNCIUFdd4rS2JLieMugsTh8n/oH+\nk29ZyirE3ikhftmZ3vY8GQ3IcIzaXwiAOGnCKcze79zVx5jmOTGSZitGZaU/cZc6\nLjzEp+ZDmE8caVIksiA+hIlaZeBXNHB+YRv/gV1Rbt7kS1am+XUZJSfVFf+99YqE\nlZ5p0hqJsczkBb2RuMxWxxsWO3pesPUNCuL/yaeggTIp7eK06tQWi1EfXr40ctrf\noimbzMAKNBpRKWruL7652SlF75Usaq+PaPi1TtqYQjLRZmbpr+IR4uMeKnEMIZPw\n3dLDKBV6d71XkTAalCmcJU+fgYrRmgz1dEnaZDIXY+f+fYR15hsFpDrZ0avHgkzJ\nca/nT/rKeX7136BttxVSbZaTU9hnmjAvl+v0BF+JUWPQ3VPTcrmyUNAICt8xdVae\nuo1tsvkCAwEAAaOBkTCBjjAMBgNVHRMEBTADAQH/MB0GA1UdDgQWBBSRG0uHQRIA\ngl91YqzSjaeaw+F7PDBSBgNVHSMESzBJgBSRG0uHQRIAgl91YqzSjaeaw+F7PKEb\npBkwFzEVMBMGCgmSJomT8ixkAQEMBW5vZGU3ghR9rT5L4LIlqFHO7A004Us1BCMg\nfzALBgNVHQ8EBAMCArwwDQYJKoZIhvcNAQELBQADggIBABR2vTH/6WlqjaTZ/hQc\nB+crqRlFimqCiVTRdX6qfkt0tLX2dK6scHNTBT04ORLjLTAn0KbcOUz3k6i0mIqB\nnG5UjqCFEQR+i2V4Hz7aK6+7LBQuwXWRhDhvO5xMn7MxjvP0LGgNf5iiA4r/N22L\nEMc1prDESIOGmWdKg9XlfLxd877R2d8/3hXyT82Y2uJHO25b53skj4pbUOWLsGSw\nd4FBNnWqM+7Hbg2v9xFvmtfs2G2Inqk4Xjtjnj8qkVk3ft6KzClUIMXGXQ8QqaRp\nYPkTJm5g2UBYDiguD/tlz3VmbsNYU6fkap7DKqhttbaseIx1zDwdAv4jtVOWDyjx\nWq4b7pljYiczfRa84X/9v1x05pT3raELy5udY+Pxmnz+hOXOM+jY5bzSKS24b8rS\n5Sklmutm0IdflMKd5vNrXd9yPFLu3QzN50ArzHHXczwBLgjaMlZsPAp1wITlqM7+\nWCjy3qM5/KgAjH3L24MPTq23o9PokBVh1NH7lesZqgJPgsj+OG7FMQDvKzg7ytrs\nQlIDF1c8Ko+9/RrnRVAS8C4GZOqbmMmfJjMp09GBz2d0ixlTusF6m6iwfIVMf/nI\nP/V0D7STRiV62cfnZ3e0w8kIeZwWAgXI7RMHJU3skLyurUu8yxkp635IQyzQsW2A\nYo7t7O7fxjqD9yVI2QfkERZ7\n-----END CERTIFICATE-----\n"},"capabilities":["https"]}
      |inventoryDate: 20180717000031.000Z
      |receiveDate: 20180717000527.050Z
      |lastLoggedUserTime: 20000714084300.000Z
      |softwareUpdate: {"name":"rudder-agent","version":"7.0.0-realease","arch":"x86_64","from":"yum","kind":"none","description":"Local privilege escalation in pkexec","severity":"low","date":"2022-01-26T00:00:00Z","ids":["RHSA-2020-4566","CVE-2021-4034"]}
      |customProperty: {"name":"simpleString","value":"a simple string"}
      |customProperty: {"name":"someJson","value":{"foo":"bar","i":42,"b":true,"arr":[1,2]}}
      |environmentVariable: {"name":"UPSTART_INSTANCE"}
      |environmentVariable: {"name":"SHELL","value":"/bin/bash"}
      |process: {"pid":193,"commandName":"/var/rudder/cfengine-community/bin/cf-serverd","cpuUsage":0.0,"memory":0.0,"started":"2014-09-25 09:45","tty":"?","user":"root","virtualMemory":36664}
      |""".stripMargin
  }

  /*
   * Mains changes from 7.0~8.2
   * - process: commandName => name
   */
  val linux83Ldif: String = {
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
      |agentName: {"agentType":"cfengine-community","version":"6.1.0","securityToken":{"type":"certificate","value":"-----BEGIN CERTIFICATE-----\nMIIFTjCCAzagAwIBAgIUfa0+S+CyJahRzuwNNOFLNQQjIH8wDQYJKoZIhvcNAQEL\nBQAwFzEVMBMGCgmSJomT8ixkAQEMBW5vZGU3MB4XDTI0MDMwMTExMDIxM1oXDTM0\nMDIyNzExMDIxM1owFzEVMBMGCgmSJomT8ixkAQEMBW5vZGU3MIICIjANBgkqhkiG\n9w0BAQEFAAOCAg8AMIICCgKCAgEAsmBUYI1A5vqkOTCW24m/mQhBaRu4WaAUXDAr\nArdIAAMN0KyHhEXP1/X32Flw90E0VjtUH4P+DYLBozXYWhJrUxdWLyn3TRSbv3Kr\npXoCWhMhMp8OK2s/mG+rfiMpTXIwaMhPnBaJFNV3c+bkijGAMHtFjl3+leXJvNZ7\nw3bIg4cA3e77kz7EWMyqxOUvvLMyY6wpd03ahe/By+iLgtOkgUwl9hMqMU8tJeaz\nNIeUporsHk5rrk8bSf6Mxxdknm43Sk6oflnueNCIUFdd4rS2JLieMugsTh8n/oH+\nk29ZyirE3ikhftmZ3vY8GQ3IcIzaXwiAOGnCKcze79zVx5jmOTGSZitGZaU/cZc6\nLjzEp+ZDmE8caVIksiA+hIlaZeBXNHB+YRv/gV1Rbt7kS1am+XUZJSfVFf+99YqE\nlZ5p0hqJsczkBb2RuMxWxxsWO3pesPUNCuL/yaeggTIp7eK06tQWi1EfXr40ctrf\noimbzMAKNBpRKWruL7652SlF75Usaq+PaPi1TtqYQjLRZmbpr+IR4uMeKnEMIZPw\n3dLDKBV6d71XkTAalCmcJU+fgYrRmgz1dEnaZDIXY+f+fYR15hsFpDrZ0avHgkzJ\nca/nT/rKeX7136BttxVSbZaTU9hnmjAvl+v0BF+JUWPQ3VPTcrmyUNAICt8xdVae\nuo1tsvkCAwEAAaOBkTCBjjAMBgNVHRMEBTADAQH/MB0GA1UdDgQWBBSRG0uHQRIA\ngl91YqzSjaeaw+F7PDBSBgNVHSMESzBJgBSRG0uHQRIAgl91YqzSjaeaw+F7PKEb\npBkwFzEVMBMGCgmSJomT8ixkAQEMBW5vZGU3ghR9rT5L4LIlqFHO7A004Us1BCMg\nfzALBgNVHQ8EBAMCArwwDQYJKoZIhvcNAQELBQADggIBABR2vTH/6WlqjaTZ/hQc\nB+crqRlFimqCiVTRdX6qfkt0tLX2dK6scHNTBT04ORLjLTAn0KbcOUz3k6i0mIqB\nnG5UjqCFEQR+i2V4Hz7aK6+7LBQuwXWRhDhvO5xMn7MxjvP0LGgNf5iiA4r/N22L\nEMc1prDESIOGmWdKg9XlfLxd877R2d8/3hXyT82Y2uJHO25b53skj4pbUOWLsGSw\nd4FBNnWqM+7Hbg2v9xFvmtfs2G2Inqk4Xjtjnj8qkVk3ft6KzClUIMXGXQ8QqaRp\nYPkTJm5g2UBYDiguD/tlz3VmbsNYU6fkap7DKqhttbaseIx1zDwdAv4jtVOWDyjx\nWq4b7pljYiczfRa84X/9v1x05pT3raELy5udY+Pxmnz+hOXOM+jY5bzSKS24b8rS\n5Sklmutm0IdflMKd5vNrXd9yPFLu3QzN50ArzHHXczwBLgjaMlZsPAp1wITlqM7+\nWCjy3qM5/KgAjH3L24MPTq23o9PokBVh1NH7lesZqgJPgsj+OG7FMQDvKzg7ytrs\nQlIDF1c8Ko+9/RrnRVAS8C4GZOqbmMmfJjMp09GBz2d0ixlTusF6m6iwfIVMf/nI\nP/V0D7STRiV62cfnZ3e0w8kIeZwWAgXI7RMHJU3skLyurUu8yxkp635IQyzQsW2A\nYo7t7O7fxjqD9yVI2QfkERZ7\n-----END CERTIFICATE-----\n"},"capabilities":["https"]}
      |inventoryDate: 20180717000031.000Z
      |receiveDate: 20180717000527.050Z
      |lastLoggedUserTime: 20000714084300.000Z
      |softwareUpdate: {"name":"rudder-agent","version":"7.0.0-realease","arch":"x86_64","from":"yum","kind":"none","description":"Local privilege escalation in pkexec","severity":"low","date":"2022-01-26T00:00:00Z","ids":["RHSA-2020-4566","CVE-2021-4034"]}
      |customProperty: {"name":"simpleString","value":"a simple string"}
      |customProperty: {"name":"someJson","value":{"foo":"bar","i":42,"b":true,"arr":[1,2]}}
      |environmentVariable: {"name":"UPSTART_INSTANCE"}
      |environmentVariable: {"name":"SHELL","value":"/bin/bash"}
      |process: {"pid":193,"name":"/var/rudder/cfengine-community/bin/cf-serverd","cpuUsage":0.0,"memory":0.0,"started":"2014-09-25 09:45","tty":"?","user":"root","virtualMemory":36664.0}
      |""".stripMargin
  }

  def node(ldif: String): NodeInventory = {
    val nodeEntry = new LDAPEntry(new Entry(ldif.split("\n").toSeq*))
    ZioRuntime.unsafeRun(mapper.nodeFromEntry(nodeEntry).either).getOrElse(throw new Exception("Error when getting node"))
  }

  val cert = """-----BEGIN CERTIFICATE-----
               |MIIFTjCCAzagAwIBAgIUfa0+S+CyJahRzuwNNOFLNQQjIH8wDQYJKoZIhvcNAQEL
               |BQAwFzEVMBMGCgmSJomT8ixkAQEMBW5vZGU3MB4XDTI0MDMwMTExMDIxM1oXDTM0
               |MDIyNzExMDIxM1owFzEVMBMGCgmSJomT8ixkAQEMBW5vZGU3MIICIjANBgkqhkiG
               |9w0BAQEFAAOCAg8AMIICCgKCAgEAsmBUYI1A5vqkOTCW24m/mQhBaRu4WaAUXDAr
               |ArdIAAMN0KyHhEXP1/X32Flw90E0VjtUH4P+DYLBozXYWhJrUxdWLyn3TRSbv3Kr
               |pXoCWhMhMp8OK2s/mG+rfiMpTXIwaMhPnBaJFNV3c+bkijGAMHtFjl3+leXJvNZ7
               |w3bIg4cA3e77kz7EWMyqxOUvvLMyY6wpd03ahe/By+iLgtOkgUwl9hMqMU8tJeaz
               |NIeUporsHk5rrk8bSf6Mxxdknm43Sk6oflnueNCIUFdd4rS2JLieMugsTh8n/oH+
               |k29ZyirE3ikhftmZ3vY8GQ3IcIzaXwiAOGnCKcze79zVx5jmOTGSZitGZaU/cZc6
               |LjzEp+ZDmE8caVIksiA+hIlaZeBXNHB+YRv/gV1Rbt7kS1am+XUZJSfVFf+99YqE
               |lZ5p0hqJsczkBb2RuMxWxxsWO3pesPUNCuL/yaeggTIp7eK06tQWi1EfXr40ctrf
               |oimbzMAKNBpRKWruL7652SlF75Usaq+PaPi1TtqYQjLRZmbpr+IR4uMeKnEMIZPw
               |3dLDKBV6d71XkTAalCmcJU+fgYrRmgz1dEnaZDIXY+f+fYR15hsFpDrZ0avHgkzJ
               |ca/nT/rKeX7136BttxVSbZaTU9hnmjAvl+v0BF+JUWPQ3VPTcrmyUNAICt8xdVae
               |uo1tsvkCAwEAAaOBkTCBjjAMBgNVHRMEBTADAQH/MB0GA1UdDgQWBBSRG0uHQRIA
               |gl91YqzSjaeaw+F7PDBSBgNVHSMESzBJgBSRG0uHQRIAgl91YqzSjaeaw+F7PKEb
               |pBkwFzEVMBMGCgmSJomT8ixkAQEMBW5vZGU3ghR9rT5L4LIlqFHO7A004Us1BCMg
               |fzALBgNVHQ8EBAMCArwwDQYJKoZIhvcNAQELBQADggIBABR2vTH/6WlqjaTZ/hQc
               |B+crqRlFimqCiVTRdX6qfkt0tLX2dK6scHNTBT04ORLjLTAn0KbcOUz3k6i0mIqB
               |nG5UjqCFEQR+i2V4Hz7aK6+7LBQuwXWRhDhvO5xMn7MxjvP0LGgNf5iiA4r/N22L
               |EMc1prDESIOGmWdKg9XlfLxd877R2d8/3hXyT82Y2uJHO25b53skj4pbUOWLsGSw
               |d4FBNnWqM+7Hbg2v9xFvmtfs2G2Inqk4Xjtjnj8qkVk3ft6KzClUIMXGXQ8QqaRp
               |YPkTJm5g2UBYDiguD/tlz3VmbsNYU6fkap7DKqhttbaseIx1zDwdAv4jtVOWDyjx
               |Wq4b7pljYiczfRa84X/9v1x05pT3raELy5udY+Pxmnz+hOXOM+jY5bzSKS24b8rS
               |5Sklmutm0IdflMKd5vNrXd9yPFLu3QzN50ArzHHXczwBLgjaMlZsPAp1wITlqM7+
               |WCjy3qM5/KgAjH3L24MPTq23o9PokBVh1NH7lesZqgJPgsj+OG7FMQDvKzg7ytrs
               |QlIDF1c8Ko+9/RrnRVAS8C4GZOqbmMmfJjMp09GBz2d0ixlTusF6m6iwfIVMf/nI
               |P/V0D7STRiV62cfnZ3e0w8kIeZwWAgXI7RMHJU3skLyurUu8yxkp635IQyzQsW2A
               |Yo7t7O7fxjqD9yVI2QfkERZ7
               |-----END CERTIFICATE-----
               |""".stripMargin

  "Agent type " should {
    "correctly unserialize Linux node from 6_1" in {
      node(linux61Ldif).agents(0) must beEqualTo(
        AgentInfo(AgentType.CfeCommunity, Some(AgentVersion("6.1.0")), Certificate(cert), Set(AgentCapability("https")))
      )
    }

    "correctly unserialize DSC node from 6_1" in {
      node(dsc61Ldif).agents(0) must beEqualTo(
        AgentInfo(AgentType.Dsc, Some(AgentVersion("6.1-1.9")), Certificate(cert), Set())
      )
    }

    "correctly unserialize software updates node from 7_0" in {
      val date = JsonSerializers.parseSoftwareUpdateInstant("2022-01-26T00:00:00Z")
      (date must beRight) and (node(linux70Ldif).softwareUpdates(0) must beEqualTo(
        SoftwareUpdate(
          "rudder-agent",
          Some("7.0.0-realease"),
          Some("x86_64"),
          Some("yum"),
          SoftwareUpdateKind.None,
          None,
          Some("Local privilege escalation in pkexec"),
          Some(SoftwareUpdateSeverity.Low),
          date.toOption,
          Some(List("RHSA-2020-4566", "CVE-2021-4034"))
        )
      ))
    }

    "correctly unserialize custom properties from 7_0" in {
      import Json.*
      node(linux70Ldif).customProperties must containTheSameElementsAs(
        List(
          CustomProperty("simpleString", Str("a simple string")),
          CustomProperty(
            "someJson",
            Obj(
              Chunk(
                ("foo" -> Str("bar")),
                ("i"   -> Num(42)),
                ("b"   -> Bool(true)),
                ("arr" -> Arr(Num(1), Num(2)))
              )
            )
          )
        )
      )
    }

    "correctly unserialize environment variable" in {
      node(linux70Ldif).environmentVariables must containTheSameElementsAs(
        List(
          EnvironmentVariable("UPSTART_INSTANCE"),
          EnvironmentVariable("SHELL", Some("/bin/bash"))
        )
      )
    }

    "correctly unserialize processes" in {
      node(linux70Ldif).processes must containTheSameElementsAs(
        List(
          Process(
            193,
            Some("/var/rudder/cfengine-community/bin/cf-serverd"),
            Some(0.0f),
            Some(0.0f),
            Some("2014-09-25 09:45"),
            Some("?"),
            Some("root"),
            Some(36664)
          )
        )
      )
    }

    "7.0 and 8.3 ldif lead to the same node" in {
      val n1 = node(linux70Ldif)
      val n2 = node(linux83Ldif)
      n1 === n2
    }

    "idempotence of unser/ser for 8.3" in {
      val nodeEntry = new LDAPEntry(new Entry(linux83Ldif.split("\n").toSeq*))
      val prog      = for {
        n <- mapper.nodeFromEntry(nodeEntry)
        e  = mapper.treeFromNode(n).root
        p  = mapper.processesFromNode(n)
        _  = e.resetValuesTo(LDAPConstants.A_PROCESS, p*)
      } yield e

      val entry = ZioRuntime.unsafeRun(prog)
      val diff  = com.unboundid.ldap.sdk.Entry.diff(nodeEntry.backed, entry.backed, false, true)
      diff.toArray() must beEmpty
    }

    "correctly unserialize a node even with malformed env var, process, soft update" in {
      val nodeEntry = new LDAPEntry(
        new Entry(
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
            |agentName: {"agentType":"cfengine-community","version":"6.1.0","securityToken":{"type":"certificate","value":"-----BEGIN CERTIFICATE-----\nMIIFTjCCAzagAwIBAgIUfa0+S+CyJahRzuwNNOFLNQQjIH8wDQYJKoZIhvcNAQEL\nBQAwFzEVMBMGCgmSJomT8ixkAQEMBW5vZGU3MB4XDTI0MDMwMTExMDIxM1oXDTM0\nMDIyNzExMDIxM1owFzEVMBMGCgmSJomT8ixkAQEMBW5vZGU3MIICIjANBgkqhkiG\n9w0BAQEFAAOCAg8AMIICCgKCAgEAsmBUYI1A5vqkOTCW24m/mQhBaRu4WaAUXDAr\nArdIAAMN0KyHhEXP1/X32Flw90E0VjtUH4P+DYLBozXYWhJrUxdWLyn3TRSbv3Kr\npXoCWhMhMp8OK2s/mG+rfiMpTXIwaMhPnBaJFNV3c+bkijGAMHtFjl3+leXJvNZ7\nw3bIg4cA3e77kz7EWMyqxOUvvLMyY6wpd03ahe/By+iLgtOkgUwl9hMqMU8tJeaz\nNIeUporsHk5rrk8bSf6Mxxdknm43Sk6oflnueNCIUFdd4rS2JLieMugsTh8n/oH+\nk29ZyirE3ikhftmZ3vY8GQ3IcIzaXwiAOGnCKcze79zVx5jmOTGSZitGZaU/cZc6\nLjzEp+ZDmE8caVIksiA+hIlaZeBXNHB+YRv/gV1Rbt7kS1am+XUZJSfVFf+99YqE\nlZ5p0hqJsczkBb2RuMxWxxsWO3pesPUNCuL/yaeggTIp7eK06tQWi1EfXr40ctrf\noimbzMAKNBpRKWruL7652SlF75Usaq+PaPi1TtqYQjLRZmbpr+IR4uMeKnEMIZPw\n3dLDKBV6d71XkTAalCmcJU+fgYrRmgz1dEnaZDIXY+f+fYR15hsFpDrZ0avHgkzJ\nca/nT/rKeX7136BttxVSbZaTU9hnmjAvl+v0BF+JUWPQ3VPTcrmyUNAICt8xdVae\nuo1tsvkCAwEAAaOBkTCBjjAMBgNVHRMEBTADAQH/MB0GA1UdDgQWBBSRG0uHQRIA\ngl91YqzSjaeaw+F7PDBSBgNVHSMESzBJgBSRG0uHQRIAgl91YqzSjaeaw+F7PKEb\npBkwFzEVMBMGCgmSJomT8ixkAQEMBW5vZGU3ghR9rT5L4LIlqFHO7A004Us1BCMg\nfzALBgNVHQ8EBAMCArwwDQYJKoZIhvcNAQELBQADggIBABR2vTH/6WlqjaTZ/hQc\nB+crqRlFimqCiVTRdX6qfkt0tLX2dK6scHNTBT04ORLjLTAn0KbcOUz3k6i0mIqB\nnG5UjqCFEQR+i2V4Hz7aK6+7LBQuwXWRhDhvO5xMn7MxjvP0LGgNf5iiA4r/N22L\nEMc1prDESIOGmWdKg9XlfLxd877R2d8/3hXyT82Y2uJHO25b53skj4pbUOWLsGSw\nd4FBNnWqM+7Hbg2v9xFvmtfs2G2Inqk4Xjtjnj8qkVk3ft6KzClUIMXGXQ8QqaRp\nYPkTJm5g2UBYDiguD/tlz3VmbsNYU6fkap7DKqhttbaseIx1zDwdAv4jtVOWDyjx\nWq4b7pljYiczfRa84X/9v1x05pT3raELy5udY+Pxmnz+hOXOM+jY5bzSKS24b8rS\n5Sklmutm0IdflMKd5vNrXd9yPFLu3QzN50ArzHHXczwBLgjaMlZsPAp1wITlqM7+\nWCjy3qM5/KgAjH3L24MPTq23o9PokBVh1NH7lesZqgJPgsj+OG7FMQDvKzg7ytrs\nQlIDF1c8Ko+9/RrnRVAS8C4GZOqbmMmfJjMp09GBz2d0ixlTusF6m6iwfIVMf/nI\nP/V0D7STRiV62cfnZ3e0w8kIeZwWAgXI7RMHJU3skLyurUu8yxkp635IQyzQsW2A\nYo7t7O7fxjqD9yVI2QfkERZ7\n-----END CERTIFICATE-----\n"},"capabilities":["https"]}
            |inventoryDate: 20180717000031.000Z
            |receiveDate: 20180717000527.050Z
            |lastLoggedUserTime: 20000714084300.000Z
            |#
            |# this is the interesting parts: even with that, we get a valid inventory
            |#
            |customProperty: malformed json
            |environmentVariable: malformed json
            |process: malformed json
            |softwareUuid: malformed DN
            |""".stripMargin.linesIterator.toSeq*
        )
      )

      ZioRuntime.unsafeRun(mapper.nodeFromEntry(nodeEntry).either) must beRight
    }

  }
}
