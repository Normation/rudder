/*
*************************************************************************************
* Copyright 2013 Normation SAS
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
package com.normation.rudder.appconfig

import com.normation.ldap.sdk.BuildFilter.IS
import com.normation.ldap.sdk.LDAPConnectionProvider
import com.normation.ldap.sdk.RwLDAPConnection
import com.normation.rudder.domain.RudderDit
import com.normation.rudder.domain.RudderLDAPConstants.OC_PROPERTY
import com.normation.rudder.domain.parameters.GlobalParameter
import com.normation.rudder.repository.ldap.LDAPEntityMapper
import com.normation.utils.Control._
import net.liftweb.common.Box
import com.normation.rudder.domain.parameters.GlobalParameter
import com.normation.rudder.domain.appconfig.RudderWebProperty

/**
 * A basic config repository, NOT typesafe.
 * The typecasting should be handle at a
 * higher level.
 */
trait ConfigRepository {


  def getConfigParameters(): Box[Seq[RudderWebProperty]]

  def saveConfigParameter(parameter: RudderWebProperty): Box[RudderWebProperty]

}


class LdapConfigRepository(
    rudderDit: RudderDit
  , ldap     : LDAPConnectionProvider[RwLDAPConnection]
  , mapper   : LDAPEntityMapper
) extends ConfigRepository {

  def getConfigParameters(): Box[Seq[RudderWebProperty]] = {
     for {
      con     <- ldap
      entries =  con.searchSub(rudderDit.APPCONFIG.dn, IS(OC_PROPERTY))
      properties <- sequence(entries) { entry =>
                   mapper.entry2RudderConfig(entry) ?~! "Error when transforming LDAP entry into an application parameter. Entry: %s".format(entry)
                 }
    } yield {
      properties
    }
  }

  def saveConfigParameter(property: RudderWebProperty) = {
    for {
      con        <- ldap
      propEntry =  mapper.rudderConfig2Entry(property)
      result     <- con.save(propEntry) ?~! "Error when saving parameter entry in repository: %s".format(propEntry)
    } yield {
      property
    }
  }


}
