/*
*************************************************************************************
* Copyright 2022 Normation SAS
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

import com.normation.GitVersion
import com.normation.GitVersion.Revision
import com.normation.NamedZioLogger
import org.joda.time.DateTime
import zio.json.jsonDiscriminator
import zio.json.jsonField
import zio.json.jsonHint

import scala.concurrent.duration.Duration


trait Campaign {
  def info : CampaignInfo
  def details : CampaignDetails
  def campaignType : CampaignType
  def copyWithId(newId : CampaignId) : Campaign
}

case class CampaignInfo (
    id : CampaignId
  , name : String
  , description : String
  , status : CampaignStatus
  , schedule : CampaignSchedule
  , duration: Duration
)

case class CampaignId (value : String, rev: Revision = GitVersion.DEFAULT_REV) {
  def serialize: String = rev match {
    case GitVersion.DEFAULT_REV => value
    case rev                    => s"${value}+${rev.value}"
  }

  def withDefaultRev: CampaignId = this.copy(rev = GitVersion.DEFAULT_REV)
}
object CampaignId {
  // parse a directiveId which was serialize by "id.serialize"
  def parse(s: String) : Either[String, CampaignId] = {
    GitVersion.parseUidRev(s).map { case (id, rev) =>
      CampaignId(id, rev)
    }
  }
}

@jsonDiscriminator("value")
sealed trait CampaignStatus
@jsonHint("enabled")
case object Enabled  extends CampaignStatus
@jsonHint("disabled")
case object Disabled extends CampaignStatus
@jsonHint("archived")
case class Archived(date : DateTime) extends CampaignStatus

@jsonDiscriminator("type")
sealed trait CampaignSchedule

sealed trait MonthlySchedulePosition
// they need to be encapsulated in MonthlySchedulePosition object
case object First extends MonthlySchedulePosition
case object Second extends MonthlySchedulePosition
case object Third extends  MonthlySchedulePosition
case object Last extends  MonthlySchedulePosition
case object SecondLast extends MonthlySchedulePosition

sealed trait DayOfWeek {
  def value : Int
}
// they need to be encapsulated in DayOfWeek object
case object Monday extends DayOfWeek {
  val value = 1
}
case object Tuesday extends DayOfWeek{
  val value = 2
}
case object Wednesday extends DayOfWeek{
  val value = 3
}
case object Thursday extends DayOfWeek{
  val value = 4
}
case object Friday extends DayOfWeek{
  val value = 5
}
case object Saturday extends DayOfWeek{
  val value = 6
}
case object Sunday extends DayOfWeek{
  val value = 7
}

@jsonHint("monthly")
case class MonthlySchedule(
    @jsonField("position")
    monthlySchedulePosition: MonthlySchedulePosition
  , day : DayOfWeek
  , startHour : Int
  , startMinute : Int) extends CampaignSchedule
@jsonHint("weekly") // TODO: we need start minutes too
case class WeeklySchedule(
    day : DayOfWeek
  , startHour : Int
  , startMinute : Int
) extends CampaignSchedule
@jsonHint("one-shot")
case class OneShot(start : DateTime) extends CampaignSchedule

trait CampaignDetails
trait CampaignType {
  def value : String
}

case class CampaignEvent(id : CampaignEventId, campaignId : CampaignId, state : CampaignEventState, start : DateTime, end : DateTime, campaignType : CampaignType )
case class CampaignEventId(value : String)

sealed trait CampaignEventState{
  def value : String
}
final object CampaignEventState {
  final case object Scheduled extends CampaignEventState { val value = "scheduled"    }
  final case object Running   extends CampaignEventState { val value = "running"      }
  final case object Finished  extends CampaignEventState { val value = "finished"     }
  final case object Skipped   extends CampaignEventState { val value = "skipped"      }

  val all = ca.mrvisser.sealerate.values[CampaignEventState].toList

  def parse(v: String): Either[String, CampaignEventState] = all.find(_.value == v.toLowerCase()).toRight(s"Error: ${v}' is not a valid campaign event state. Allowed values: '${all.mkString("','")}'")
}

trait CampaignResult {
  def id : CampaignEventId
}

object CampaignLogger extends NamedZioLogger {
  override def loggerName: String = "campaign"
}
