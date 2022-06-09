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

import better.files.File.root
import cats.implicits._
import com.normation.GitVersion
import com.normation.GitVersion.Revision
import com.normation.NamedZioLogger
import com.normation.errors.BoxToIO
import com.normation.errors.IOResult
import com.normation.errors.Inconsistency
import com.normation.rudder.db.Doobie
import com.normation.rudder.db.Doobie._
import com.normation.rudder.domain.reports.Reports
import com.normation.rudder.repository.ReportsRepository
import com.normation.rudder.repository.RudderPropertiesRepository
import com.normation.utils.DateFormaterService
import com.normation.zio.ZioRuntime
import doobie.Read
import doobie.Write
import doobie.implicits._
import doobie.implicits.javasql._
import doobie.implicits.toSqlInterpolator
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import zio.Queue
import zio.ZIO
import zio.clock.Clock
import zio.duration._
import zio.interop.catz._
import zio.json.DeriveJsonDecoder
import zio.json.DeriveJsonEncoder
import zio.json.JsonDecoder
import zio.json.JsonEncoder
import zio.json.ast.Json
import zio.json.jsonDiscriminator
import zio.json.jsonHint
import zio.syntax._

import java.sql.Timestamp
import java.time.temporal.ChronoUnit

case class CampaignId (value : String, rev: Revision = GitVersion.DEFAULT_REV)

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

object CampaignSchedule

sealed trait MonthlySchedulePosition
case object First extends MonthlySchedulePosition
case object Second extends MonthlySchedulePosition
case object Third extends  MonthlySchedulePosition
case object Last extends  MonthlySchedulePosition
case object SecondLast extends MonthlySchedulePosition

sealed trait DayOfWeek {
  def value : Int
}
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



object CampaignLogger extends NamedZioLogger {
  override def loggerName: String = "campaign"
}

@jsonHint("monthly")
case class MonthlySchedule(monthlySchedulePosition: MonthlySchedulePosition, day : DayOfWeek) extends CampaignSchedule
@jsonHint("weekly")
case class WeeklySchedule(day : DayOfWeek) extends CampaignSchedule
@jsonHint("one-shot")
case class OneShot(start : DateTime) extends CampaignSchedule


trait Campaign {
  def info : CampaignInfo
  def details : CampaignDetails
}

case class CampaignInfo (
   id : CampaignId
  , name : String
  , description : String
  , status : CampaignStatus
  , schedule : CampaignSchedule
  , duration: Duration
)

trait CampaignDetails



case class CampaignEventId(value : String)


case class CampaignEvent(id : CampaignEventId, campaignId : CampaignId, state : CampaignEventState, start : DateTime, end : DateTime )

trait CampaignResult {
  def id : CampaignEventId
}

trait CampaignEventRepository {
  def getAllActiveCampaignEvents() : IOResult[List[CampaignEvent]]
  def get(campaignEventId: CampaignEventId) : IOResult[CampaignEvent]
  def getEventsForCampaign(campaignId: CampaignId, state: Option[CampaignEventState]) : IOResult[List[CampaignEvent]]
  def saveCampaignEvent(c : CampaignEvent) : IOResult[CampaignEvent]
}

class CampaignEventRepositoryImpl(doobie: Doobie) extends CampaignEventRepository {

  import doobie._


  implicit val eventWrite: Write[CampaignEvent] =
    Write[(String,String,String,Timestamp,Timestamp)].contramap{
      case event => (event.campaignId.value,event.id.value, "event.state", new java.sql.Timestamp(event.start.getMillis), new java.sql.Timestamp(event.end.getMillis))
    }

  implicit val eventRead : Read[CampaignEvent] =
    Read[(String,String,String,Timestamp,Timestamp)].map {
      d : (String,String,String,Timestamp,Timestamp) => CampaignEvent(CampaignEventId(d._1),CampaignId(d._2), Finished, new DateTime(d._4.getTime()), new DateTime(d._5.getTime()))
    }

