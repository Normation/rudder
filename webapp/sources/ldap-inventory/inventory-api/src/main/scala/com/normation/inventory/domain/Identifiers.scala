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

package com.normation.inventory.domain

import java.security.MessageDigest

trait Uuid extends Any {
  def value: String
}

final case class NodeId(val value:String) extends AnyVal with Uuid

final case class MachineUuid(val value:String) extends AnyVal with Uuid

final case class SoftwareUuid(val value:String) extends AnyVal with Uuid

final case class MotherBoardUuid(val value:String) extends AnyVal with Uuid

object IdGenerator {
  /**
   * Generate a (stable) UUID from a string (ie, given the same string, get the same UUID).
   * Use MD5, so expects the same collision rate than it.
   */
  def md5Hash(s: String): String = {
    val md5 = MessageDigest.getInstance("MD5").digest(s.getBytes)
    val id = (md5.map(0xFF & _).map { "%02x".format(_) }.foldLeft(""){_ + _}).toLowerCase
    s"${id.substring(0,8)}-${id.substring(8,12)}-${id.substring(12,16)}-${id.substring(16,20)}-${id.substring(20)}"
  }
}
