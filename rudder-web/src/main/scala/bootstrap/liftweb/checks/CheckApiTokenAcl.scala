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
import com.normation.rudder.repository.ldap.LDAPEntityMapper
import com.normation.ldap.sdk.BuildFilter
import com.normation.rudder.rest.AllApi
import com.normation.rudder.api.ApiAuthz
import com.normation.rudder.api.ApiAcl
import com.normation.rudder.api.AclPathSegment
import com.normation.rudder.api.AclPath
import com.normation.rudder.rest.ApiPathSegment
import net.liftweb.common.Full
import net.liftweb.common.EmptyBox

/**
 * This class add the "api acl" attribute to Api Token
 * which don't have them.
 * It uses the current list of defined endpoint to create the list.
 */
class CheckApiTokenAcl(
    rudderDit    : RudderDit
  , ldapConnexion: LDAPConnectionProvider[RwLDAPConnection]
  , mapper       : LDAPEntityMapper
) extends BootstrapChecks {

  override val description = "Define default API ACL"

  val defaultAcl = {
    val authzs = AllApi.api.groupBy( _.path ).mapValues( _.map( _.action ) ).map { case (path, actions) =>
      val p = AclPath.FullPath(path.parts.map {
        case ApiPathSegment.Segment(v)  => AclPathSegment.Segment(v)
        case ApiPathSegment.Resource(_) => AclPathSegment.Wildcard
      })
      ApiAuthz(p, actions.toSet)
    }
    mapper.serApiAcl(ApiAcl(authzs.toList))
  }

  println("default acl: " + defaultAcl)


  @throws(classOf[ UnavailableException ])
  override def checks() : Unit = {
    import com.normation.inventory.ldap.core.LDAPConstants.A_NAME
    import com.normation.rudder.domain.RudderLDAPConstants.{ OC_API_ACCOUNT, A_API_ACL, A_API_UUID }

    for {
      ldap       <- ldapConnexion
    } yield {
      ldap.searchOne(rudderDit.API_ACCOUNTS.dn, BuildFilter.IS(OC_API_ACCOUNT), A_API_ACL, A_NAME).foreach { e =>
        // we are looking for entries where A_API_ACL is not defined
        if(!e.hasAttribute(A_API_ACL)) {
          e += (A_API_ACL, defaultAcl)
          val name = e(A_NAME).orElse(e(A_API_UUID)).getOrElse("")
          ldap.save(e) match {
            case Full(_) =>
              logger.info(s"[migration] Adding default ACL to API token '${name}'")
            case eb: EmptyBox =>
              val e = (eb ?~! s"Error when trying to add default ACL to API token ${name}")
              logger.error(e.messageChain)
          }
        }
      }
    }
  }

}
