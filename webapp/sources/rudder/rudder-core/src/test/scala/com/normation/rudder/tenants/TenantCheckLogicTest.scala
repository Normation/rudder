/*
 *************************************************************************************
 * Copyright 2026 Normation SAS
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

import com.normation.errors.IOResult
import com.normation.eventlog.EventActor
import com.normation.zio.*
import java.util.concurrent.atomic.AtomicReference
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*
import zio.Chunk
import zio.syntax.*

/*
 * Direct unit tests of the tenant write law, one test per law. Until now that logic was only covered
 * indirectly, through the repository (LDAP) and API (YAML) test suites, which makes a law hard to read from
 * the tests and hard to change with confidence.
 *
 * The object under test is a local one rather than a Rule or a NodeFact, so that each law can be exercised
 * on exactly the variation it is about (system or not, monotonic or reassignable) without dragging a whole
 * domain object in.
 */
@RunWith(classOf[JUnitRunner])
class TenantCheckLogicTest extends Specification {

  sequential

  final case class Obj(
      id:        String,
      security:  Option[SecurityTag],
      system:    Boolean = false,
      lifecycle: TenantTagLifecycle = TenantTagLifecycle.Monotonic
  )

  object Obj {
    implicit val hasSecurityTag: HasSecurityTag[Obj] = new HasSecurityTag[Obj] {
      extension (a: Obj) {
        def security:           Option[SecurityTag] = a.security
        def isSystem:           Boolean             = a.system
        def tenantTagLifecycle: TenantTagLifecycle  = a.lifecycle
        def updateSecurityContext(security: Option[SecurityTag]): Obj = a.copy(security = security)
        def debugId: String = a.id
      }
    }
  }

  private def tenantTag(ids: String*): Option[SecurityTag] = Some(SecurityTag.ByTenants(Chunk.fromIterable(ids.map(TenantId(_)))))
  private def obj(id:        String, security: Option[SecurityTag]): Obj = Obj(id, security)
  private def existing(o:    Obj): Lookup[Obj] = Some(o).succeed
  private val absent: Lookup[Obj] = None.succeed
  private def container(o: Obj): Container[Obj] = o.succeed

  // the tenant feature is enabled on zoneA and zoneB in all these tests unless stated otherwise
  private def logic(enabled: Boolean = true, tenants: Set[TenantId] = Set(TenantId("zoneA"), TenantId("zoneB"))) = {
    val service = InMemoryTenantService.make(tenants).runNow
    service.setTenantEnabled(enabled).runNow
    new DefaultTenantCheckLogic(service)
  }

  private def cc(name: String, accesses: TenantAccess*): ChangeContext = {
    ChangeContext.newFor(EventActor(name), TenantAccessGrant.ByTenants(Chunk.fromIterable(accesses)))
  }

  private val admin  = ChangeContext.newForRudder()
  private val zoneA  = cc("zoneA", TenantAccess(TenantId("zoneA")))
  private val zoneB  = cc("zoneB", TenantAccess(TenantId("zoneB")))
  private val zoneAr = cc("zoneA read-only", TenantAccess(TenantId("zoneA"), TenantPermission.Read))

  // return the object the action was called with, or the error
  private def written[R](r: IOResult[R]): Either[String, R] = r.either.runNow.left.map(_.msg)
  private def keep(o:       Obj):         IOResult[Obj]     = o.succeed

  "[lookup context] the object being written" should {
    "always be looked up with the system context, whatever the actor" in {
      val seen = new AtomicReference[Option[QueryContext]](None)
      val look: Lookup[Obj] = (qc: QueryContext) ?=> {
        seen.set(Some(qc))
        Some(obj("o", tenantTag("zoneA"))).succeed
      }
      written(logic().manageUpdate(obj("o", tenantTag("zoneA")), look, IfAbsent.Fail("absent"))(using zoneA)(keep))
      seen.get() must beSome(QueryContext.systemQC)
    }

    "so an object the actor can not see is an update denial, never a silent creation" in {
      // the object exists and belongs to zoneA: zoneB must not go through the creation path (which would
      // re-tag it with zoneB) but be refused on the existing object
      val res = written(
        logic().manageUpdate(obj("o", None), existing(obj("o", tenantTag("zoneA"))), IfAbsent.Fail("absent"))(using zoneB)(keep)
      )
      res must beLeft(contain("can't be modified"))
    }
  }

