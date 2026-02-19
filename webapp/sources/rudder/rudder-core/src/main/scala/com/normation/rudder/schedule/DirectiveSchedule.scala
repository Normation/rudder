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

import com.normation.rudder.campaigns.*
import com.normation.rudder.campaigns.CampaignSerializer.*
import com.softwaremill.quicklens.*
import io.scalaland.chimney.*
import java.time.Instant
import zio.json.JsonCodec
import zio.json.jsonDiscriminator
import zio.json.jsonHint

/*
 * A directive schedule is a very particular schedule type which
 * control the fact that a directive is enabled or disabled during a given run on the
 * agent side. But on the rudder compliance side, the fact that the directive is
 * disabled keeps the previous compliance (if any).
 */

object DirectiveScheduleType extends CampaignType("directive-schedule")

// no details for directive schedule
// maxDate : date up to which events are generated (may be none before first policy generation for that schedule).
//           Must be consistent for all nodes.
case class DirectiveScheduleDetails(
    scheduleType: String, // for documantation: benchmarks, system-update, etc
    maxDate:      Option[Instant]
) extends CampaignDetails
    derives JsonCodec

object DirectiveScheduleDetails {
  def empty = DirectiveScheduleDetails("generic", None)
}

// The only goal of this trait is to be able to provide a discriminator in json,
// zio-json allow to have discriminator only on sealed trait
@jsonDiscriminator("campaignType")
sealed trait DirectiveScheduleFamily extends Campaign derives JsonCodec {
  override def version:      Int          = 1
  override def campaignType: CampaignType = DirectiveScheduleType
}

@jsonHint(DirectiveScheduleType.value)
case class DirectiveSchedule(info: CampaignInfo, details: DirectiveScheduleDetails = DirectiveScheduleDetails.empty)
    extends DirectiveScheduleFamily derives JsonCodec {
  override def copyWithId(newId: CampaignId): Campaign = this.modify(_.info.id).setTo(newId)
  override def setScheduleTimeZone(newScheduleTimeZone: ScheduleTimeZone): Campaign =
    this.modify(_.info.schedule).using(_.atTimeZone(newScheduleTimeZone))
}

object DirectiveSchedule {

  // transform into json short representation of directive schedules
  implicit val transformDirectiveSchedule: Transformer[DirectiveSchedule, JsonDirectiveSchedule] = {
    Transformer
      .define[DirectiveSchedule, JsonDirectiveSchedule]
      .withFieldComputed(_.id, _.info.id)
      .withFieldComputed(_.e, _.info.status.value == CampaignStatusValue.Enabled)
      .withFieldComputed(_.s, _.info.schedule)
      .withFieldComputed(_.d, _.details.maxDate)
      .buildTransformer
  }
}

/*
 * We need a short version of directive schedule to be kept in node configuration / expected reports
 */
case class JsonDirectiveSchedule(
    id: CampaignId,
    e:  Boolean,         // enabled == true <=> status == enabled, else false
    d:  Option[Instant], // max date up to which events were generated for that schedule
    s:  CampaignSchedule // that's a complicated data format, keep it like that
) derives JsonCodec

/*
 * A schedule event that will be save in rudder.json for the module
 */
case class DirectiveScheduleEvent(
    id:        CampaignId,
    eventId:   String,
    eventType: String, // for documentation, "benchmarks", etc
    name:      String, // schedule name + event information
    notBefore: Instant,
    notAfter:  Instant
) derives JsonCodec
