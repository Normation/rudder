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

    // Whether the object is a system (shared/global) object. System objects can only be managed by an
    // administrator (all-tenants grant), so the tenant check logic needs to know it. There is no default:
    // each instance states explicitly whether its type has a system notion (and how it is computed).
    def isSystem: Boolean

    // How this object's tenant tag may evolve when an administrator changes it (see TenantTagLifecycle):
    // `Monotonic` (configuration objects: visibility can only grow, for event-log soundness) or
    // `Reassignable` (nodes: freely reassignable between tenants). There is no default: each instance states
    // it explicitly, so a new taggable type can not silently inherit the wrong lifecycle.
    def tenantTagLifecycle: TenantTagLifecycle

    // update the security context of the object, returning is updated
    def updateSecurityContext(security: Option[SecurityTag]): A

    // simplify common usage with cc: tag the object with the tenants the user can write on
    def updateFromChangeContext(implicit cc: ChangeContext): A = updateSecurityContext(
      cc.accessGrant.restrictToWrite.toSecurityTag
    )

    // this is needed for giving useful log/debug message to users
    def debugId: String

  }
}

// A security token for now is just a list of tags denoting tenants
// That security tag is not exposed in proxy service
sealed trait SecurityTag

// default serialization for security tag. Be careful, changing that impacts external APIs.
object SecurityTag {

  // A tag answers two questions: who sees the object, and who may change it. A container only needs the
  // first one, the child carries its own tag (`TenantCheckLogic.checkContainer`).

  /*
   * Visible to the listed tenants, changeable by those of them with a `rw` grant. An empty list means only
   * a `*` grant sees it. Serialized `{"tenants": ["tenantA", "tenantB"]}`.
   */
  final case class ByTenants(tenants: Chunk[TenantId]) extends SecurityTag

  /*
   * Visible to everybody. The two cases differ only on who may write, so visibility decisions match on
   * `_: Open` and only `canWrite` and `join` name the case. `kind` is the serialized word.
   */
  sealed trait Open(val kind: String) extends SecurityTag

  object OpenRo extends Open("open-ro")
  object OpenRw extends Open("open-rw")

  def empty: SecurityTag = SecurityTag.ByTenants(Chunk.empty)

  given codecByTenants: JsonCodec[ByTenants] = DeriveJsonCodec.gen

  // the legacy open that was used during 9.2 beta can still exist a bit. It is read as "open-ro" because it was the intent
  private val OPEN_LEGACY = "open"

  private def parseOpen(value: String): Option[Open] = {
    value match {
      case OpenRo.kind | OPEN_LEGACY => Some(OpenRo)
      case OpenRw.kind               => Some(OpenRw)
      case _                         => None
    }
  }

  private def expectedOpen: String = s"'${OpenRo.kind}' or '${OpenRw.kind}'"

  // not a `given`: an open tag is only serialized as part of a `SecurityTag`
  private val codecOpen: JsonCodec[Open] = new JsonCodec[Open](
    JsonEncoder.string.contramap(_.kind),
    JsonDecoder.string.mapOrFail { s =>
      parseOpen(s).toRight(s"Error decoding security tag: found '${s}', expected ${expectedOpen}")
    }
  )

  given codecSecurityTag: JsonCodec[SecurityTag] = new JsonCodec[SecurityTag](
    (a: SecurityTag, indent: Option[Int], out: Write) => {
      a match {
        case x: ByTenants => codecByTenants.encoder.unsafeEncode(x, indent, out)
        case x: Open      => codecOpen.encoder.unsafeEncode(x, indent, out)
      }
    },
    codecOpen.decoder.widen[SecurityTag] <> codecByTenants.decoder.widen
  )

  // JsonCodec[A] does not implicitly provide JsonEncoder[A]/JsonDecoder[A] in ZIO JSON, so we expose them explicitly
  given JsonEncoder[SecurityTag] = codecSecurityTag.encoder
  given JsonDecoder[SecurityTag] = codecSecurityTag.decoder

  /*
   * Form of the `securityTag` LDAP attribute: the bare word for an open tag, the JSON object for a tenant
   * list.
   *
   *   securityTag: open-ro
   *   securityTag: {"tenants":["zoneA"]}
   */
  def toLdapValue(tag: SecurityTag): String = {
    tag match {
      case o: Open      => o.kind
      case t: ByTenants => codecByTenants.encoder.encodeJson(t, None).toString
    }
  }

