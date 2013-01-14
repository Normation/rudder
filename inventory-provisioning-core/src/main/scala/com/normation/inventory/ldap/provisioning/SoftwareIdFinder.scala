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

import com.normation.ldap.sdk._
import BuildFilter._
import com.normation.inventory.ldap.core._
import LDAPConstants._
import com.normation.inventory.domain._

import scala.collection.mutable.Buffer

import net.liftweb.common.{Box,Empty,Full}

import org.slf4j.LoggerFactory
import NameAndVersionIdFinder._

object NameAndVersionIdFinder {
  val logger = LoggerFactory.getLogger(classOf[NameAndVersionIdFinder])
}


/*
 * Retrieve the id from the Mother Board Id
 */
class NameAndVersionIdFinder(
    ldapConnectionProvider:LDAPConnectionProvider,
    mapper:InventoryMapper,
    dit:InventoryDit
) extends SoftwareDNFinderAction {
  
  //the onlyTypes is an AND filter
  override def tryWith(entity:Software) : Box[SoftwareUuid] = {
    //create filter for software name and version
    val nameFilter = entity.name match {
      case None => NOT(HAS(A_NAME))
      case Some(x) => EQ(A_NAME,x)
    }
    
    val versionFilter = entity.version match {
      case None => NOT(HAS(A_SOFT_VERSION))
      case Some(x) => EQ(A_SOFT_VERSION,x.value)
    }

    val filter = AND(nameFilter,versionFilter)
    

    ldapConnectionProvider.flatMap { con =>
      //get potential entries, and only get the one with a A_SOFTWARE_UUID
      //return the list of A_SOFTWARE_UUID sorted
      val entries = con.searchOne(dit.SOFTWARE.dn, filter, A_SOFTWARE_UUID).map(e => e(A_SOFTWARE_UUID)).flatten.sorted
      if(entries.size >= 1) {
        if(entries.size > 1) {
        /*
         * that means that several os have the same public key, probably they should be
         * merge. Notify the human merger service for candidate.
         * For that case, take the first one
         */
        //TODO : notify merge
          logger.info("Several software ids found for filter '{}', chosing the first one:",filter)
          for(e <- entries) {
            logger.info("-> {}",e )
          }
        } 
        Full(SoftwareUuid(entries.head))
      } else Empty
    }
  }
}