  def getAllActiveCampaignEvents(): IOResult[List[CampaignEvent]] = {
    val q = sql"select eventId, campaignId, state, startDate, endDate from  CampaignEvent where state != 'finished' and state != 'skipped'"
    transactIOResult(s"error when inserting ")(xa => q.query[CampaignEvent].to[List].transact(xa))
  }


  def get(id : CampaignEventId): IOResult[CampaignEvent] = {
    val q = sql"select eventId, campaignId, state, startDate, endDate from  CampaignEvent where eventId == '${id.value}'"
    transactIOResult(s"error when inserting ")(xa => q.query[CampaignEvent].unique.transact(xa))
  }



  def saveCampaignEvent(c: CampaignEvent): IOResult[CampaignEvent] = {
      val q = sql"insert into CampaignEvent (eventId, campaignId, state, startDate, endDate) values (${c})"
      transactIOResult(s"error when inserting ")(xa => q.update.run.transact(xa)).map(_ => c)
  }

  def getEventsForCampaign(campaignId: CampaignId, state: Option[CampaignEventState]): IOResult[List[CampaignEvent]] = {
    val q = sql"select eventId, campaignId, state, startDate, endDate from  CampaignEvent where campaignId == '${campaignId.value}'"
    transactIOResult(s"error when inserting ")(xa => q.query[CampaignEvent].to[List].transact(xa))
  }

}

trait CampaignRepository {
  def getAll(): IOResult[List[Campaign]]
  def get(id : CampaignId) : IOResult[Campaign]
  def save(c : Campaign): IOResult[Campaign]
}

trait JSONTranslateCampaign{

  def getRawJson(): PartialFunction[Campaign, IOResult[Json]]

  def handle(): PartialFunction[Campaign, IOResult[String]]

  def read(): PartialFunction[String, IOResult[Campaign]]

}

object JSONTranslateCampaign {
  implicit val idEncoder : JsonEncoder[CampaignId]= JsonEncoder[String].contramap(_.value)
  implicit val dateTime : JsonEncoder[DateTime] = JsonEncoder[String].contramap(DateFormaterService.serialize)
  implicit val dayOfWeek : JsonEncoder[DayOfWeek] = JsonEncoder[Int].contramap(_.value)
  implicit val monthlySchedulePosition : JsonEncoder[MonthlySchedulePosition] =  JsonEncoder[Int].contramap(
    s =>
      s match {
        case First => 1
        case Second => 2
        case Third => 3
        case Last => -1
        case SecondLast => -2
      }
  )
  implicit val statusInfoEncoder   : JsonEncoder[CampaignStatus] = DeriveJsonEncoder.gen
  implicit val scheduleEncoder : JsonEncoder[CampaignSchedule]= DeriveJsonEncoder.gen
  implicit val campaignInfoEncoder : JsonEncoder[CampaignInfo]= DeriveJsonEncoder.gen

  implicit val idDecoder : JsonDecoder[CampaignId] = JsonDecoder[String].map(s => CampaignId(s))

  val iso8601 = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ssZZ")
  implicit val decodeIsoDate: JsonDecoder[DateTime] = JsonDecoder[String].mapOrFail(s =>
        try {
          Right(iso8601.parseDateTime(s))
        } catch {
          case e: Exception => Left(e.getMessage)
        }
  )
  implicit val decodeDay : JsonDecoder[DayOfWeek] = JsonDecoder[Int].mapOrFail(v =>
    v match {
      case 1 => Right(Monday)
      case 2 => Right(Tuesday)
      case 3 => Right(Wednesday)
      case 4 => Right(Thursday)
      case 5 => Right(Friday)
      case 6 => Right(Saturday)
      case 7 => Right(Sunday)
      case _ => Left(s"value should be between 1-7, received ${v}")
    }
  )
  implicit val decodeWeekMonth : JsonDecoder[MonthlySchedulePosition] = JsonDecoder[Int].mapOrFail(v =>
    v match {
      case 1 => Right(First)
      case 2 => Right(Second)
      case 3 => Right(Third)
      case -1 => Right(Last)
      case -2 => Right(SecondLast)
      case _ => Left(s"value should be between [-2,3], received ${v}")
    }
  )

