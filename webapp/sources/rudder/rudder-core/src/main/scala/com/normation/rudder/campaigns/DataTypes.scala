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
import com.normation.rudder.hooks.HookReturnCode
import enumeratum.*
import io.scalaland.chimney.*
import java.time.Instant
import java.time.ZoneId
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import zio.json.*
import zio.json.enumeratum.*

case class CampaignParsingInfo(
    campaignType: CampaignType,
    version:      Int
)

trait Campaign {
  def info:         CampaignInfo
  def details:      CampaignDetails
  def campaignType: CampaignType
  def copyWithId(newId:                        CampaignId):       Campaign
  def setScheduleTimeZone(newScheduleTimeZone: ScheduleTimeZone): Campaign
  def version: Int
}

object Campaign {
  def filter(
      campaigns:    List[Campaign],
      typeFilter:   List[CampaignType],
      statusFilter: List[CampaignStatusValue]
  ): List[Campaign] = {
    (typeFilter, statusFilter) match {
      case (Nil, Nil) => campaigns
      case (t, Nil)   => campaigns.filter(c => t.contains(c.campaignType))
      case (Nil, s)   => campaigns.filter(c => s.contains(c.info.status.value))
      case (t, s)     => campaigns.filter(c => t.contains(c.campaignType) && s.contains(c.info.status.value))
    }
  }
}

case class CampaignInfo(
    id:          CampaignId,
    name:        String,
    description: String,
    status:      CampaignStatus,
    schedule:    CampaignSchedule
)

case class CampaignId(value: String, rev: Revision = GitVersion.DEFAULT_REV) {
  def serialize: String = rev match {
    case GitVersion.DEFAULT_REV => value
    case rev                    => s"${value}+${rev.value}"
  }

  def withDefaultRev: CampaignId = this.copy(rev = GitVersion.DEFAULT_REV)
}

object CampaignId {

  // parse an id which was serialized by "id.serialize"
  def parse(s: String): Either[String, CampaignId] = {
    GitVersion.parseUidRev(s).map {
      case (id, rev) =>
        CampaignId(id, rev)
    }
  }

  implicit val codecCampaignId: JsonCodec[CampaignId] = JsonCodec.string.transformOrFail(CampaignId.parse, _.serialize)
}

sealed trait CampaignStatusValue extends EnumEntry {
  def value: String
}
@jsonDiscriminator("value")
sealed trait CampaignStatus {
  def value: CampaignStatusValue
}

object CampaignStatusValue extends Enum[CampaignStatusValue] {
  case object Enabled  extends CampaignStatusValue {
    val value = "enabled"
  }
  case object Disabled extends CampaignStatusValue {
    val value = "disabled"
  }
  case object Archived extends CampaignStatusValue {
    val value = "archived"
  }
  val values: IndexedSeq[CampaignStatusValue] = findValues

  def getValue(s: String): Either[String, CampaignStatusValue] = values.find(_.value == s.toLowerCase()) match {
    case None    => Left(s"${s} is not valid status value, accepted values are ${values.map(_.value).mkString(", ")}")
    case Some(v) => Right(v)
  }
}

// type of server side hooks for a campaign
sealed trait CampaignHookTypes(override val entryName: String) extends EnumEntry
object CampaignHookTypes                                       extends Enum[CampaignHookTypes] with EnumCodec[CampaignHookTypes] {
  // global to all events of a campaign
  // entry name is used both for JSON serialisation and to find the name of the
  // directory in the file system
  case object CampaignPreHooks  extends CampaignHookTypes("pre-hooks")
  case object CampaignPostHooks extends CampaignHookTypes("post-hooks")

  override def values: IndexedSeq[CampaignHookTypes] = findValues
}

