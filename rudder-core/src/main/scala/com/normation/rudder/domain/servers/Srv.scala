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

package com.normation.rudder.domain.servers

import com.normation.inventory.domain._
import com.normation.inventory.ldap.core._
import LDAPConstants._
import com.normation.utils.Utils._
import org.slf4j.LoggerFactory
import org.joda.time.DateTime
import com.normation.rudder.domain.RudderLDAPConstants._
import com.normation.utils.HashcodeCaching



// TODO : merge with NodeInventory.NodeSummary


/**
 * Class that only contains most meaningfull data about a server (a non registered node)
 * (it's a server, shorten, so its name)
 * Properties are "web friendly" (i.e: string, never null, perhaps empty)
 * @param id - uuid of the server, ex: 27e0e8e8-dfc4-487e-975f-065b073f0b1f
 * @param hostname - full hostname of the server, ex: foobox1.example.com
 * @param osType - os description of the server, ex: Linux
 * @param osName - linux distribution, windows version, etc
 * @param osFullName - long description about the os (distribution, type, version, etc)
 * @param ips : the server's list of ip
 * @param creationDate : the creation date of the object
 */
case class Srv(
    id          : NodeId
  , status      : InventoryStatus
  , hostname    : String
  , osType      : String
  , osName      : String 
  , osFullName  : String
  , ips         : List[String]
  , creationDate: DateTime
) extends HashcodeCaching 


object Srv {
  import com.normation.inventory.ldap.core.LDAPConstants._

  /**
   * List of attributes needed to be able to map a Srv. 
   * There must be at least all required attributes for a node. 
   */
  val ldapAttributes = Seq(
     A_OC
   , A_NODE_UUID
   , A_HOSTNAME
   , A_ROOT_USER
   , A_POLICY_SERVER_UUID
   , A_OS_NAME
   , A_OS_VERSION
   , A_OS_KERNEL_VERSION
   , A_OS_FULL_NAME
   , A_NAME
   , A_LIST_OF_IP
   , A_OBJECT_CREATION_DATE
  )
}
