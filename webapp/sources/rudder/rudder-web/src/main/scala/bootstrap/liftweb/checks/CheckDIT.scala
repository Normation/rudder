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

package bootstrap.liftweb
package checks

import net.liftweb.common._
import com.normation.rudder.domain.RudderDit
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.ldap.sdk.LDAPConnectionProvider
import javax.servlet.UnavailableException
import com.normation.ldap.sdk.RwLDAPConnection
import com.unboundid.ldap.sdk.DN

import scalaz.zio._
import scalaz.zio.syntax._
import com.normation.errors._
import com.normation.box._
import com.normation.zio._

/**
 * This class check that all DIT entries needed for the application
 * to work are present in the LDAP directory.
 * If they are not, it will try to create them, and stop
 * application start if that is not possible.
 *
 */
class CheckDIT(
    pendingNodesDit:InventoryDit
  , acceptedDit:InventoryDit
  , removedDit:InventoryDit
  , rudderDit:RudderDit
  , ldap:LDAPConnectionProvider[RwLDAPConnection]
) extends BootstrapChecks {

  override val description = "Check mandatory DIT entries"

  @throws(classOf[ UnavailableException ])
  override def checks() : Unit = {

    def FAIL(msg:String) = {
      BootraspLogger.logEffect.error(msg)
      throw new UnavailableException(msg)
    }

    //start to check that an LDAP connection is up and running
    ldap.map(_.backed.isConnected).toBox match {
      case e:EmptyBox => FAIL("Can not open LDAP connection")
      case _ => //ok
    }

    //check that all base DN's entry are already in the LDAP
    val baseDns = pendingNodesDit.BASE_DN :: pendingNodesDit.SOFTWARE_BASE_DN ::
      acceptedDit.BASE_DN :: acceptedDit.SOFTWARE_BASE_DN ::
      removedDit.BASE_DN :: removedDit.SOFTWARE_BASE_DN ::
      rudderDit.BASE_DN :: Nil

    (for {
      con   <- ldap
      pairs <- baseDns.accumulate(dn => con.get(dn, "1:1").map(e => (e, dn)))
    } yield {
      pairs.collect { //only keep those on error, and check if the resulting list is empty
        case(res,dn) if(res.isEmpty) => dn
      }
    }).either.runNow match {
      case Left(err) =>
        FAIL(s"Error when checking for mandatory entries on the DIT. Message was: ${err.fullMsg}")
      case Right(list) =>
        list match {
          case Nil => //ok
          case list =>
            FAIL( s"There is some required entries missing in the LDAP directory: ${list.map(_.toString).mkString(" | ")}")
        }
    }

    //now, check that all DIT entries are here, add missing ones
    val ditEntries = (pendingNodesDit.getDITEntries ++ acceptedDit.getDITEntries ++ removedDit.getDITEntries ++ rudderDit.getDITEntries).toSet

    (for {
      con <- ldap
      e   <- ditEntries.accumulate { e =>
               con.exists(e.dn).flatMap {
                 case true  =>  BootraspLogger.logPure.debug(s"DIT entry '${e.dn}' already in LDAP directory, nothing to do")
                 case false =>
                   BootraspLogger.logPure.info(s"Missing DIT entry '${e.dn}', trying to add it") *> con.save(e)
               }
             }
    } yield {
      e
    }).either.runNow match {
      case Right(_)  => // ok
      case Left(err) => FAIL(s"Error when checking for mandatory entries on the DIT. Message was: ${err.fullMsg}")
    }

    //check Root Policy Server entries (from init-policy-server.ldif)
    val dns = List(
        "nodeId=root,ou=Nodes,cn=rudder-configuration"
      , "nodeId=root,ou=Nodes,ou=Accepted Inventories,ou=Inventories,cn=rudder-configuration"
      , "nodeGroupId=hasPolicyServer-root,groupCategoryId=SystemGroups,groupCategoryId=GroupRoot,ou=Rudder,cn=rudder-configuration"
      , "ruleTarget=policyServer:root,groupCategoryId=SystemGroups,groupCategoryId=GroupRoot,ou=Rudder,cn=rudder-configuration"
      , "directiveId=root-distributePolicy,activeTechniqueId=distributePolicy,techniqueCategoryId=Rudder Internal,techniqueCategoryId=Active Techniques,ou=Rudder,cn=rudder-configuration"
      , "directiveId=common-root,activeTechniqueId=common,techniqueCategoryId=Rudder Internal,techniqueCategoryId=Active Techniques,ou=Rudder,cn=rudder-configuration"
      , "ruleId=root-DP,ou=Rules,ou=Rudder,cn=rudder-configuration"
      , "ruleId=hasPolicyServer-root,ou=Rules,ou=Rudder,cn=rudder-configuration"
    ).map(s => new DN(s))

    (for {
      con <- ldap
      ok  <- ZIO.foreach(dns) { dn =>
               for {
                 exists <- con.exists(dn)
                 res    <- if(exists) UIO.unit else Unconsistancy(s"Missing required entry '${dn}'. This is most likelly because Rudder was not initialized. Please run /opt/rudder/bin/rudder-init to set it up.").fail
               } yield {
                 res
               }
             }
    } yield {
      ok
    }).toBox match {
      case eb: EmptyBox =>
        val e = (eb ?~! "Error when checking for mandatory entries for 'root' server in the DIT.")
        FAIL(e.messageChain)
      case Full(_)      => //ok
    }


    BootraspLogger.logEffect.info("All the required DIT entries are present in the LDAP directory")
  }

}
