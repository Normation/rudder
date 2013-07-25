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
package com.normation.rudder.api

import com.normation.utils.HashcodeCaching
import org.joda.time.DateTime

/**
 * ID of the Account
 */
final case class ApiAccountId(value:String) extends HashcodeCaching

/**
 * Name of the principal, used in event log to know
 * who did actions.
 */
final case class ApiAccountName(value:String) extends HashcodeCaching

/**
 * The actual authentication token.
 * A token is defined with [0-9a-zA-Z]{n}, with n not small.
 */
final case class ApiToken(value: String) extends HashcodeCaching

object ApiToken {

  val tokenRegex = """[0-9a-zA-Z]{12,128}""".r

  def buildCheckValue(value: String) : Option[ApiToken] = value.trim match {
    case tokenRegex(v) => Some(ApiToken(v))
    case _ => None
  }
}

/**
 * An API principal
 */
final case class ApiAccount(
    id                 : ApiAccountId
    //Authentication token. It is a mandatory value, and can't be ""
    //If a token should be revoked, use isEnabled = false.
  , name               : ApiAccountName  //used in event log to know who did actions.
  , token              : ApiToken
  , description        : String
  , isEnabled          : Boolean
  , creationDate       : DateTime
  , tokenGenerationDate: DateTime
) extends HashcodeCaching



