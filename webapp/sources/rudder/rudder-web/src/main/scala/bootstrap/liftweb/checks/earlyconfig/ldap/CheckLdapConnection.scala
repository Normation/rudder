/*
 *************************************************************************************
 * Copyright 2017 Normation SAS
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

package bootstrap.liftweb.checks.earlyconfig.ldap

import bootstrap.liftweb.BootstrapChecks
import bootstrap.liftweb.BootstrapLogger
import com.normation.box.*
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.RwLDAPConnection
import jakarta.servlet.UnavailableException
import net.liftweb.common.*

/**
 * This class check that all external connection (LDAP, Postgres)
 * needed for the other bootstrap checks to run are OK.
 */
class CheckLdapConnection(
    ldap: LDAPConnectionProvider[RwLDAPConnection]
) extends BootstrapChecks {

  override val description = "Check LDAP connection"

  @throws(classOf[UnavailableException])
  override def checks(): Unit = {

    def FAIL(msg: String) = {
      BootstrapLogger.Early.LDAP.logEffect.error(msg)
      throw new UnavailableException(msg)
    }

    // check that an LDAP connection is up and running
    ldap.map(_.backed.isConnected).toBox match {
      case _: EmptyBox | Full(false) => FAIL("Can not open LDAP connection")
      case _                         => // ok
    }

    BootstrapLogger.Early.LDAP.logEffect.info("LDAP connection is OK")
  }

}