  "[lookup context] the container" should {
    "be looked up with the actor's own context" in {
      val seen = new AtomicReference[Option[QueryContext]](None)
      val into: Container[Obj] = (qc: QueryContext) ?=> {
        seen.set(Some(qc))
        obj("parent", Some(SecurityTag.Open)).succeed
      }
      written(logic().manageCreate(obj("o", None), into)(using zoneA)(keep))
      seen.get().map(_.actor) must beSome(zoneA.actor)
    }
  }

  "[create] the tag of a created object" should {
    "be the actor's writable tenants" in {
      val res = written(logic().manageCreate(obj("o", None), Container.none)(using zoneA)(keep))
      res.map(_.security) must beRight(tenantTag("zoneA"))
    }

    "keep only the tenants that actually exist" in {
      val ccAX = cc("zoneA+X", TenantAccess(TenantId("zoneA")), TenantAccess(TenantId("zoneX")))
      val res  = written(logic().manageCreate(obj("o", None), Container.none)(using ccAX)(keep))
      res.map(_.security) must beRight(tenantTag("zoneA"))
    }

    "be none (admin-only) when the tenant feature is disabled" in {
      val res = written(logic(enabled = false).manageCreate(obj("o", tenantTag("zoneA")), Container.none)(using admin)(keep))
      res.map(_.security) must beRight(beNone)
    }

    "be refused to an actor with only read-only tenants" in {
      written(logic().manageCreate(obj("o", None), Container.none)(using zoneAr)(keep)) must beLeft
    }

    "be refused when the container is not writable by the actor" in {
      val res = written(logic().manageCreate(obj("o", None), container(obj("parent", tenantTag("zoneB"))))(using zoneA)(keep))
      res must beLeft(contain("can't be created or moved under 'parent'"))
    }

    "let an admin choose the tenants, but only existing ones" in {
      val l = logic()
      written(l.manageCreate(obj("o", tenantTag("zoneA")), Container.none)(using admin)(keep)) must beRight
      written(l.manageCreate(obj("o", tenantTag("zoneX")), Container.none)(using admin)(keep)) must beLeft(contain("don't exist"))
    }
  }

  "[update] the tag of an existing object" should {
    "not be changed by a non-admin, whatever it submits" in {
      val res = written(
        logic()
          .manageUpdate(obj("o", tenantTag("zoneA", "zoneB")), existing(obj("o", tenantTag("zoneA"))), IfAbsent.Fail("absent"))(
            using zoneA
          )(keep)
      )
      res.map(_.security) must beRight(tenantTag("zoneA"))
    }

    "be allowed to grow for an admin (monotonic)" in {
      val res = written(
        logic()
          .manageUpdate(obj("o", tenantTag("zoneA", "zoneB")), existing(obj("o", tenantTag("zoneA"))), IfAbsent.Fail("absent"))(
            using admin
          )(keep)
      )
      res.map(_.security) must beRight(tenantTag("zoneA", "zoneB"))
    }

    "not be allowed to shrink, even for an admin (monotonic)" in {
      val res = written(
        logic()
          .manageUpdate(obj("o", tenantTag("zoneA")), existing(obj("o", tenantTag("zoneA", "zoneB"))), IfAbsent.Fail("absent"))(
            using admin
          )(keep)
      )
      res must beLeft(contain("visibility can only grow"))
    }

    "be kept when the submitted object carries no tag (the payload does not transport it)" in {
      val res = written(
        logic().manageUpdate(obj("o", None), existing(obj("o", tenantTag("zoneA"))), IfAbsent.Fail("absent"))(using admin)(keep)
      )
      res.map(_.security) must beRight(tenantTag("zoneA"))
    }

    "be freely reassignable for a reassignable object (nodes)" in {
      val node = Obj("n", tenantTag("zoneA", "zoneB"), lifecycle = TenantTagLifecycle.Reassignable)
      val res  = written(
        logic().manageUpdate(node.copy(security = tenantTag("zoneA")), Some(node).succeed, IfAbsent.Fail("absent"))(using admin)(
          o => o.succeed
        )
      )
      res.map(_.security) must beRight(tenantTag("zoneA"))
    }
  }

