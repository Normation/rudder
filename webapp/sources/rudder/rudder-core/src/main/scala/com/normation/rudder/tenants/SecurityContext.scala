/*
 *************************************************************************************
 * Copyright 2025 Normation SAS
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
package com.normation.rudder.tenants

import scala.xml.Node as XNode

import zio.json.*

trait HasSecurityContext {
  def security: Option[SecurityTag]
}


import zio.Chunk

// A security token for now is just a a list of tags denoting tenants
// That security tag is not exposed in proxy service
final case class SecurityTag(tenants: Chunk[TenantId])

// default serialization for security tag. Be careful, changing that impacts external APIs.
object SecurityTag {

  def empty: SecurityTag = SecurityTag(Chunk.empty)

  implicit val codecSecurityTag: JsonCodec[SecurityTag] = DeriveJsonCodec.gen

  // XML serialization / deserialisation for events
  import scala.xml.*

  def toXml(opt: Option[SecurityTag]): NodeSeq = {
    opt match {
      case None    => NodeSeq.Empty
      case Some(s) => <security><tenants>{s.tenants.map(t => <tenant id={t.value}/>)}</tenants></security>
    }
  }

  // Parse the parent element of SecurityTag to see if there is one
  def fromXml(xml: NodeSeq): Option[SecurityTag] = {
    def tenant(t: XNode): Option[TenantId] = {
      (t \ "@id").text.trim match {
        case "" => None
        case t  => Some(TenantId(t))
      }
    }
    (xml \ "security" \ "tenants") match {
      case NodeSeq.Empty => None
      case ns            => Some(SecurityTag(Chunk.fromIterable((ns \ "tenant").flatMap(tenant))))
    }
  }
}