  /*
   * Whitespace is trimmed, an LDIF is hand-written. Rudder 9.1 wrote the JSON form into the attribute, so
   * the quoted `"open"` is read too and rewritten bare on the next save.
   */
  def parseLdapValue(value: Option[String], debugId: => String): Option[SecurityTag] = {
    value.flatMap { raw =>
      val v        = raw.trim
      val unquoted = if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) v.substring(1, v.length - 1) else v
      parseOpen(unquoted).orElse(parseOrWarn(v, debugId, expectedOpen))
    }
  }

  // Tag stored in a JSON document: `securitytag` jsonb column, `security` field of a serialized property.
  def parseJsonValue(value: Option[String], debugId: => String): Option[SecurityTag] = {
    value.flatMap(v => parseOrWarn(v, debugId, s"""'"${OpenRo.kind}"' or '"${OpenRw.kind}"'"""))
  }

  /*
   * An unreadable value is read as "no tag", ie administrators only: fail closed. Warn, so that the object
   * disappearing from every tenant view is traceable to the value.
   */
  private def parseOrWarn(value: String, debugId: => String, expected: => String): Option[SecurityTag] = {
    value.fromJson[SecurityTag] match {
      case Right(tag) => Some(tag)
      case Left(err)  =>
        TenantsLogger.logEffect.warn(
          s"Security tag '${value}' of '${debugId}' can not be understood, so the object is only visible to " +
          s"""administrators. Expected ${expected} or '{"tenants":["tenantA"]}'. Error was: ${err}"""
        )
        None
    }
  }

  // XML form, for object marshalling: archives, git, technique metadata.
  import scala.xml.*

  def toXml(opt: Option[SecurityTag]): NodeSeq = {
    opt match {
      case None                => NodeSeq.Empty
      case Some(o: Open)       => <security>{Elem(null, o.kind, Null, TopScope, minimizeEmpty = true)}</security>
      case Some(ByTenants(ts)) => <security><tenants>{ts.map(t => <tenant id={t.value}/>)}</tenants></security>
    }
  }

  // takes the parent element of the `security` one
  def fromXml(xml: NodeSeq): Option[SecurityTag] = {
    def tenant(t: XNode): Option[TenantId] = {
      (t \ "@id").text.trim match {
        case "" => None
        case t  => Some(TenantId(t))
      }
    }
    def nonEmpty(name: String): Boolean = (xml \ "security" \ name) != NodeSeq.Empty

    (xml \ "security" \ "tenants") match {
      case NodeSeq.Empty =>
        // `<open/>` comes from 9.1
        List(OpenRo.kind, OpenRw.kind, OPEN_LEGACY).find(nonEmpty).flatMap(parseOpen)
      case ns            => Some(SecurityTag.ByTenants(Chunk.fromIterable((ns \ "tenant").flatMap(tenant))))
    }
  }

  /*
   * The security level to use for automatically added objects in technique library.
   * Up to Rudder 9.1, it was a de-facto `None`
   */
  val USER_LIB_TECHNIQUE_SECURITY_TAG: Option[SecurityTag] = None

  // tag of a library object: root categories, provided groups and targets, shipped techniques.
  val LIBRARY_SECURITY_TAG: Option[SecurityTag] = Some(OpenRo)

  /*
   * Tag of a non-system technique with only a `metadata.xml`: shipped by Rudder or hand-written in the
   * configuration repository, usable by a tenant and changeable by an administrator, ie a library object.
   * A technique with a `technique.yml` declares its own `security` field, and falls back to
   * `USER_LIB_TECHNIQUE_SECURITY_TAG`.
   */
  val LEGACY_TECHNIQUE_SECURITY_TAG: Option[SecurityTag] = LIBRARY_SECURITY_TAG

  /*
   * Visibility lattice. `None` (administrators only) is the bottom, the open tags are the top, a tenant
   * list is above the lists it contains. Two disjoint tenant lists are not comparable.
   *
   * `isWiderOrEqual(a, b)` reads "everything `b` shows, `a` shows too". Backs the monotonic-growth law
   * (`TenantCheckLogic`) and tag inheritance, both of which constrain visibility only; who may write is
   * `canWrite`.
   */
  def isWiderOrEqual(a: Option[SecurityTag], b: Option[SecurityTag]): Boolean = {
    (a, b) match {
      case (x, y) if x == y                           => true
      case (Some(OpenRo | OpenRw), _)                 => true
      case (_, Some(OpenRo | OpenRw))                 => false
      case (_, None)                                  => true
      case (None, _)                                  => false
      case (Some(ByTenants(as)), Some(ByTenants(bs))) => bs.toSet.subsetOf(as.toSet)
    }
  }

  /*
   * Narrowest tag showing everything both show. Used where an object inherits from several sources: an
   * active technique covers every version of its technique, each declaring its own tag. Two open tags are
   * equally visible, so the narrower write wins.
   */
  def join(a: Option[SecurityTag], b: Option[SecurityTag]): Option[SecurityTag] = {
    (a, b) match {
      case (None, x)                                  => x
      case (x, None)                                  => x
      case (Some(OpenRo), _) | (_, Some(OpenRo))      => Some(OpenRo)
      case (Some(OpenRw), _) | (_, Some(OpenRw))      => Some(OpenRw)
      case (Some(ByTenants(as)), Some(ByTenants(bs))) => Some(ByTenants(as ++ bs.filterNot(as.contains)))
    }
  }

}