  implicit val statusInfoDecoder   : JsonDecoder[CampaignStatus] = DeriveJsonDecoder.gen
  implicit val scheduleDecoder : JsonDecoder[CampaignSchedule]= DeriveJsonDecoder.gen
  implicit val campaignInfoDecoder : JsonDecoder[CampaignInfo]= DeriveJsonDecoder.gen


  implicit val campaignEventIdDecoder : JsonDecoder[CampaignEventId] = JsonDecoder[String].map(CampaignEventId)
  implicit val campaignEventStateDecoder : JsonDecoder[CampaignEventState] =  JsonDecoder[String].mapOrFail( s =>
    s match {
      case "skipped" => Right(Skipped)
      case "running" => Right(Running)
      case "finished" => Right(Finished)
      case "scheduled" => Right(Scheduled)
      case _ => Left("Invalid value for campaign event state should be one of scheduled | running | finished | skipped")
    }
  )
  implicit val campaignEventDecoder : JsonDecoder[CampaignEvent] = DeriveJsonDecoder.gen


  implicit val campaignEventIdEncoder : JsonEncoder[CampaignEventId] = JsonEncoder[String].contramap(_.value)
  implicit val campaignEventStateEncoder : JsonEncoder[CampaignEventState] =  JsonEncoder[String].contramap( s =>
    s match {
      case Skipped => "skipped"
      case Running =>  "running"
      case Finished => "finished"
      case Scheduled => "scheduled"
    }
  )
  implicit val campaignEventEncoder : JsonEncoder[CampaignEvent] = DeriveJsonEncoder.gen
}

class CampaignSerializer {

  private[this] var tranlaters : List[JSONTranslateCampaign] = Nil

  def getJson(campaign: Campaign) = tranlaters.map(_.getRawJson()).fold(Jsonbase) { case (a, b) => b orElse a }(campaign)


  val Jsonbase : PartialFunction[Campaign, IOResult[Json]] = {
    case c : Campaign => Inconsistency(s"No translater for campaign ${c.info.id.value}").fail
  }

  val base : PartialFunction[Campaign, IOResult[String]] = {
    case c : Campaign => Inconsistency(s"No translater for campaign ${c.info.id.value}").fail
  }

  val readBase : PartialFunction[String, IOResult[Campaign]]= {
    case c : String => Inconsistency(s"could not translate into campaign").fail
  }

  def addJsonTranslater(c : JSONTranslateCampaign ) = tranlaters = c :: tranlaters

  def serialize(campaign: Campaign) : IOResult[String] = tranlaters.map(_.handle()).fold(base) { case (a, b) => b orElse a }(campaign)
  def parse(string : String) : IOResult[Campaign] = tranlaters.map(_.read()).fold(readBase) { case (a, b) => b orElse a }(string)

}

class CampaignRepositoryImpl(campaignSerializer: CampaignSerializer) extends CampaignRepository {

  val path =  root / "var" / "rudder" /" configuration-repository" / "campaigns"
  def getAll(): IOResult[List[Campaign]] = {
      for {
        jsonFiles <- IOResult.effect{path.collectChildren(_.extension.exists(_ =="json"))}
        campaigns <- ZIO.foreach(jsonFiles.toList) {
          json => campaignSerializer.parse(json.contentAsString)
        }
      } yield {
        campaigns
      }
  }
  def get(id : CampaignId) : IOResult[Campaign] = {
    for {
      content <- IOResult.effect ("error when getting "){
          val file = path / (id.value ++".json")
          file.createFileIfNotExists(true)
          file
        }
      campaign <- campaignSerializer.parse(content.contentAsString)
    } yield {
      campaign
    }
  }
  def save(c : Campaign): IOResult[Campaign] = {
    for {
      file <- IOResult.effect {
        val file = path / (c.info.id.value ++".json")
        file.createFileIfNotExists(true)
        file
      }
      content <- campaignSerializer.serialize(c)
      _ <- IOResult.effect{
        file.write(content)
      }
    } yield {
      c
    }
  }
}

