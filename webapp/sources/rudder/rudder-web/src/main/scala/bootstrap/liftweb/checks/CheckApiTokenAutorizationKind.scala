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

import com.normation.rudder.domain.RudderDit
import com.normation.ldap.sdk.LDAPConnectionProvider
import javax.servlet.UnavailableException
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.ldap.sdk.BuildFilter
import com.normation.ldap.sdk.LDAPEntry
import com.normation.rudder.api.ApiAccountType
import com.normation.rudder.api.ApiAuthorizationKind

import scalaz.zio._
import com.normation.zio._

/**
 * This class add the "api acl" attribute to Api Token
 * which don't have them.
 * It uses the current list of defined endpoint to create the list.
 */
class CheckApiTokenAutorizationKind(
    rudderDit    : RudderDit
  , ldapConnexion: LDAPConnectionProvider[RwLDAPConnection]
) extends BootstrapChecks {

  val DEFAULT_AUTHZ = ApiAuthorizationKind.RW

  override val description = "Update existing API token to 'RW' autorization level."

  @throws(classOf[ UnavailableException ])
  override def checks() : Unit = {
    import com.normation.inventory.ldap.core.LDAPConstants.A_NAME
    import com.normation.rudder.domain.RudderLDAPConstants.{ OC_API_ACCOUNT, A_API_AUTHZ_KIND, A_API_UUID, A_API_KIND }

    /*
     * Process one entry. Don't fail, but log the error
     */
    def processOne(ldap: RwLDAPConnection)(e: LDAPEntry): UIO[Unit] = {
      // we are looking for entries where A_API_ACL is not defined
      // we also want to exclude already ported ACL, ie the one where A_API_KIND is defined to something else than "public"
      if(!e.hasAttribute(A_API_AUTHZ_KIND) && e(A_API_KIND).getOrElse(ApiAccountType.PublicApi.name) == ApiAccountType.PublicApi.name ) {
        e += (A_API_AUTHZ_KIND, DEFAULT_AUTHZ.name)
        val name = e(A_NAME).orElse(e(A_API_UUID)).getOrElse("")
        ldap.save(e) foldM (
          err =>
            BootraspLogger.logPure.error(s"Error when trying to add default '${DEFAULT_AUTHZ.name.toUpperCase}' authorization level to API token ${name}. Error was: ${err.fullMsg}")
        , ok  =>
            BootraspLogger.logPure.info(s"[migration] Adding default '${DEFAULT_AUTHZ.name.toUpperCase}' authorization level to API token '${name}'")
        )
      } else {
        UIO.unit
      }
    }

    (for {
      ldap    <- ldapConnexion
      entries <- ldap.searchOne(rudderDit.API_ACCOUNTS.dn, BuildFilter.IS(OC_API_ACCOUNT), A_API_KIND, A_API_AUTHZ_KIND, A_NAME)
      done    <- ZIO.foreach(entries)(processOne(ldap))
    } yield {
      done
    }).run.void.runNow
  }
}
