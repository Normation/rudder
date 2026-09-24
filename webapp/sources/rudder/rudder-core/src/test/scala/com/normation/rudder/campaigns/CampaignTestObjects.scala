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
import com.normation.rudder.tenants.SecurityTag
import com.softwaremill.quicklens.*
import zio.json.*
import zio.json.ast.Json

/*
 * A campaign type for the tests of the campaign repositories: a `Campaign` is a trait each family
 * implements, so a test needs one of its own, and three specs needed the same one.
 */
object RepoTestCampaignType extends CampaignType("test-campaign-repository")

final case class RepoTestCampaignDetails(name: String) extends CampaignDetails

final case class RepoTestCampaign(info: CampaignInfo, details: RepoTestCampaignDetails) extends Campaign {
  val campaignType: CampaignType = RepoTestCampaignType
  val version = 1
  def copyWithId(newId:      CampaignId):          Campaign = this.modify(_.info.id).setTo(newId)
  def withSecurity(security: Option[SecurityTag]): Campaign = this.modify(_.info.security).setTo(security)
  def setScheduleTimeZone(newScheduleTimeZone: ScheduleTimeZone): Campaign =
    this.modify(_.info.schedule).using(_.atTimeZone(newScheduleTimeZone))
}

object RepoTestCampaignTranslater extends JSONTranslateCampaign {
  import com.normation.rudder.campaigns.CampaignSerializer.*

  implicit val detailsDecoder:  JsonDecoder[RepoTestCampaignDetails] = DeriveJsonDecoder.gen
  implicit val campaignDecoder: JsonDecoder[RepoTestCampaign]        = DeriveJsonDecoder.gen
  implicit val detailsEncoder:  JsonEncoder[RepoTestCampaignDetails] = DeriveJsonEncoder.gen
  implicit val campaignEncoder: JsonEncoder[RepoTestCampaign]        = DeriveJsonEncoder.gen

  def read(): PartialFunction[(String, CampaignParsingInfo), IOResult[Campaign]] = {
    case (s, CampaignParsingInfo(RepoTestCampaignType, 1)) => s.fromJson[RepoTestCampaign].toIO
  }

  def getRawJson(): PartialFunction[Campaign, IOResult[Json]] = { case c: RepoTestCampaign => c.toJsonAST.toIO }

  def campaignType(): PartialFunction[String, CampaignType] = { case RepoTestCampaignType.value => RepoTestCampaignType }
}

object TestCampaignObjects {

  def serializer(): CampaignSerializer = {
    val s = new CampaignSerializer()
    s.addJsonTranslater(RepoTestCampaignTranslater)
    s
  }

  // a schedule every campaign of a test can use when the schedule is not what is being tested
  val someSchedule: CampaignSchedule = WeeklySchedule(DayTime(Monday, 3, 42), DayTime(Monday, 4, 42), None)

  def campaign(id: String, security: Option[SecurityTag] = None, schedule: CampaignSchedule = someSchedule): RepoTestCampaign = {
    RepoTestCampaign(
      CampaignInfo(CampaignId(id), s"campaign ${id}", "a campaign used in tests", Enabled, schedule, security),
      RepoTestCampaignDetails(id)
    )
  }
}
