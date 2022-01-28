/*
*************************************************************************************
* Copyright 2022 Normation SAS
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

import zio.json._

/*
 * This file provide serializer for core inventory objects.
 * They assume the object IS the law regarding field names and type.
 * It means that they are ill suited to public APIs that should use a translation stub
 * to provide garanties on stability.
 * They are well suited for internal API that needs to evolve with the code.
 *
 *
 * Use
 * import zio.json._
 * import com.normation.inventory.domain.JsonSerializers.implicits._
 *
 * to import serializer in code, then:
 *
 * val obj = fromJson(jsonString)
 * val jsonString = toJson(obj)
 */


object JsonSerializers {
  object implicits extends InventoryJsonEncoders with InventoryJsonDecoders
}


// encoder from object to json string
trait InventoryJsonEncoders {
  implicit val softwareUpdateKindEnd : JsonEncoder[SoftwareUpdateKind] = JsonEncoder[String].contramap { k =>
    k match {
      case SoftwareUpdateKind.Other(v) => v
      case kind                        => kind.name
    }
  }
  implicit val softwareUpdateEnc: JsonEncoder[SoftwareUpdate] = DeriveJsonEncoder.gen

}


trait InventoryJsonDecoders {

  implicit val softwareUpdateKindDec: JsonDecoder[SoftwareUpdateKind] = JsonDecoder[String].map(s =>
    SoftwareUpdateKind.parse(s)
  )

  implicit val softwareUpdateDec: JsonDecoder[SoftwareUpdate] = DeriveJsonDecoder.gen
}

