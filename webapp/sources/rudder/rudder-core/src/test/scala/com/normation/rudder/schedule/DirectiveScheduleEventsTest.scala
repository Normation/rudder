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

package com.normation.rudder.schedule

import com.normation.errors.*
import com.normation.rudder.campaigns.*
import com.normation.zio.UnsafeRun
import com.softwaremill.quicklens.*
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.junit.runner.RunWith
import org.specs2.mutable.*
import org.specs2.runner.JUnitRunner
import zio.Ref
import zio.syntax.*

/*
 * Test the generation of directive schedule events: bounds (horizon, min/max events),
 * event id determinism, horizon renewal quantization, and the schedule management
 * service persisting extended horizons.
 */
@RunWith(classOf[JUnitRunner])
class DirectiveScheduleEventsTest extends Specification {

  val utc: Option[ScheduleTimeZone] = Some(ScheduleTimeZone("UTC"))

  // all dates in june 2026, UTC. The daily schedule is between 4:00 and 6:00 UTC.
  def date(day: Int, hour: Int, minute: Int = 0): Instant = {
    OffsetDateTime.of(2026, 6, day, hour, minute, 0, 0, ZoneOffset.UTC).toInstant
  }

  val daily:   Daily               = Daily(Time(4, 0), Time(6, 0), utc)
  val monthly: MonthlySchedule     = MonthlySchedule(First, DayTime(Monday, 4, 0), DayTime(Monday, 6, 0), utc)
  val bounds:  ScheduleEventBounds = ScheduleEventBounds(horizonDays = 30, minEvents = 3, maxEvents = 100)

  val now: Instant = date(10, 5) // inside the day 10 window

  "occurrence windows generation" should {
    "include the currently open window" in {
      DirectiveScheduleEvents.occurrencesFrom(daily, now, 3) must beRight(
        List(
          ScheduleWindow(date(10, 4), date(10, 6)),
          ScheduleWindow(date(11, 4), date(11, 6)),
          ScheduleWindow(date(12, 4), date(12, 6))
        )
      )
    }

    "cover the full horizon for a daily schedule" in {
      val target = DirectiveScheduleEvents.targetWindows(daily, now, bounds)
      // from june 10 to july 10 included: 31 daily windows
      target.map(_.size) must beRight(31)
    }

    "be capped by max events" in {
      val target = DirectiveScheduleEvents.targetWindows(daily, now, bounds.copy(maxEvents = 10))
      target.map(_.size) must beRight(10)
    }

    "generate at least min events for infrequent schedules" in {
      // a monthly schedule has at most 2 occurrences in a 30 days horizon
      val target = DirectiveScheduleEvents.targetWindows(monthly, now, bounds)
      target.map(_.size) must beRight(3)
    }

    "generate exactly one event for a future one shot" in {
      val oneShot = {
        import com.normation.utils.DateFormaterService.toJodaDateTime
        OneShot(date(20, 4).toJodaDateTime, date(20, 6).toJodaDateTime)
      }
      DirectiveScheduleEvents.targetWindows(oneShot, now, bounds) must beRight(
        List(ScheduleWindow(date(20, 4), date(20, 6)))
      )
    }
  }

  "event ids" should {
    val id = CampaignId("sched1")
    val w1 = ScheduleWindow(date(10, 4), date(10, 6))
    val w2 = ScheduleWindow(date(11, 4), date(11, 6))

    "be deterministic for a same occurrence" in {
      DirectiveScheduleEvents.eventId(id, w1) === DirectiveScheduleEvents.eventId(id, w1)
    }
    "differ between occurrences" in {
      DirectiveScheduleEvents.eventId(id, w1) !== DirectiveScheduleEvents.eventId(id, w2)
    }
    "differ between schedules" in {
      DirectiveScheduleEvents.eventId(id, w1) !== DirectiveScheduleEvents.eventId(CampaignId("sched2"), w1)
    }
  }

  "horizon renewal" should {
    val target = DirectiveScheduleEvents.targetWindows(daily, now, bounds).getOrElse(throw new Exception("test data"))

    "be needed when no horizon was generated yet" in {
      DirectiveScheduleEvents.needsRenewal(None, target, now, bounds) must beTrue
    }
    "not be needed just after generation" in {
      DirectiveScheduleEvents.needsRenewal(Some(target.last.end), target, now, bounds) must beFalse
    }
    "be needed when less than half the coverage remains" in {
      DirectiveScheduleEvents.needsRenewal(Some(now.plus(java.time.Duration.ofDays(10))), target, now, bounds) must beTrue
    }
    "not be needed when more than half the coverage remains" in {
      DirectiveScheduleEvents.needsRenewal(Some(now.plus(java.time.Duration.ofDays(20))), target, now, bounds) must beFalse
    }
    "not be needed for a one shot already generated" in {
      val w = List(ScheduleWindow(date(20, 4), date(20, 6)))
      DirectiveScheduleEvents.needsRenewal(Some(date(20, 6)), w, now, bounds) must beFalse
    }
    "not be needed when there is nothing to generate" in {
      DirectiveScheduleEvents.needsRenewal(None, Nil, now, bounds) must beFalse
    }
  }

