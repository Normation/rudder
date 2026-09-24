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

import better.files.File
import com.normation.errors.*
import com.normation.rudder.tenants.*
import com.normation.zio.*
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*
import zio.syntax.*

/*
 * A campaign is a file named after its id and a directory of hooks named after it too, and its schedule is
 * read at every policy generation. So an id has to be a file name, and a schedule has to be computable:
 * both are checked before anything is written, not when it is too late.
 */
@RunWith(classOf[JUnitRunner])
class CampaignRepositoryGuardsTest extends Specification {

  sequential

  val campaignDir: File = File.newTemporaryDirectory("rudder-test-campaign-guards-")

  val archiver: CampaignArchiver = new CampaignArchiver {
    override def saveCampaign(campaignId:     CampaignId)(implicit cc: ChangeContext): IOResult[Unit] = ().succeed
    override def deleteCampaign(campaignId:   CampaignId)(implicit cc: ChangeContext): IOResult[Unit] = ().succeed
    override def init(implicit changeContext: ChangeContext): IOResult[Unit] = ().succeed
    override def campaignPath: File = campaignDir
  }

  val serializer: CampaignSerializer = TestCampaignObjects.serializer()

  val repo: CampaignRepositoryImpl = {
    CampaignRepositoryImpl
      .make(
        campaignDir,
        archiver,
        serializer,
        NoopCampaignHooksRepository,
        new DefaultTenantCheckLogic(InMemoryTenantService.make(Nil).runNow)
      )
      .runNow
  }

  given cc: ChangeContext = ChangeContext.newForRudder()
  given qc: QueryContext  = QueryContext.systemQC

  private def campaign(id: String, schedule: CampaignSchedule = TestCampaignObjects.someSchedule): RepoTestCampaign = {
    TestCampaignObjects.campaign(id, schedule = schedule)
  }

  "[id] a campaign id is a file name" >> {
    (CampaignId.parse("c0") must beRight) and
    (CampaignId.parse("c0+rev1") must beRight) and
    (CampaignId.parse("") must beLeft) and
    (CampaignId.parse("..") must beLeft) and
    (CampaignId.parse("../../etc/passwd") must beLeft) and
    (CampaignId.parse("hooks/../../evil") must beLeft)
  }

  "[id] an id built in code can not escape the campaign directory either" >> {
    // `CampaignId.parse` is the guard for user input; the repository is the one for everything else
    val escaping = campaign("../escaped")

    (repo.save(escaping).either.runNow must beLeft) and
    (repo.get(CampaignId("../escaped")).either.runNow must beLeft) and
    ((campaignDir.parent / "escaped.json").exists must beFalse)
  }

  "[schedule] a one shot that ends before it starts is refused" >> {
    val now = DateTime.now(DateTimeZone.UTC)

    (repo.save(campaign("c-oneshot-ko", OneShot(now.plusHours(2), now))).either.runNow must beLeft) and
    (repo.save(campaign("c-oneshot-ok", OneShot(now, now.plusHours(2)))).either.runNow must beRight)
  }

  "[schedule] a repetition of less than one month, or of more than ten years, is refused" >> {
    def every(n: Int) = NMonthlySchedule(First, DayTime(Monday, 3, 0), DayTime(Monday, 4, 0), None, n)

    (repo.save(campaign("c-freq-0", every(0))).either.runNow must beLeft) and
    (repo.save(campaign("c-freq-max", every(Int.MaxValue))).either.runNow must beLeft) and
    (repo.save(campaign("c-freq-ok", every(3))).either.runNow must beRight)
  }

  "[schedule] a time of day that is not one is refused" >> {
    repo
      .save(campaign("c-bad-hour", WeeklySchedule(DayTime(Monday, 42, 0), DayTime(Monday, 4, 0), None)))
      .either
      .runNow must beLeft
  }

  "[locks] asking for campaigns that do not exist does not grow the lock map" >> {
    val before = repo.lockCount.runNow
    (1 to 10).foreach(i => repo.get(CampaignId(s"never-existed-${i}")).runNow)

    repo.lockCount.runNow must beEqualTo(before)
  }

  "[locks] deleting a campaign drops its lock" >> {
    repo.save(campaign("c-lock")).runNow
    val withCampaign = repo.lockCount.runNow

    repo.delete(CampaignId("c-lock")).runNow

    (withCampaign must beGreaterThan(0)) and (repo.lockCount.runNow must beEqualTo(withCampaign - 1))
  }
}
