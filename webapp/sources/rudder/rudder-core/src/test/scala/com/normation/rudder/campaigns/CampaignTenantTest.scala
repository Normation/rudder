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
import com.normation.eventlog.EventActor
import com.normation.rudder.tenants.*
import com.normation.zio.*
import com.softwaremill.quicklens.*
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*
import zio.Chunk
import zio.syntax.*

/*
 * A campaign is a tenant-scoped configuration object like a rule or a group: it carries a tag in its
 * `CampaignInfo`, reads are filtered to what the query context may see, and writes go through the write
 * law. These tests pin that law on the real (file based) repository.
 */
@RunWith(classOf[JUnitRunner])
class CampaignTenantTest extends Specification {

  // the repository is one directory shared by all these examples
  sequential

  //// test setup ////

  val campaignDir: File = File.newTemporaryDirectory("rudder-test-campaign-tenant-")

  val archiver: CampaignArchiver = new CampaignArchiver {
    override def saveCampaign(campaignId:     CampaignId)(implicit cc: ChangeContext): IOResult[Unit] = ().succeed
    override def deleteCampaign(campaignId:   CampaignId)(implicit cc: ChangeContext): IOResult[Unit] = ().succeed
    override def init(implicit changeContext: ChangeContext): IOResult[Unit] = ().succeed
    override def campaignPath: File = campaignDir
  }

  val tenantRepo: InMemoryTenantService = {
    val service = InMemoryTenantService.make(Set(TenantId("zoneA"), TenantId("zoneB"))).runNow
    service.setTenantEnabled(true).runNow
    service
  }

  val serializer: CampaignSerializer = TestCampaignObjects.serializer()

  val repo: CampaignRepository = {
    CampaignRepositoryImpl
      .make(campaignDir, archiver, serializer, NoopCampaignHooksRepository, new DefaultTenantCheckLogic(tenantRepo))
      .runNow
  }

  private def grant(accesses: TenantAccess*): QueryContext =
    QueryContext(EventActor("u"), TenantAccessGrant.ByTenants(Chunk.fromIterable(accesses)))

  val admin:   QueryContext = QueryContext.systemQC
  val zoneA:   QueryContext = grant(TenantAccess(TenantId("zoneA")))
  val zoneB:   QueryContext = grant(TenantAccess(TenantId("zoneB")))
  // read-only on zoneA: may see, may not change
  val zoneAro: QueryContext = grant(TenantAccess(TenantId("zoneA"), TenantPermission.Read))

  private def campaign(id: String): RepoTestCampaign = TestCampaignObjects.campaign(id)

  private def tagOf(c: Campaign): Option[SecurityTag] = c.info.security

  //// tests ////

  "[create] a campaign saved by a tenant user gets that user's tenants" >> {
    val saved = repo.save(campaign("c-create-a"))(using zoneA.newCC()).runNow
    tagOf(saved) must beEqualTo(Some(SecurityTag.ByTenants(Chunk(TenantId("zoneA")))))
  }

  "[create] a campaign saved by an admin is admin-only when it carries no tag" >> {
    val saved = repo.save(campaign("c-create-admin"))(using admin.newCC()).runNow
    tagOf(saved) must beNone
  }

  "[read] a tenant user only sees the campaigns of their tenants" >> {
    repo.save(campaign("c-read-a"))(using zoneA.newCC()).runNow
    repo.save(campaign("c-read-b"))(using zoneB.newCC()).runNow

    val seenByA = repo.getAll(Nil, Nil)(using zoneA).runNow.map(_.info.id.value)
    (seenByA must contain("c-read-a")) and (seenByA.contains("c-read-b") must beFalse)
  }

  "[read] an untagged (admin-only) campaign is invisible to a tenant user" >> {
    repo.save(campaign("c-read-admin"))(using admin.newCC()).runNow

    (repo.get(CampaignId("c-read-admin"))(using zoneA).runNow must beNone) and
    (repo.get(CampaignId("c-read-admin"))(using admin).runNow must beSome)
  }

  "[read] a campaign of another tenant reads as absent, not as an error" >> {
    repo.save(campaign("c-read-oracle"))(using zoneB.newCC()).runNow
    repo.get(CampaignId("c-read-oracle"))(using zoneA).runNow must beNone
  }

  "[update] a user of another tenant can not change a campaign" >> {
    repo.save(campaign("c-update"))(using zoneA.newCC()).runNow
    val updated = campaign("c-update").modify(_.info.name).setTo("renamed by zoneB")

    (repo.save(updated)(using zoneB.newCC()).either.runNow must beLeft) and
    (repo.get(CampaignId("c-update"))(using admin).runNow.map(_.info.name) must beSome("campaign c-update"))
  }

  "[update] a read-only user of the right tenant can not change a campaign either" >> {
    repo.save(campaign("c-update-ro"))(using zoneA.newCC()).runNow
    val updated = campaign("c-update-ro").modify(_.info.name).setTo("renamed by a reader")

    repo.save(updated)(using zoneAro.newCC()).either.runNow must beLeft
  }

  "[update] a tenant user does not change the tag of a campaign they update" >> {
    repo.save(campaign("c-update-tag"))(using zoneA.newCC()).runNow
    // submit a widened tag: the law keeps the existing one for a non-admin
    val widened = campaign("c-update-tag")
      .modify(_.info.security)
      .setTo(Some(SecurityTag.ByTenants(Chunk(TenantId("zoneA"), TenantId("zoneB")))))

    val saved = repo.save(widened)(using zoneA.newCC()).runNow
    tagOf(saved) must beEqualTo(Some(SecurityTag.ByTenants(Chunk(TenantId("zoneA")))))
  }

  "[delete] a user of another tenant can not delete a campaign" >> {
    repo.save(campaign("c-delete"))(using zoneA.newCC()).runNow

    // like an update: the campaign exists, the actor may not act on it
    (repo.delete(CampaignId("c-delete"))(using zoneB.newCC()).either.runNow must beLeft) and
    (repo.get(CampaignId("c-delete"))(using admin).runNow must beSome)
  }

  "[delete] a user of the right tenant deletes their campaign" >> {
    repo.save(campaign("c-delete-ok"))(using zoneA.newCC()).runNow

    repo.delete(CampaignId("c-delete-ok"))(using zoneA.newCC()).runNow
    repo.get(CampaignId("c-delete-ok"))(using admin).runNow must beNone
  }
}