  // an in-memory campaign repository to check persistence of extended horizons
  class InMemoryCampaignRepository(initial: List[Campaign]) extends CampaignRepository {
    val campaigns: Ref[Map[CampaignId, Campaign]] = Ref.make(initial.map(c => (c.info.id, c)).toMap).runNow
    val saveCount: Ref[Int]                       = Ref.make(0).runNow

    override def getAll(typeFilter: List[CampaignType], statusFilter: List[CampaignStatusValue]): IOResult[List[Campaign]] = {
      campaigns.get.map(_.values.toList.filter(c => typeFilter.isEmpty || typeFilter.contains(c.campaignType)))
    }
    override def get(id:    CampaignId): IOResult[Option[Campaign]] = campaigns.get.map(_.get(id))
    override def delete(id: CampaignId): IOResult[Unit]             = campaigns.update(_ - id)
    override def save(c: Campaign): IOResult[Campaign] = {
      saveCount.update(_ + 1) *> campaigns.update(_ + (c.info.id -> c)) *> c.succeed
    }
  }

  "schedule management" should {
    val campaign = SystemDirectiveSchedule.dailyOn4UTC

    def newManagement(c: DirectiveSchedule): (InMemoryCampaignRepository, ScheduleManagementImpl) = {
      val repo = new InMemoryCampaignRepository(c :: Nil)
      (repo, new ScheduleManagementImpl(repo, bounds))
    }

    "extend and persist the horizon on first generation" in {
      val (repo, mgmt) = newManagement(campaign)
      val data         = mgmt.updateSchedules(now, campaign :: Nil).runNow

      val saved = repo.get(campaign.info.id).runNow.collect { case c: DirectiveSchedule => c }
      (data.updated.keySet === Set(campaign.info.id)) and
      (data.events(campaign.info.id).size === 31) and
      (saved.flatMap(_.details.maxDate) === Some(date(10, 6).plus(java.time.Duration.ofDays(30))))
    }

    "be stable on a second generation" in {
      val (repo, mgmt) = newManagement(campaign)
      val first        = mgmt.updateSchedules(now, campaign :: Nil).runNow
      val updated      = repo.get(campaign.info.id).runNow.collect { case c: DirectiveSchedule => c }.get
      val second       = mgmt.updateSchedules(now.plus(java.time.Duration.ofHours(5)), updated :: Nil).runNow

      (second.upToDate.keySet === Set(campaign.info.id)) and
      (second.updated must beEmpty) and
      (second.events(campaign.info.id) === first.events(campaign.info.id).drop(1)) // day 10 window is closed 5h later
    }

    "not generate events for a disabled schedule" in {
      val disabled  = campaign.modify(_.info.status).setTo(Disabled("test"))
      val (_, mgmt) = newManagement(disabled)
      val data      = mgmt.updateSchedules(now, disabled :: Nil).runNow

      (data.events(campaign.info.id) must beEmpty) and
      (data.all(campaign.info.id).e must beFalse)
    }

    "emit non expired on-demand one shots, even when the schedule is disabled" in {
      val live     = DirectiveScheduleOneShot("live-event", date(10, 4, 50), date(10, 5, 20))
      val expired  = DirectiveScheduleOneShot("expired-event", date(9, 4), date(9, 4, 30))
      val disabled = campaign
        .modify(_.info.status)
        .setTo(Disabled("test"))
        .modify(_.details.oneShots)
        .setTo(List(live, expired))

      val (_, mgmt) = newManagement(disabled)
      val data      = mgmt.updateSchedules(now, disabled :: Nil).runNow

      data.events(campaign.info.id).map(_.eventId) === List("live-event")
    }

    "prune expired on-demand one shots when extending the horizon" in {
      val expired    = DirectiveScheduleOneShot("expired-event", date(9, 4), date(9, 4, 30))
      val withEvents = campaign.modify(_.details.oneShots).setTo(List(expired))

      val (repo, mgmt) = newManagement(withEvents)
      val data         = mgmt.updateSchedules(now, withEvents :: Nil).runNow
      val saved        = repo.get(campaign.info.id).runNow.collect { case c: DirectiveSchedule => c }

      (data.events(campaign.info.id).map(_.eventId) must not contain "expired-event") and
      (saved.map(_.details.oneShots) === Some(Nil))
    }

    "add an on-demand one shot with a stable stored event id" in {
      // outside the daily 4:00-6:00 window, so the dedupe does not kick in
      val at           = date(10, 7)
      val (repo, mgmt) = newManagement(campaign)
      val oneShot      = mgmt.addOneShotEvent(campaign.info.id, at, java.time.Duration.ofMinutes(30)).runNow
      val saved        = repo.get(campaign.info.id).runNow.collect { case c: DirectiveSchedule => c }.get
      val data         = mgmt.updateSchedules(at, saved :: Nil).runNow

      (oneShot.end === at.plus(java.time.Duration.ofMinutes(30))) and
      (saved.details.oneShots.map(_.eventId) === List(oneShot.eventId)) and
      (data.events(campaign.info.id).map(_.eventId) must contain(oneShot.eventId))
    }

    "drop an on-demand one shot when a scheduled window is already active" in {
      // now = day 10, 5:00, inside the daily 4:00-6:00 window
      val (repo, mgmt) = newManagement(campaign)
      val oneShot      = mgmt.addOneShotEvent(campaign.info.id, now, java.time.Duration.ofMinutes(30)).runNow
      val saved        = repo.get(campaign.info.id).runNow.collect { case c: DirectiveSchedule => c }.get

      // the returned window is the active recurrent one, and nothing was persisted
      (oneShot.start === date(10, 4)) and
      (oneShot.end === date(10, 6)) and
      (saved.details.oneShots must beEmpty)
    }

    "drop an on-demand one shot when another on-demand window is already active" in {
      val at       = date(10, 7)
      val existing = DirectiveScheduleOneShot("existing-event", date(10, 6, 45), date(10, 7, 15))
      val withOs   = campaign.modify(_.details.oneShots).setTo(List(existing))

      val (repo, mgmt) = newManagement(withOs)
      val oneShot      = mgmt.addOneShotEvent(campaign.info.id, at, java.time.Duration.ofMinutes(30)).runNow
      val saved        = repo.get(campaign.info.id).runNow.collect { case c: DirectiveSchedule => c }.get

      (oneShot === existing) and
      (saved.details.oneShots === List(existing))
    }

    "not dedupe an on-demand one shot against the recurrent window when the schedule is disabled" in {
      // disabled schedule means the recurrent window generates no run: on-demand must proceed
      val disabled = campaign.modify(_.info.status).setTo(Disabled("test"))

      val (repo, mgmt) = newManagement(disabled)
      val oneShot      = mgmt.addOneShotEvent(campaign.info.id, now, java.time.Duration.ofMinutes(30)).runNow
      val saved        = repo.get(campaign.info.id).runNow.collect { case c: DirectiveSchedule => c }.get

      (oneShot.end === now.plus(java.time.Duration.ofMinutes(30))) and
      (saved.details.oneShots.map(_.eventId) === List(oneShot.eventId))
    }

    "splay events per node inside the first half of the window, deterministically" in {
      val window = DirectiveScheduleEvent(campaign.info.id, "eid", "generic", "test", date(10, 4), date(10, 6))
      val nodeA  = com.normation.inventory.domain.NodeId("aaaaaaaa-1111-2222-3333-444444444444")
      val nodeB  = com.normation.inventory.domain.NodeId("bbbbbbbb-1111-2222-3333-444444444444")

      val splayedA  = DirectiveScheduleEvents.splayEvents(nodeA, List(window))
      val splayedA2 = DirectiveScheduleEvents.splayEvents(nodeA, List(window))
      val splayedB  = DirectiveScheduleEvents.splayEvents(nodeB, List(window))

      (splayedA === splayedA2) and                                               // deterministic for a given node
      (splayedA.head.notBefore must not(beEqualTo(splayedB.head.notBefore))) and // differs across nodes
      (splayedA.head.notAfter === window.notAfter) and                           // notAfter unchanged
      (splayedA.forall(e => !e.notBefore.isBefore(window.notBefore)) must beTrue) and
      (splayedA.forall(e => !e.notBefore.isAfter(date(10, 5))) must beTrue) and  // within first half
      (splayedB.forall(e => !e.notBefore.isBefore(window.notBefore) && !e.notBefore.isAfter(date(10, 5))) must beTrue)
    }
  }

  "directive schedule campaign serialization" should {
    "round trip through the campaign serializer" in {
      val serializer = new CampaignSerializer()
      serializer.addJsonTranslater(DirectiveScheduleSerializer)

      val campaign = SystemDirectiveSchedule.dailyOn4UTC.modify(_.details.maxDate).setTo(Some(date(30, 6)))
      val res      = serializer.serialize(campaign).flatMap(serializer.parse).either.runNow

      res === Right(campaign)
    }
  }
}
