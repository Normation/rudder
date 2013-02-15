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

package com.normation.inventory.ldap.provisioning

import com.normation.inventory.services.provisioning._

import com.unboundid.ldap.sdk.DN
import com.normation.ldap.sdk._
import BuildFilter._
import com.normation.inventory.ldap.core._
import LDAPConstants._
import com.normation.utils.StringUuidGenerator
import com.normation.inventory.domain._

import net.liftweb.common._

trait NodeInventoryDNFinder extends NodeInventoryDNFinderAction

/**
 * Find the UUID in the whole LDAP and find if the uuid is already use
 *
 */
class UseExistingNodeIdFinder(inventoryDitService:InventoryDitService, ldap:LDAPConnectionProvider[RoLDAPConnection], rootDN:DN) extends NodeInventoryDNFinder {
  override def tryWith(entity:NodeInventory) : Box[(NodeId,InventoryStatus)] = {
    for {
      con <- ldap
      entry <- con.searchSub(rootDN, AND(IS(OC_NODE), EQ(A_NODE_UUID, entity.main.id.value)), "1.1").headOption //TODO: error if more than one !! #555
      dit <- inventoryDitService.getDit(entry.dn)
    } yield {
      (entity.main.id, inventoryDitService.getInventoryStatus(dit) )
    }
  }
}


/*
 * Retrieve the id from the cfengine public key
 */
class ComparePublicKeyIdFinder(
    ldapConnectionProvider:LDAPConnectionProvider[RoLDAPConnection], 
    dit:InventoryDit,
    ditService:InventoryDitService
) extends NodeInventoryDNFinder with Loggable {

  override def tryWith(entity:NodeInventory) : Box[(NodeId,InventoryStatus)] = {
    val keys = entity.publicKeys
    if(keys.size > 0) {
      val keysFilter = OR((keys.map( k => EQ(A_PKEYS,k.key))):_*)

      ldapConnectionProvider flatMap { con =>
        val entries = con.searchOne(dit.NODES.dn, keysFilter, A_NODE_UUID)

        if(entries.size >= 1) {
          if(entries.size > 1) {
            /*
             * that means that several os have the same public key, probably they should be
             * merge. Notify the human merger service for candidate.
             * For that case, take the first one
             */
            //TODO : notify merge
            logger.error("Several ids found with one of these public keys '%s':".format(keys.map(_.key)))
            for(e <- entries) {
              logger.error("-> %s".format(e.dn))
            }
            logger.error("Can not just choose one at random, so none is chosen")
            Empty
          } else {
            //Issue #553
            //Full(NodeId(entries(0)(A_SERVER_UUID).get),dit)
            val existingNodeId = NodeId(entries(0)(A_NODE_UUID).get)
            if(entity.main.id != existingNodeId) {
                logger.error("Found two nodes which share at least one key. Isn't that a pending security issue ?\nNodes ids: '%s' and '%s'\nKeys fingerprint: \n==> %s"format(entity.main.id, existingNodeId, keys.map(_.key).mkString("\n==> ")))
                Empty
            } else {
              Full((existingNodeId,ditService.getInventoryStatus(dit)))
            }
          }
        } else Empty
      }
    } else Empty
  }
}