  "[restore] putting back a past state" should {
    // this is the one thing a restore may do that an update may not
    "be allowed to narrow a monotonic tag, which an update refuses" in {
      val l        = logic()
      val past     = obj("o", tenantTag("zoneA"))
      val today    = obj("o", tenantTag("zoneA", "zoneB"))
      val refused  = written(l.manageUpdate(past, existing(today), IfAbsent.Fail("absent"))(using admin)(keep))
      val restored = written(l.manageRestore(past, existing(today), Container.none)(using admin)(keep))
      (refused must beLeft(contain("visibility can only grow"))) and
      (restored.map(_.security) must beRight(tenantTag("zoneA")))
    }

    "still refuse a tag referencing a tenant that no longer exists" in {
      val res = written(
        logic().manageRestore(obj("o", tenantTag("zoneX")), existing(obj("o", tenantTag("zoneA"))), Container.none)(using admin)(
          keep
        )
      )
      res must beLeft(contain("don't exist"))
    }

    // reverting a deletion is a restore too
    "create the object back when it is gone" in {
      val res = written(logic().manageRestore(obj("o", None), absent, Container.none)(using zoneA)(keep))
      res.map(_.security) must beRight(tenantTag("zoneA"))
    }

    "still require the right to write the object as it is today" in {
      val res = written(
        logic().manageRestore(obj("o", tenantTag("zoneA")), existing(obj("o", tenantTag("zoneA"))), Container.none)(using zoneB)(
          keep
        )
      )
      res must beLeft(contain("can't be modified"))
    }
  }

  "[save] the create-or-update decision" should {
    "take the creation path (and check the container) when the object does not exist" in {
      val res =
        written(logic().manageSave(obj("o", None), absent, container(obj("parent", tenantTag("zoneB"))))(using zoneA)(keep))
      res must beLeft(contain("can't be created or moved under 'parent'"))
    }

    "take the update path (and NOT check the container) when the object exists" in {
      val res = written(
        logic().manageSave(obj("o", None), existing(obj("o", tenantTag("zoneA"))), container(obj("parent", tenantTag("zoneB"))))(
          using zoneA
        )(
          keep
        )
      )
      res.map(_.security) must beRight(tenantTag("zoneA"))
    }
  }

  "[delete] an object" should {
    "be a no-op or an error when absent, as the operation states" in {
      written(logic().manageDelete(absent, IfAbsent.Noop("noop"))(using zoneA)(_ => "deleted".succeed)) must beRight("noop")
      written(logic().manageDelete(absent, IfAbsent.Fail("not found"))(using zoneA)(_ => "deleted".succeed)) must beLeft(
        "not found"
      )
    }

    "be refused when the actor has no write right on it" in {
      val res = {
        written(
          logic().manageDelete(existing(obj("o", tenantTag("zoneA"))), IfAbsent.Fail("absent"))(using zoneB)(_ =>
            "deleted".succeed
          )
        )
      }
      res must beLeft(contain("can't be deleted"))
    }

    "be refused to a read-only tenant, even on its own object" in {
      val res = written(
        logic().manageDelete(existing(obj("o", tenantTag("zoneA"))), IfAbsent.Fail("absent"))(using zoneAr)(_ =>
          "deleted".succeed
        )
      )
      res must beLeft(contain("can't be deleted"))
    }

    // a delete is a write: it obeys the same law as an update
    "be refused to a non-admin when the tenant feature is disabled" in {
      val res = written(
        logic(enabled = false)
          .manageDelete(existing(obj("o", Some(SecurityTag.Open))), IfAbsent.Fail("absent"))(using zoneA)(_ => "deleted".succeed)
      )
      res must beLeft(contain("can't be deleted"))
    }
  }

