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

package bootstrap.liftweb.checks

import bootstrap.liftweb.BootstrapChecks
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.ldap.sdk.BuildFilter._
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.utils.Control.bestEffort
import net.liftweb.common.Failure
import net.liftweb.common.Logger
import net.liftweb.common._
import org.slf4j.LoggerFactory
import com.normation.inventory.ldap.core.LDAPConstants.OC_NET_IF
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.RudderLDAPConstants.{A_QUERY_NODE_GROUP, OC_RUDDER_NODE_GROUP}
import com.unboundid.ldap.sdk.Modification
import com.unboundid.ldap.sdk.ModificationType

/**
 * In rudder 4.1.17 // 4.3.6 // 5.0.2 we had to change
 * ipNetworkNumber => networkSubnet.
 * So we copy node network entries attribute and we update search with that element
 */
class UpdateLdapNodeSubnetAttrName(
    dit : InventoryDit
  , ldap: LDAPConnectionProvider[RwLDAPConnection]
) extends BootstrapChecks {

  override val description = "Check if an update of node network attributes 'ipNetworkNumber' to 'networkSubnet' is needed"

  private[this] object logger extends Logger {
    override protected def _logger = LoggerFactory.getLogger("migration")
    val defaultErrorLogger : Failure => Unit = { f =>
      _logger.error(f.messageChain)
      f.rootExceptionCause.foreach { ex =>
        _logger.error("Root exception was:", ex)
      }
    }
  }

  override def checks() : Unit = {

    (for {
      con         <- ldap
      // we want to look in *all* node inventories, both pending / accepted / deleted
      badNetworks = con.searchSub(dit.BASE_DN.getParent, AND(IS(OC_NET_IF), HAS("ipNetworkNumber")), "ipNetworkNumber")
      updated     <- bestEffort(badNetworks) { e =>
        e("ipNetworkNumber") match {
          case None => // should not happen, we selected on them
            Empty
          case Some(v) =>
            con.modify(e.dn
              , new Modification(ModificationType.DELETE, "ipNetworkNumber")
              , new Modification(ModificationType.REPLACE, "networkSubnet", v)
            )
        }
      }
    } yield {
      updated
    } ) match {
      case Full(updates) =>
        logger.info(s"Some node network entries were updated due to LDAP schema change: ${updates.map(_.getDN).mkString("; ")}")

      case eb =>
        val message = "Could not migrate network entries"
        val fail = eb ?~ message
        logger.error(fail)
    }
  }
}

/*
 * Also update in group query. We don't need to exec the query again, it should still be
 * ok (as long as it's done before dyn group update)
 */
class UpdateLdapNodeSubnetInGroup(
    dit : RudderDit
  , ldap: LDAPConnectionProvider[RwLDAPConnection]
) extends BootstrapChecks {

  override val description = "Check if an update 'ipNetworkNumber' to 'networkSubnet' in group queries is needed"

  private[this] object logger extends Logger {
    override protected def _logger = LoggerFactory.getLogger("migration")
    val defaultErrorLogger : Failure => Unit = { f =>
      _logger.error(f.messageChain)
      f.rootExceptionCause.foreach { ex =>
        _logger.error("Root exception was:", ex)
      }
    }
  }

  override def checks() : Unit = {

    (for {
      con         <- ldap
      // we want to look in *all* node inventories, both pending / accepted / deleted
      badNetworks = con.searchSub(dit.GROUP.dn, AND(IS(OC_RUDDER_NODE_GROUP), SUB(A_QUERY_NODE_GROUP, null, Array("ipNetworkNumber"), null)), A_QUERY_NODE_GROUP)
      updated     <- bestEffort(badNetworks) { e =>
        e(A_QUERY_NODE_GROUP).map( v => e +=!(A_QUERY_NODE_GROUP, v.replaceAll("ipNetworkNumber", "networkSubnet")))
        con.save(e)
      }
    } yield {
      updated
    } ) match {
      case Full(updates) =>
        logger.info(s"Some node group queries were updated due to LDAP schema change: ${updates.map(_.getDN).mkString("; ")}")

      case eb =>
        val message = "Could not migrate node group queries for 'ipNetworkNumber' LDAP attribute update"
        val fail = eb ?~ message
        logger.error(fail)
    }
  }
}