@jsonHint("enabled")
case object Enabled                                 extends CampaignStatus {
  val value: CampaignStatusValue = CampaignStatusValue.Enabled
}
@jsonHint("disabled")
case class Disabled(reason: String)                 extends CampaignStatus {
  val value: CampaignStatusValue = CampaignStatusValue.Disabled
}
@jsonHint("archived")
case class Archived(reason: String, date: DateTime) extends CampaignStatus {
  val value: CampaignStatusValue = CampaignStatusValue.Archived
}

@jsonDiscriminator("type")
sealed trait CampaignSchedule {
  def tz: Option[ScheduleTimeZone]

  def atTimeZone(timeZone: ScheduleTimeZone): CampaignSchedule
}

sealed trait MonthlySchedulePosition
// they need to be encapsulated in MonthlySchedulePosition object
case object First      extends MonthlySchedulePosition
case object Second     extends MonthlySchedulePosition
case object Third      extends MonthlySchedulePosition
case object Last       extends MonthlySchedulePosition
case object SecondLast extends MonthlySchedulePosition

sealed trait DayOfWeek {
  def value: Int
}
// they need to be encapsulated in DayOfWeek object
case object Monday extends DayOfWeek {
  val value = 1
}
case object Tuesday extends DayOfWeek {
  val value = 2
}
case object Wednesday extends DayOfWeek {
  val value = 3
}
case object Thursday extends DayOfWeek {
  val value = 4
}
case object Friday extends DayOfWeek {
  val value = 5
}
case object Saturday extends DayOfWeek {
  val value = 6
}
case object Sunday extends DayOfWeek {
  val value = 7
}

case class Time(hour: Int, minute: Int) {
  val realHour:   Int = hour   % 24
  val realMinute: Int = minute % 60
}

case class DayTime(
    day:    DayOfWeek,
    hour:   Int,
    minute: Int
) {
  val realHour:   Int = hour   % 24
  val realMinute: Int = minute % 60
}

case class ScheduleTimeZone(
    id: String
) extends AnyVal {
  def toDateTimeZone: Option[DateTimeZone] = {
    try { Option(DateTimeZone.forID(id)) }
    catch { case _: Throwable => None }
  }
}
object ScheduleTimeZone                 {
  def parse(s: String): Either[String, ScheduleTimeZone] = {
    if (ZoneId.getAvailableZoneIds.contains(s)) {
      Right(ScheduleTimeZone(s))
    } else {
      Left(s"Error parsing schedule time zone, unknown IANA ID : '${s}'")
    }
  }
  def now():            ScheduleTimeZone                 = ScheduleTimeZone(DateTimeZone.getDefault().getID())
}

@jsonHint("monthly")
case class MonthlySchedule(
    @jsonField("position")
    monthlySchedulePosition: MonthlySchedulePosition,
    start:                   DayTime,
    end:                     DayTime,
    tz:                      Option[ScheduleTimeZone]
) extends CampaignSchedule {
  override def atTimeZone(timeZone: ScheduleTimeZone): CampaignSchedule = copy(tz = Some(timeZone))
}
@jsonHint("weekly")
case class WeeklySchedule(
    start: DayTime,
    end:   DayTime,
    tz:    Option[ScheduleTimeZone]
) extends CampaignSchedule {
  override def atTimeZone(timeZone: ScheduleTimeZone): CampaignSchedule = copy(tz = Some(timeZone))
}
@jsonHint("one-shot")
case class OneShot(start: DateTime, end: DateTime) extends CampaignSchedule {
  override def tz:                                     None.type        = None
  // just change the start and end time to the timezone
  override def atTimeZone(timeZone: ScheduleTimeZone): CampaignSchedule = timeZone.toDateTimeZone match {
    case None        => this
    case Some(value) => copy(start = start.withZone(value), end = end.withZone(value))
  }
}

@jsonHint("daily")
case class Daily(start: Time, end: Time, tz: Option[ScheduleTimeZone]) extends CampaignSchedule {
  override def atTimeZone(timeZone: ScheduleTimeZone): CampaignSchedule = copy(tz = Some(timeZone))
}

trait CampaignDetails

case class CampaignType(value: String)

