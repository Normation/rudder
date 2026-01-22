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
import com.softwaremill.quicklens.*
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
object NoDirectiveScheduleDetails extends CampaignDetails

// The only goal of this trait is to be able to provide a discriminator in json,
// zio-json allow to have discriminator only on sealed trait
@jsonDiscriminator("campaignType")
sealed trait DirectiveScheduleFamily extends Campaign {
  override def version:      Int             = 1
  override def details:      CampaignDetails = NoDirectiveScheduleDetails
  override def campaignType: CampaignType    = DirectiveScheduleType
}

@jsonHint(NoDirectiveScheduleDetails.value)
case class DirectiveSchedule(info: CampaignInfo) extends DirectiveScheduleFamily {
  override def copyWithId(newId: CampaignId): Campaign = this.modify(_.info.id).setTo(newId)
  override def setScheduleTimeZone(newScheduleTimeZone: ScheduleTimeZone): Campaign =
    this.modify(_.info.schedule).using(_.atTimeZone(newScheduleTimeZone))
}
