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

package bootstrap.liftweb
package checks

import net.liftweb.common._
import com.normation.ldap.sdk.LDAPConnectionProvider
import javax.servlet.UnavailableException
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.rudder.repository.jdbc.RudderDatasourceProvider

import com.normation.box._

/**
 * This class check that all external connection (LDAP, Postgres)
 * needed for the other bootstrap checks to run are OK.
 */
class CheckConnections(
    postgres   : RudderDatasourceProvider
  , ldap       : LDAPConnectionProvider[RwLDAPConnection]
) extends BootstrapChecks {

  override val description = "Check LDAP and PostgreSQL connection"

  @throws(classOf[ UnavailableException ])
  override def checks() : Unit = {

    def FAIL(msg:String) = {
      BootraspLogger.logEffect.error(msg)
      throw new UnavailableException(msg)
    }

    //check that an LDAP connection is up and running
    ldap.map(_.backed.isConnected).toBox match {
      case _:EmptyBox | Full(false) => FAIL("Can not open LDAP connection")
      case _ => //ok
    }

    //check that PostgreSQL pool is OK
    try {
      postgres.datasource.getConnection
    } catch {
      case e: Exception => FAIL("Can not open connection to PostgreSQL database server")
    }

    BootraspLogger.logEffect.info("LDAP and PostgreSQL connection are OK")
  }

}