  "[move] an object" should {
    "check both the object and the destination container" in {
      val l   = logic()
      // can write the object, but not the destination
      val ko1 = written(
        l.manageMove(
          existing(obj("o", tenantTag("zoneA"))),
          container(obj("parent", tenantTag("zoneB"))),
          IfAbsent.Fail("absent")
        )(using
          zoneA
        )(keep)
      )
      // can write the destination, but not the object
      val ko2 = written(
        l.manageMove(
          existing(obj("o", tenantTag("zoneB"))),
          container(obj("parent", tenantTag("zoneA"))),
          IfAbsent.Fail("absent")
        )(using
          zoneA
        )(keep)
      )
      val ok  = written(
        l.manageMove(
          existing(obj("o", tenantTag("zoneA"))),
          container(obj("parent", tenantTag("zoneA"))),
          IfAbsent.Fail("absent")
        )(using
          zoneA
        )(keep)
      )
      (ko1 must beLeft) and (ko2 must beLeft) and (ok must beRight)
    }
  }

  "[system objects] managing a system object" should {
    val system = Obj("sys", tenantTag("zoneA"), system = true)

    "be refused to a tenant actor on create" in {
      written(logic().manageCreate(system, Container.none)(using zoneA)(keep)) must beLeft(contain("Only an administrator"))
    }
    "be refused to a tenant actor on update" in {
      written(logic().manageUpdate(system, existing(system), IfAbsent.Fail("absent"))(using zoneA)(keep)) must beLeft(
        contain("Only an administrator")
      )
    }
    "be refused to a tenant actor on delete" in {
      written(logic().manageDelete(existing(system), IfAbsent.Fail("absent"))(using zoneA)(keep)) must beLeft(
        contain("Only an administrator")
      )
    }
    "be allowed to an admin" in {
      written(logic().manageUpdate(system, existing(system), IfAbsent.Fail("absent"))(using admin)(keep)) must beRight
    }
    // creating a tenant object UNDER a system container stays allowed: a container check is not system-gated
    "not prevent creating a non-system object under a system container" in {
      val systemContainer = Obj("root", Some(SecurityTag.Open), system = true)
      written(logic().manageCreate(obj("o", None), container(systemContainer))(using zoneA)(keep)) must beRight
    }
  }

  "[feature disabled] a tenant-restricted actor" should {
    "not be able to write anything" in {
      val l = logic(enabled = false)
      (written(l.manageCreate(obj("o", None), Container.none)(using zoneA)(keep)) must beLeft) and
      (written(l.manageUpdate(obj("o", None), existing(obj("o", None)), IfAbsent.Fail("absent"))(using zoneA)(keep)) must beLeft)
    }

    "while an admin still can, and the existing tag is preserved" in {
      val res = written(
        logic(enabled = false).manageUpdate(obj("o", None), existing(obj("o", tenantTag("zoneA"))), IfAbsent.Fail("absent"))(using
          admin
        )(keep)
      )
      res.map(_.security) must beRight(tenantTag("zoneA"))
    }
  }

  "[change context] the action" should {
    "run with the grant restricted to the tenants the object was created with" in {
      val ccAX = cc("zoneA+X", TenantAccess(TenantId("zoneA")), TenantAccess(TenantId("zoneX")))
      val res  = written(
        logic().manageCreate(obj("o", None), Container.none)(using ccAX)(_ => summon[ChangeContext].accessGrant.serialize.succeed)
      )
      // zoneX does not exist, so it is not part of the context the action runs with
      res must beRight("zoneA")
    }
  }
}
