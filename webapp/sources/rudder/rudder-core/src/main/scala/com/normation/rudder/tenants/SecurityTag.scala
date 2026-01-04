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
import zio.Chunk
import zio.json.*
import zio.json.internal.Write

/*
 * A trait that define an object that is tagged (can be viewed as tagged)
 * with a `SecurityTag`.
 * The simple case is that the object is directly tagged, but we could
 * use anything that ca be provided by a given.
 */
trait HasSecurityTag[A] {
  extension (a: A) {
    def security: Option[SecurityTag]

    // update the security context of the object, returning is updated
    def updateSecurityContext(security: Option[SecurityTag]): A

    // simplify common usage with cc
    def updateFromChangeContext(implicit cc: ChangeContext): A = updateSecurityContext(cc.accessGrant.toSecurityTag)

    // this is needed for giving useful log/debug message to users
    def debugId: String

  }
}

// A security token for now is just a list of tags denoting tenants
// That security tag is not exposed in proxy service
sealed trait SecurityTag

// default serialization for security tag. Be careful, changing that impacts external APIs.
object SecurityTag {

  // We have two kind of defined security tag:
  // - ByTenant security tag, that explicitly list the tenants allowed to see it.
  //   Note that an empty list of tenants means that only a grant "*" will see it.
  // - Open security tag, that means that everyone can see it. This is used for
  //   "library" kind of objects, that nobody can change but everybody should see
  //   (things like the root categories of things, standard techniques, etc)

  /*
   * `ByTenants` is serialized: `{"tenants": ["tenantA", "tenantB"]}`
   */
  final case class ByTenants(tenants: Chunk[TenantId]) extends SecurityTag

  /*
   * `Open` is serialized: `open`
   */
  object Open extends SecurityTag

  def empty: SecurityTag = SecurityTag.ByTenants(Chunk.empty)

  implicit val codecByTenants: JsonCodec[ByTenants] = DeriveJsonCodec.gen
  implicit val codecOpen:      JsonCodec[Open.type] = new JsonCodec[Open.type](
    JsonEncoder.string.contramap(_ => "open"),
    JsonDecoder.string.mapOrFail {
      case "open" => Right(Open)
      case x      => Left(s"Error decoding SecurityTag.Open: found '${x}'")
    }
  )

  implicit val codecSecurityTag: JsonCodec[SecurityTag] = new JsonCodec[SecurityTag](
    new JsonEncoder[SecurityTag] {
      override def unsafeEncode(a: SecurityTag, indent: Option[Int], out: Write): Unit = {
        a match {
          case x: ByTenants => codecByTenants.encoder.unsafeEncode(x, indent, out)
          case Open => codecOpen.encoder.unsafeEncode(Open, indent, out)
        }
      }
    },
    codecOpen.decoder.widen <> codecByTenants.decoder.widen
  )

  // XML serialization / deserialisation for events
  import scala.xml.*

  def toXml(opt: Option[SecurityTag]): NodeSeq = {
    opt match {
      case None                => NodeSeq.Empty
      case Some(Open)          => <security><open /></security>
      case Some(ByTenants(ts)) => <security><tenants>{ts.map(t => <tenant id={t.value}/>)}</tenants></security>
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
      case NodeSeq.Empty =>
        (xml \ "security" \ "open") match {
          case NodeSeq.Empty => None
          case _             => Some(SecurityTag.Open)
        }
      case ns            => Some(SecurityTag.ByTenants(Chunk.fromIterable((ns \ "tenant").flatMap(tenant))))
    }
  }
}
