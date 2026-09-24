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

package com.normation.rudder.campaigns

import com.normation.errors.*
import com.normation.eventlog.EventActor
import com.normation.rudder.campaigns.TestCampaignObjects.campaign
import com.normation.rudder.db.DBCommon
import com.normation.rudder.tenants.*
import com.normation.zio.*
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.junit.runner.*
import org.specs2.runner.*
import zio.*
import zio.Chunk

/*
 * A campaign event carries no tenant tag: what scopes it is the campaign it belongs to. That scoping is in
 * SQL (the query pages, so it can not be a post-filter), which is why it needs a real database to be tested.
 *
 * This runs only with `-Dtest.postgres=true` (see `DBCommon`); it is skipped otherwise.
 */
@RunWith(classOf[JUnitRunner])
class CampaignEventRepositoryTest extends DBCommon {

  sequential

  //// tenants ////

  val tenantRepo:  InMemoryTenantService = {
    val service = InMemoryTenantService.make(Set(TenantId("zoneA"), TenantId("zoneB"), TenantId("zoneC"))).runNow
    service.setTenantEnabled(true).runNow
    service
  }
  val checkTenant: TenantCheckLogic      = new DefaultTenantCheckLogic(tenantRepo)

  private def grant(accesses: TenantAccess*): QueryContext =
    QueryContext(EventActor("u"), TenantAccessGrant.ByTenants(Chunk.fromIterable(accesses)))

  val admin:   QueryContext = QueryContext.systemQC
  val zoneA:   QueryContext = grant(TenantAccess(TenantId("zoneA")))
  val zoneB:   QueryContext = grant(TenantAccess(TenantId("zoneB")))
  // sees nothing: a tenant with no campaign of its own, which is the state of a brand new tenant
  val zoneC:   QueryContext = grant(TenantAccess(TenantId("zoneC")))
  // may see zoneA, may not write it
  val zoneAro: QueryContext = grant(TenantAccess(TenantId("zoneA"), TenantPermission.Read))

  private def tenantTag(ids: String*): Option[SecurityTag] =
    Some(SecurityTag.ByTenants(Chunk.fromIterable(ids.map(TenantId(_)))))

  //// campaigns: in memory, but filtered by the real tenant logic - that is what the event repository asks ////

  val campaignA:     RepoTestCampaign = campaign("c-zoneA", tenantTag("zoneA"))
  val campaignB:     RepoTestCampaign = campaign("c-zoneB", tenantTag("zoneB"))
  val campaignAdmin: RepoTestCampaign = campaign("c-admin")

  object campaignRepo extends CampaignRepository {
    val campaigns: Ref[Map[CampaignId, Campaign]] = Ref
      .make(
        Map[CampaignId, Campaign](
          campaignA.info.id     -> campaignA,
          campaignB.info.id     -> campaignB,
          campaignAdmin.info.id -> campaignAdmin
        )
      )
      .runNow

    override def getAll(typeFilter: List[CampaignType], statusFilter: List[CampaignStatusValue])(using
        qc: QueryContext
    ): IOResult[List[Campaign]] = campaigns.get.map(cs => checkTenant.filter(cs.values.toList))

    override def get(id: CampaignId)(using qc: QueryContext): IOResult[Option[Campaign]] =
      campaigns.get.map(cs => checkTenant.flatMap(cs.get(id)))

    override def save(c: Campaign)(using cc: ChangeContext): IOResult[Campaign] =
      campaigns.update(_ + (c.info.id -> c)).as(c)

    override def delete(id: CampaignId)(using cc: ChangeContext): IOResult[Unit] = campaigns.update(_ - id)
  }

  lazy val repo = new CampaignEventRepositoryImpl(doobie, TestCampaignObjects.serializer(), checkTenant, campaignRepo)

  //// events ////

  private val t0 = new DateTime(0, DateTimeZone.UTC)

  private def event(id: String, c: Campaign, hour: Int): CampaignEvent = {
    CampaignEvent(
      CampaignEventId(id),
      c.info.id,
      s"event ${id}",
      CampaignEventState.Finished,
      t0.plusHours(hour),
      t0.plusHours(hour + 1),
      c.campaignType
    )
  }

  // three events for zoneA so that paging has something to page, one for each other campaign
  val eventsA:    List[CampaignEvent] = List(event("e-a1", campaignA, 1), event("e-a2", campaignA, 2), event("e-a3", campaignA, 3))
  val eventB:     CampaignEvent       = event("e-b1", campaignB, 4)
  val eventAdmin: CampaignEvent       = event("e-admin1", campaignAdmin, 5)

