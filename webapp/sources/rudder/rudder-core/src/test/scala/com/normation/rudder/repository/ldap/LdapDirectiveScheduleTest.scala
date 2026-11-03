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

package com.normation.rudder.repository.ldap

import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersionHelper
import com.normation.eventlog.EventActor
import com.normation.rudder.campaigns.CampaignId
import com.normation.rudder.domain.Constants.ROOT_ACTIVE_TECHNIQUES
import com.normation.rudder.domain.policies.AddDirectiveDiff
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.ModifyDirectiveDiff
import com.normation.rudder.domain.policies.PolicyTypes
import com.normation.rudder.domain.policies.SimpleDiff
import com.normation.rudder.services.eventlog.EventLogDetailsServiceImpl
import com.normation.rudder.tenants.ChangeContext
import com.normation.rudder.tenants.QueryContext
import com.normation.zio.*
import net.liftweb.common.Full
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*

/*
 * Changing the schedule of a directive must be a regular, traced modification:
 * it used to make the save fail after the LDAP write ("Unknown diff attribute: scheduleId").
 */
@RunWith(classOf[JUnitRunner])
class LdapDirectiveScheduleTest extends Specification with SetupLdapRepositories {

  sequential

  given cc: ChangeContext = ChangeContext.newForRudder()

  val technique = TechniqueName("Create_file")
  val daily     = CampaignId("daily-schedule")
  val weekly    = CampaignId("weekly-schedule")

  val directive = Directive(
    DirectiveId(DirectiveUid("scheduled-directive")),
    TechniqueVersionHelper("1.0"),
    Map(),
    "scheduled directive",
    "",
    None,
    security = None,
    scheduleId = Some(daily)
  )

  lazy val activeTechnique = woDirectiveRepo
    .addTechniqueInUserLibrary(ROOT_ACTIVE_TECHNIQUES, technique, Nil, PolicyTypes.rudderBase, None)
    .runNow

  def save(d: Directive) = woDirectiveRepo.saveDirective(activeTechnique.id, d).runNow

  def scheduleDiff(d: Directive) = save(d) match {
    case Some(diff: ModifyDirectiveDiff) => (diff.modScheduleId, diff.needDeployment)
    case other                           => throw new IllegalArgumentException(s"Expected a modify diff, got: ${other}")
  }

  // details service only needs the parameter unserialiser when parameters change
  val eventLogDetails = new EventLogDetailsServiceImpl(null, null, null, null, null, null, null, null, null, null)

  "Saving a scheduled directive" should {
    "keep the schedule in the add diff" in {
      save(directive) must beLike { case Some(AddDirectiveDiff(_, d)) => d.scheduleId must beSome(daily) }
    }
    "produce a schedule diff that triggers a generation when the schedule changes" in {
      scheduleDiff(directive.copy(scheduleId = Some(weekly))) must beEqualTo(
        (Some(SimpleDiff(Some(daily), Some(weekly))), true)
      )
    }
    "produce a schedule diff when the schedule is removed" in {
      scheduleDiff(directive.copy(scheduleId = None)) must beEqualTo((Some(SimpleDiff(Some(weekly), None)), true))
    }
    "produce a schedule diff when a schedule is added" in {
      scheduleDiff(directive) must beEqualTo((Some(SimpleDiff(None, Some(daily))), true))
    }
    "have the new schedule stored" in {
      roDirectiveRepo.getDirective(directive.id.uid)(using QueryContext.systemQC).runNow.flatMap(_.scheduleId) must beSome(daily)
    }
  }

  "The modify directive event log" should {
    "keep the schedule diff through serialisation" in {
      val diff = ModifyDirectiveDiff
        .emptyMod(technique, directive.id, directive.name)
        .copy(modScheduleId = Some(SimpleDiff(Some(daily), None)))
      val log  = eventLogRepo.eventLogFactory.getModifyDirectiveFromDiff(
        principal = EventActor("test"),
        modifyDiff = diff,
        reason = None,
        securityTag = None
      )

      eventLogDetails.getDirectiveModifyDetails(log.details) must beEqualTo(Full(diff))
    }
  }
}