case class MainCampaignService(repo : CampaignEventRepository, campaignRepo: CampaignRepository) {

  private[this] var services : List[CampaignHandler] = Nil
  def registerService(s : CampaignHandler) = {
    services = s :: services
  }

  private[this] var inner : Option[CampaignScheduler] = None


  def queueCampaign(c: CampaignEvent) = {
    inner match {
      case Some(s) => s.queueCampaign(c)
      case None => Inconsistency("not initialized yet").fail
    }
  }


  case class CampaignScheduler(main : MainCampaignService, queue:Queue[CampaignEvent], zclock: Clock) {

    def queueCampaign(c: CampaignEvent) = {
      for {
        _ <- CampaignLogger.info("queuing")
        _ <- queue.offer(c)
      } yield {
        c
      }
    }

    def handle(c: CampaignEvent) = {

      def base: PartialFunction[Campaign, IOResult[CampaignEvent]] = {
        case _ => c.succeed
      }

      val s = services.map(_.handle(main,c)).fold(base) { case (a, b) => b orElse a }
      val now = DateTime.now()

      (for {
        campaign <- campaignRepo.get(c.campaignId)
        wait <-
          c.state match {
            case Finished =>
              ().succeed
            case Scheduled | Skipped =>
              if (c.start.isAfter(now)) {
                for {
                  _ <- CampaignLogger.info(s"will wait for ${c.start.getMillis - now.getMillis}")
                  _ <- ZIO.sleep(Duration.fromMillis(c.start.getMillis - now.getMillis))
                } yield {
                  ()
                }
              } else
                ().succeed
            case Running =>
              if (c.end.isAfter(now)) {
                for {
                  _ <- CampaignLogger.info(s"will wait for ${c.end.getMillis - now.getMillis}")
                  _ <- ZIO.sleep(Duration.fromMillis(c.end.getMillis - now.getMillis))
                } yield {
                  ()
                }
              } else ().succeed
          }
        newCampaign <- s.apply(campaign).catchAll(
          err =>
          for {
            _ <- CampaignLogger.error(err.fullMsg)
          } yield  {
            c
          }
        )
        _ <- CampaignLogger.info("saving")
        post <-
          c.state match {
            case Finished =>
              for {
                campaign <- campaignRepo.get(c.campaignId)
                up <-  scheduleCampaignEvent(campaign)
              } yield {
                up
              }
            case Scheduled|Running|Skipped =>
              for {
                _ <- repo.saveCampaignEvent(newCampaign)
                _ <- queueCampaign(newCampaign)
              } yield {
                ()
              }
          }

        _ <- CampaignLogger.info("end handle")

      } yield {
        ()
      }).provide(zclock).forkDaemon


    }

    def loop() = {
      for {

        _ <- CampaignLogger.info("loop")
        _ <- CampaignLogger.info(queue.toString)
        c <- queue.take
        _ <- CampaignLogger.info(c.toString)
        _ <- handle(c)
      } yield {
        ()
      }
    }



    def start() = {
      for {
        _ <- CampaignLogger.info("kikooo")
        init <- repo.getAllActiveCampaignEvents()
        _ <- CampaignLogger.info(init.mkString(","))
        _ <- queue.offerAll(init)
        _ <- loop().forever.forkDaemon
      } yield {
        ()
      }

    }
  }

  def nextCampaignDate(schedule : CampaignSchedule, date : DateTime) : IOResult[DateTime] = {
    schedule match {
      case OneShot(s) =>
        if (s.isAfter(date)) {
          s.succeed
        } else {
          Inconsistency("Cannot schedule").fail
        }
      case WeeklySchedule(day) =>
        (if (date.getDayOfWeek > day.value) {
          date.plusWeeks(1).withDayOfWeek(day.value)
        } else {
          date.withDayOfWeek(day.value)
        }).succeed
      case MonthlySchedule(position, day) =>
        (position match {
          case First =>
            val t = date.withDayOfMonth(1).withDayOfWeek(day.value)
            if (t.getMonthOfYear < date.getMonthOfYear) {
              t.plusWeeks(1)
            } else {
              t
            }
          case Second =>
            val t = date.withDayOfMonth(1).withDayOfWeek(day.value)
            if (t.getMonthOfYear < date.getMonthOfYear) {
              t.plusWeeks(2)
            } else {
              t.plusWeeks(1)
            }
          case Third =>
            val t = date.withDayOfMonth(1).withDayOfWeek(day.value)
            if (t.getMonthOfYear < date.getMonthOfYear) {
              t.plusWeeks(3)
            } else {
              t.plusWeeks(2)
            }
          case Last =>
            val t = date.plusMonths(1).withDayOfMonth(1).withDayOfWeek(day.value)
            if (t.getMonthOfYear > date.getMonthOfYear) {
              t.minusWeeks(1)
            } else {
              t
            }
          case SecondLast =>
            val t = date.plusMonths(1).withDayOfMonth(1).withDayOfWeek(day.value)
            if (t.getMonthOfYear > date.getMonthOfYear) {
              t.minusWeeks(2)
            } else {
              t.minusWeeks(1)
            }
        }).succeed
    }
  }

  def scheduleCampaignEvent(campaign: Campaign) : IOResult[CampaignEvent] = {

    for {
      events <- repo.getEventsForCampaign(campaign.info.id, None)
      lastEventDate = events match {
        case Nil => DateTime.now()
        case _ => events.maxBy(_.start.getMillis).start
      }
      newEventDate <- nextCampaignDate(campaign.info.schedule, lastEventDate)
      end = newEventDate.plus(campaign.info.duration.get(ChronoUnit.MILLIS))
      newCampaign = CampaignEvent(CampaignEventId("bloups"),campaign.info.id,Scheduled,newEventDate,end)
      _ <- repo.saveCampaignEvent(newCampaign)
      _ <- queueCampaign(newCampaign)
    } yield {
      newCampaign
    }
  }

  def start(initQueue: Queue[CampaignEvent]) = {
    val s = CampaignScheduler(this, initQueue, ZioRuntime.environment)
    inner = Some(s)
    s.start()
  }

}
trait CampaignHandler{
  def handle(mainCampaignService: MainCampaignService, event :CampaignEvent): PartialFunction[Campaign, IOResult[CampaignEvent]]
}