  // the events are put in place as an administrator: what is under test is who may then see and change them
  private def resetEvents(): Unit = {
    given cc: ChangeContext = admin.newCC()
    // `deleteEvent` with no criterion at all is a no-op by design, so we delete campaign by campaign
    List(campaignA, campaignB, campaignAdmin).foreach(c => repo.deleteEvent(campaignId = Some(c.info.id)).runNow)
    (eventsA ++ List(eventB, eventAdmin)).foreach(e => repo.saveCampaignEvent(e).runNow)
  }

  override def beforeAll(): Unit = {
    super.beforeAll()
    if (doDatabaseConnection) resetEvents()
  }

  //// tests ////

  "[save] an event is written for a campaign the actor may act on" >> {
    val e = event("e-a-new", campaignA, 6)

    (repo.saveCampaignEvent(e)(using zoneA.newCC()).either.runNow must beRight) and
    (repo.get(e.id)(using admin).runNow must beSome)
  }

  "[save] an event is refused for a campaign the actor can not see" >> {
    val e = event("e-a-forged", campaignA, 7)

    (repo.saveCampaignEvent(e)(using zoneB.newCC()).either.runNow must beLeft) and
    (repo.get(e.id)(using admin).runNow must beNone)
  }

  "[save] an event is refused for a campaign that does not exist" >> {
    val e = CampaignEvent(
      CampaignEventId("e-ghost"),
      CampaignId("no-such-campaign"),
      "ghost",
      CampaignEventState.Finished,
      t0,
      t0.plusHours(1),
      RepoTestCampaignType
    )

    repo.saveCampaignEvent(e)(using admin.newCC()).either.runNow must beLeft
  }

  "[get] an event of another tenant's campaign reads as absent, it is not an error" >> {
    resetEvents()

    (repo.get(eventB.id)(using zoneA).runNow must beNone) and
    (repo.get(eventB.id)(using zoneB).runNow must beSome) and
    (repo.get(eventB.id)(using admin).runNow must beSome)
  }

  "[list] only the events of the campaigns the actor sees are listed" >> {
    resetEvents()
    val seenByA = repo.getWithCriteria(order = None, asc = None)(using zoneA).runNow.map(_.id.value)

    (seenByA must containTheSameElementsAs(eventsA.map(_.id.value))) and
    (seenByA.contains(eventB.id.value) must beFalse) and
    (seenByA.contains(eventAdmin.id.value) must beFalse)
  }

  /*
   * The regression this test exists for: an empty set of visible campaigns must mean 'nothing', never
   * 'no filter'. A tenant that owns no campaign is the normal state of a new tenant.
   */
  "[list] an actor who sees no campaign gets no event, not every event" >> {
    resetEvents()

    (repo.getWithCriteria(order = None, asc = None)(using zoneC).runNow must beEmpty) and
    (repo.getWithCriteria(order = None, asc = None)(using admin).runNow must haveSize(5))
  }

  "[list] paging pages over what the actor sees" >> {
    resetEvents()
    val firstTwo = repo.getWithCriteria(limit = Some(2), order = None, asc = None)(using zoneA).runNow
    val lastOne  = repo.getWithCriteria(limit = Some(2), offset = Some(2), order = None, asc = None)(using zoneA).runNow

    (firstTwo must haveSize(2)) and
    (lastOne must haveSize(1)) and
    ((firstTwo ++ lastOne).map(_.id.value) must containTheSameElementsAs(eventsA.map(_.id.value)))
  }

  "[count] a campaign the actor can not see has no event" >> {
    resetEvents()

    (repo.numberOfEventsByCampaign(campaignA.info.id)(using zoneA).runNow must beEqualTo(3)) and
    (repo.numberOfEventsByCampaign(campaignA.info.id)(using zoneB).runNow must beEqualTo(0)) and
    (repo.numberOfEventsByCampaign(campaignA.info.id)(using admin).runNow must beEqualTo(3))
  }

  "[delete] deleting the events of another tenant's campaign does nothing" >> {
    resetEvents()

    repo.deleteEvent(campaignId = Some(campaignB.info.id))(using zoneA.newCC()).runNow

    repo.get(eventB.id)(using admin).runNow must beSome
  }

  "[delete] a read-only grant on the campaign does not delete its events" >> {
    resetEvents()

    repo.deleteEvent(campaignId = Some(campaignA.info.id))(using zoneAro.newCC()).runNow

    repo.numberOfEventsByCampaign(campaignA.info.id)(using admin).runNow must beEqualTo(3)
  }

  "[delete] the tenant that may act on the campaign deletes its events" >> {
    resetEvents()

    repo.deleteEvent(campaignId = Some(campaignA.info.id))(using zoneA.newCC()).runNow

    (repo.numberOfEventsByCampaign(campaignA.info.id)(using admin).runNow must beEqualTo(0)) and
    // and nothing else was touched
    (repo.get(eventB.id)(using admin).runNow must beSome)
  }
}
