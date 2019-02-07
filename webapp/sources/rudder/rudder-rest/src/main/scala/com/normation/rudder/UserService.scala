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

package com.normation.rudder

import com.normation.eventlog.EventActor
import com.normation.rudder.api.ApiAccount
import com.normation.rudder.api.ApiAuthorization

/*
 * This file define data type around what is a User in Rudder,
 * and a servive to access it.
 */


/**
 * Rudder user details must know if the account is for a
 * rudder user or an api account, and in the case of an
 * api account, what sub-case of it.
 */
sealed trait RudderAccount
final object RudderAccount {
  final case class User(login: String, password: String) extends RudderAccount
  final case class Api(api: ApiAccount)                  extends RudderAccount
}


trait User {
  def account: RudderAccount
  def checkRights(auth : AuthorizationType): Boolean
  def getApiAuthz: ApiAuthorization
  final def actor  = EventActor(account match {
    case RudderAccount.User(login, _) => login
    case RudderAccount.Api(api)       => api.name.value
  })
}

/**
 * A minimalistic definition of a service that give access to currently logged user .
 */
trait UserService {
  def getCurrentUser: User
}