sealed trait CampaignEventState
final case object Scheduled extends CampaignEventState
final case object Running extends CampaignEventState
final case object Finished extends CampaignEventState
final case object Skipped extends CampaignEventState



trait JSONReportsHandler {
  def handle :  PartialFunction[Reports, IOResult[Reports]]


}




case class JSONReportsAnalyser (reportsRepository: ReportsRepository, propRepo: RudderPropertiesRepository) {


  private[this] var handlers: List[JSONReportsHandler] = Nil

  def addHandler(handler : JSONReportsHandler) = handlers =  handler :: handlers

  def handle(report: Reports) : IOResult[Reports] = {

    def base: PartialFunction[Reports, IOResult[Reports]] = {
      case c =>
        for {
          _ <- CampaignLogger.warn(s"Could not handle json report with type ${c.component}")
        } yield {
          c
        }

    }

    handlers.map(_.handle).fold(base) { case (a, b) => b orElse a }(report)
  }
  def loop = {
    for {

      lowerId <- propRepo.getReportHandlerLastId
      _ <- CampaignLogger.info(s"lower id is ${lowerId}" )
      reports <- reportsRepository.getReportsByKindBetween(lowerId.getOrElse(0),None, 1000, List(Reports.REPORT_JSON)).toIO
      _ <- ZIO.foreach(reports)(r => handle(r._2))
      _ <- propRepo.updateReportHandlerLastId(reports.maxBy(_._1)._1)
    } yield {
      ()
    }
  }

  def start() = {
    loop.delay(5.seconds).forever
  }


}