object CampaignType {
  implicit val campaignTypeEncoder: JsonEncoder[CampaignType] = JsonEncoder[String].contramap(_.value)
  implicit val decoderCampaignType: JsonDecoder[CampaignType] = JsonDecoder.string.map(CampaignType.apply)
}

case class CampaignEventId(value: String)

object CampaignEventId {
  implicit val campaignEventIdDecoder: JsonDecoder[CampaignEventId] = JsonDecoder[String].map(CampaignEventId.apply)
  implicit val campaignEventIdEncoder: JsonEncoder[CampaignEventId] = JsonEncoder[String].contramap(_.value)
}

/*
 * Our different kind of states and their stable names (need compat between rudder version).
 */
sealed trait CampaignEventStateType(override val entryName: String) extends EnumEntry

object CampaignEventStateType extends Enum[CampaignEventStateType] with EnumCodec[CampaignEventStateType] {
  case object ScheduledType extends CampaignEventStateType("scheduled")
  case object PreHooksType  extends CampaignEventStateType("pre-hooks")
  case object RunningType   extends CampaignEventStateType("running")
  case object PostHooksType extends CampaignEventStateType("post-hooks")
  case object FinishedType  extends CampaignEventStateType("finished")
  case object SkippedType   extends CampaignEventStateType("skipped")
  case object DeletedType   extends CampaignEventStateType("deleted")
  case object FailureType   extends CampaignEventStateType("failure")

  override def values: IndexedSeq[CampaignEventStateType] = findValues
}

case class CampaignEvent(
    id:           CampaignEventId,
    campaignId:   CampaignId,
    name:         String,
    state:        CampaignEventState,
    start:        DateTime,
    end:          DateTime,
    campaignType: CampaignType
)

object CampaignEvent {
  import com.normation.utils.DateFormaterService.json.*
  implicit val campaignEventDecoder: JsonDecoder[CampaignEvent] = DeriveJsonDecoder.gen
  implicit val campaignEventEncoder: JsonEncoder[CampaignEvent] = DeriveJsonEncoder.gen

  implicit val transformCampaignEvent: Transformer[CampaignEvent, CampaignEventHistory] = {
    Transformer
      .define[CampaignEvent, CampaignEventHistory]
      .buildTransformer
  }

}

// this is just a json compatible version of HookResultCode
case class HookResult(cmd: String, code: Int, stdout: String, stderr: String, msg: String) derives JsonCodec

object HookResult {
  def fromCode(c: HookReturnCode): HookResult = HookResult(c.cmd, c.code, c.stdout, c.stderr, c.msg)
}

case class HookResults(results: Seq[HookResult]) derives JsonCodec

/*
 * Campaign event can have details stored in history
 */
@jsonDiscriminator("value")
sealed trait CampaignEventState(val value: CampaignEventStateType) derives JsonCodec
object CampaignEventState {
  import com.normation.rudder.campaigns.CampaignEventStateType.*
  @jsonHint(ScheduledType.entryName) case object Scheduled                            extends CampaignEventState(ScheduledType)
  @jsonHint(PreHooksType.entryName) case class PreHooks(hookResults: HookResults)     extends CampaignEventState(PreHooksType)
  @jsonHint(RunningType.entryName) case object Running                                extends CampaignEventState(RunningType)
  // post hook are given a potential "goto failure/skipped/etc" choice
  @jsonHint(PostHooksType.entryName) case class PostHooks(nextState: CampaignEventStateType, hookResults: HookResults)
      extends CampaignEventState(PostHooksType)
  @jsonHint(FinishedType.entryName) case object Finished                              extends CampaignEventState(FinishedType)
  @jsonHint(SkippedType.entryName) case class Skipped(reason: String)                 extends CampaignEventState(SkippedType)
  @jsonHint(DeletedType.entryName) case class Deleted(reason: String)                 extends CampaignEventState(DeletedType)
  @jsonHint(FailureType.entryName) case class Failure(cause: String, message: String) extends CampaignEventState(FailureType)

