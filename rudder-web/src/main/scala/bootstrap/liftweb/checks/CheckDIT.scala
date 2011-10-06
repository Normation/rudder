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

package bootstrap.liftweb
package checks

import net.liftweb.common._
import com.normation.rudder.domain.RudderDit
import com.normation.inventory.ldap.core.InventoryDit
import com.normation.ldap.sdk.LDAPConnectionProvider

import javax.servlet.UnavailableException

/**
 * This class check that all DIT entries needed for the application
 * to work are present in the LDAP directory.
 * If they are not, it will try to create them, and stop
 * application start if that is not possible. 
 *
 */
class CheckDIT(
  pendingServersDit:InventoryDit,
  acceptedDit:InventoryDit,
  rudderDit:RudderDit,
  ldap:LDAPConnectionProvider
) extends BootstrapChecks with Loggable {
  
  @throws(classOf[ UnavailableException ])
  override def checks() : Unit = {
    
    def FAIL(msg:String) = {
      logger.error(msg)
      throw new UnavailableException(msg)
    }
    
    //start to check that an LDAP connection is up and running
    ldap.map { con => 
      con.backed.isConnected
    } match {
      case e:EmptyBox => throw new UnavailableException("Can not open LDAP connection")
      case _ => //ok
    }
    
    //check that all base DN's entry are already in the LDAP
    val baseDns = pendingServersDit.BASE_DN :: pendingServersDit.SOFTWARE_BASE_DN :: 
      acceptedDit.BASE_DN :: acceptedDit.SOFTWARE_BASE_DN :: 
      rudderDit.BASE_DN :: Nil
    
    ldap.map { con =>
      (for {
        dn <- baseDns 
      } yield {
        (con.get(dn, "1:1"), dn)
      }).filter { //only keep those on error, and check if the resulting list is empty
        case(res,dn) => res.isEmpty 
      } match {
        case Nil => //ok
        case list => 
          FAIL { "There is some required entries missing in the LDAP directory: %s".format(
            list.map {
              case (Failure(m,_,_), dn) => "%s (error message: %s)".format(dn.toString, m)
              case (Empty,dn) => "%s (no error message)".format(dn.toString)
              case _ => "" //strange...
            }.mkString(" | ")
          )}
      }
    } match {
      case Failure(m,_,_) => FAIL("Error when checking for mandatory entries on the DIT. Message was: %s".format(m))
      case Empty => FAIL("Error when checking for mandatory entries on the DIT. No message was left.")
      case Full(_) => //ok
    }
      
    //now, check that all DIT entries are here, add missing ones
    val ditEntries = pendingServersDit.getDITEntries ++ acceptedDit.getDITEntries ++ rudderDit.getDITEntries
    
    ldap.map { con =>
      (for {
        e <- ditEntries.toList
      } yield {
        if(con.exists(e.dn)) {
          logger.debug("DIT entry '%s' already in LDAP directory, nothing to do".format(e.dn))
          (Full(e),e.dn)
        } else {
          logger.info("Missing DIT entry '%s', trying to add it".format(e.dn))
          (con.save(e), e.dn)
        }
      }).filter { //only keep those on error, and check if the resulting list is empty
        case (result, dn) => result.isEmpty 
      } match {
        case Nil => //ok
        case list =>
          FAIL { "There is some required entries missing in the LDAP directory: %s".format(
            list.map {
              case (Failure(m,_,_), dn) => "%s (error message: %s)".format(dn.toString, m)
              case (Empty,dn) => "%s (no error message)".format(dn.toString)
              case _ => "" //strange...
            }.mkString(" | ")
          )}        
      }
    } match {
      case Failure(m,_,_) => FAIL("Error when checking for mandatory entries on the DIT. Message was: %s".format(m))
      case Empty => FAIL("Error when checking for mandatory entries on the DIT. No message was left.")
      case Full(_) => //ok
    }
    
    logger.info("All require DIT entries present in the LDAP directory")
  }

}