  // initial value for pre and post hooks
  val PreHooksInit:  PreHooks  = PreHooks(HookResults(Nil))
  val PostHooksInit: PostHooks = PostHooks(FinishedType, HookResults(Nil))

  // when we don't have data for a state that could/should
  def getDefault(s: CampaignEventStateType): CampaignEventState = {
    s match {
      case ScheduledType => CampaignEventState.Scheduled
      case PreHooksType  => PreHooksInit
      case RunningType   => CampaignEventState.Running
      case PostHooksType => PostHooksInit
      case FinishedType  => CampaignEventState.Finished
      case SkippedType   => CampaignEventState.Skipped("")
      case DeletedType   => CampaignEventState.Deleted("")
      case FailureType   => CampaignEventState.Failure("unknown reason", "")
    }
  }
}

case class CampaignEventHistory(id: CampaignEventId, state: CampaignEventState, start: Instant, end: Option[Instant])

sealed abstract class CampaignSortDirection(override val entryName: String) extends EnumEntry

object CampaignSortDirection extends Enum[CampaignSortDirection] {
  case object Asc extends CampaignSortDirection("asc")

  case object Desc extends CampaignSortDirection("desc")

  override def values: IndexedSeq[CampaignSortDirection] = findValues
}

sealed abstract class CampaignSortOrder(override val entryName: String) extends EnumEntry

object CampaignSortOrder extends Enum[CampaignSortOrder] {
  case object StartDate extends CampaignSortOrder("startDate")

  case object EndDate extends CampaignSortOrder("endDate")

  override def extraNamesToValuesMap: Map[String, CampaignSortOrder] = {
    Map(
      "start" -> StartDate,
      "end"   -> EndDate
    )
  }

  override def values: IndexedSeq[CampaignSortOrder] = findValues
}

object CompatV21 {
  @jsonDiscriminator("value")
  sealed trait CampaignEventState {
    def value: String
  }

  object CampaignEventState {

    @jsonHint(Scheduled.value)
    case object Scheduled extends CampaignEventState {
      val value = "scheduled"
    }

    @jsonHint(Running.value)
    case object Running extends CampaignEventState {
      val value = "running"
    }

    @jsonHint(Finished.value)
    case object Finished extends CampaignEventState {
      val value = "finished"
    }

    @jsonHint(Skipped("").value)
    final case class Skipped(reason: String) extends CampaignEventState {
      val value = "skipped"
    }

    implicit val codecCampaignEventState: JsonCodec[CampaignEventState] = DeriveJsonCodec.gen

    def parse(s: String): Either[String, CampaignEventState] = {
      s.toLowerCase.trim match {
        case Scheduled.value => Right(Scheduled)
        case Running.value   => Right(Running)
        case Finished.value  => Right(Finished)
        case "skipped"       => Right(Skipped(""))
        case x               => Left(s"Error when parsing CampaignEventState: unrecognized case '${s}'")
      }
    }
  }

  case class CampaignEvent(
      id:           CampaignEventId,
      campaignId:   CampaignId,
      name:         String,
      state:        CampaignEventState,
      start:        DateTime,
      end:          DateTime,
      campaignType: CampaignType
  )

  object CampaignEvent {
    import com.normation.utils.DateFormaterService.json.*
    implicit val codecCampaignEvent: JsonCodec[CampaignEvent] = DeriveJsonCodec.gen
  }
}

/*
 * Type used in plugin to specify their result.
 * A plugin can have several result type, like SystemUpdateCampaignResultSimple
 * and SystemUpdateCampaignResultFull.
 */
trait CampaignResult {
  def id: CampaignEventId
}

object CampaignLogger extends NamedZioLogger {
  override def loggerName: String = "campaign"

  // chatty part about Json report processing
  object Reports extends NamedZioLogger {
    override def loggerName: String = "campaign.reports"
  }
}
