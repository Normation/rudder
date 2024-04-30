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

import cats.implicits.*
import com.normation.errors.Inconsistency
import com.normation.errors.IOResult
import com.normation.errors.RudderError
import com.normation.utils.DateFormaterService
import com.normation.utils.StringUuidGenerator
import org.joda.time.DateTime
import scala.annotation.nowarn
import zio.Duration
import zio.Queue
import zio.ZIO
import zio.syntax.*

trait CampaignHandler {
  def handle(mainCampaignService: MainCampaignService, event: CampaignEvent): PartialFunction[Campaign, IOResult[CampaignEvent]]
  def delete(mainCampaignService: MainCampaignService, event: CampaignEvent): PartialFunction[Campaign, IOResult[CampaignEvent]]
}

object MainCampaignService {
  def start(mainCampaignService: MainCampaignService): ZIO[Any, Nothing, Unit] = {
    for {
      campaignQueue <- Queue.unbounded[CampaignEventId]
      _             <- mainCampaignService.start(campaignQueue).forkDaemon
    } yield ()
  }
}

class MainCampaignService(
    repo:         CampaignEventRepository,
    campaignRepo: CampaignRepository,
    uuidGen:      StringUuidGenerator,
    startDelay:   Int,
    endDelay:     Int
) {

  private var services:                    List[CampaignHandler]       = Nil
  def registerService(s: CampaignHandler): ZIO[Any, RudderError, Unit] = {
    services = s :: services
    init()
  }

  private var inner: Option[CampaignScheduler] = None

  def deleteCampaign(c: CampaignId): ZIO[Any, RudderError, Unit] = {

    inner match {
      case Some(s) =>
        for {
          _ <- s.deleteCampaign(c)
          _ <- campaignRepo.delete(c)
        } yield { () }
      case None    => CampaignLogger.debug(s"Campaign system not initialized yet, campaign ${c.value} was not deleted")
    }
  }

  def saveCampaign(c: Campaign):       ZIO[Any, RudderError, Campaign]        = {
    for {
      _ <- campaignRepo.save(c)
      _ <- scheduleCampaignEvent(c, DateTime.now())
    } yield {
      c
    }
  }
  def queueCampaign(c: CampaignEvent): ZIO[Any, Inconsistency, CampaignEvent] = {
    inner match {
      case Some(s) => s.queueCampaign(c)
      case None    => Inconsistency("not initialized yet").fail
    }
  }

  case class CampaignScheduler(main: MainCampaignService, queue: Queue[CampaignEventId]) {

    def deleteCampaign(c: CampaignId):   ZIO[Any, RudderError, Unit]      = {
      for {
        campaign <- campaignRepo.get(c)
        events   <- repo.getWithCriteria(campaignId = Some(c))
        _        <- ZIO.foreach(events) { event =>
                      ZIO
                        .foreach(services)(s => s.delete(main, event)(campaign))
                        .catchAll(_ => {
                          CampaignLogger.warn(
                            s"An error occured while cleaning campaign event ${event.id.value} during deletion of campaign ${c.value}"
                          )
                        })
                    }
        _        <- repo.deleteEvent(campaignId = Some(c))
      } yield {
        ()
      }
    }
    def queueCampaign(c: CampaignEvent): ZIO[Any, Nothing, CampaignEvent] = {
      for {
        _ <- queue.offer(c.id)
      } yield {
        c
      }
    }

    def handle(eventId: CampaignEventId): ZIO[Any, RudderError, Any] = {

      def base(event: CampaignEvent): PartialFunction[Campaign, IOResult[CampaignEvent]] = { case _ => event.succeed }

      val now = DateTime.now()

      @nowarn
      def failingLog(err: RudderError) = {
        for {
          _ <- CampaignLogger.error(
                 s"An error occurred while treating campaign event ${eventId.value}, error details : ${err.fullMsg} "
               )
          _ <- err.fail
        } yield {
          ()
        }
      }

      repo
        .get(eventId)
        .flatMap {
          case None        =>
            CampaignLogger.error(
              s"An error occurred while treating campaign event ${eventId.value}, error details : Could not find campaign event details "
            )
          case Some(event) =>
            {
              for {
                campaign <- campaignRepo.get(event.campaignId)
                _        <- CampaignLogger.debug(s"Got Campaign ${event.campaignId.value} for event ${event.id.value}")

                _    <- CampaignLogger.debug(s"Start handling campaign event '${event.id.value}' state is ${event.state.value}")
                wait <-
                  event.state match {
                    case Finished | Skipped(_) =>
                      ().succeed
                    case Scheduled             =>
                      campaign.info.status match {
                        case Disabled(reason)       =>
                          val reasonMessage = if (reason.isEmpty) "" else s"Reason is '${reason}''"
                          repo.saveCampaignEvent(
                            event.copy(state = Skipped(s"Event was cancelled because campaign is disabled. ${reasonMessage}"))
                          )
                        case Archived(reason, date) =>
                          val reasonMessage = if (reason.isEmpty) "" else s"Reason is '${reason}''"
                          repo.saveCampaignEvent(
                            event.copy(state = Skipped(s"Event was cancelled because campaign is archived. ${reasonMessage}"))
                          )
                        case Enabled                =>
                          val effectiveStart = event.start.minusHours(startDelay)
                          if (effectiveStart.isAfter(now)) {
                            for {
                              _ <-
                                CampaignLogger.debug(
                                  s"Scheduled Campaign event ${event.id.value} put to sleep until it should start, on ${DateFormaterService
                                      .serialize(effectiveStart)}, ${startDelay} hour${if (startDelay > 1) "s" else ""} before official start date, to ensure policies are correctly dispatched, nothing will be applied on the node"
                                )
                              _ <- ZIO.sleep(Duration.fromMillis(effectiveStart.getMillis - now.getMillis))
                            } yield {
                              ().succeed
                            }
                          } else {
                            CampaignLogger.debug(
                              s"${event.id.value} should be treated by ${event.campaignType.value} handler for Scheduled state"
                            )
                          }
                      }
                    case Running               =>
                      val effectiveStart = event.start.minusHours(startDelay)
                      val effectiveEnd   = event.end.plusHours(endDelay)
                      if (effectiveStart.isAfter(now)) {
                        for {
                          // Campaign should be planned, not running
                          _ <-
                            CampaignLogger.warn(
                              s"Campaign event ${event.id.value} was considered Running but we are before its start date, setting state to Schedule and wait for event to start, on ${DateFormaterService
                                  .serialize(effectiveStart)}, ${startDelay} hour${if (startDelay > 1) "s" else ""} before official start date, to ensure policies are correctly dispatched, nothing will be applied on the node"
                            )
                          _ <- repo.saveCampaignEvent(event.copy(state = Scheduled))
                          _ <-
                            CampaignLogger.debug(
                              s"Scheduled Campaign event ${event.id.value} put to sleep until it should start, on ${DateFormaterService
                                  .serialize(effectiveStart)}, ${startDelay} hour${if (startDelay > 1) "s" else ""} before official start date, to ensure policies are correctly dispatched, nothing will be applied on the node"
                            )

                          _ <- ZIO.sleep(Duration.fromMillis(effectiveStart.getMillis - now.getMillis))
                        } yield {
                          ()
                        }
                      } else if (effectiveEnd.isAfter(now)) {
                        for {
                          _ <-
                            CampaignLogger.debug(
                              s"Running Campaign event ${event.id.value} put to sleep until it should end, on ${DateFormaterService
                                  .serialize(effectiveEnd)}, ${endDelay} hour${if (endDelay > 1) "s" else ""} after official end date, so that we can gather results"
                            )
                          _ <- ZIO.sleep(Duration.fromMillis(effectiveEnd.getMillis - now.getMillis))
                        } yield {
                          ()
                        }
                      } else {
                        CampaignLogger.debug(
                          s"${event.id.value} should be treated by ${event.campaignType.value} handler for Running state"
                        )
                      }
                  }

                // Get updated event and campaign, state of the event could have changed, campaign parameter also
                _    <- repo.get(event.id).flatMap {
                          case None               =>
                            CampaignLogger.warn(
                              s"Campaign event ${event.name} (id '${event.id.value}') was deleted while it was waiting to be treated, it state was ${event.state.value}, it should have run between ${DateFormaterService
                                  .getDisplayDate(event.start)} and ${DateFormaterService.getDisplayDate(event.end)}, we will ignore this event and do nothing with it"
                            )
                          case Some(updatedEvent) =>
                            for {
                              updatedCampaign <- campaignRepo.get(event.campaignId)
                              _               <- CampaignLogger.debug(s"Got Updated campaign and event for event ${event.id.value}")

                              handle       = {
                                services.map(_.handle(main, updatedEvent)).foldLeft(base(updatedEvent)) {
                                  case (base, handler) => handler orElse base
                                }
                              }
                              newCampaign <- handle.apply(updatedCampaign)

                              _    <-
                                CampaignLogger.debug(
                                  s"Campaign event ${newCampaign.id.value} update, previous state was ${event.state.value} new state${newCampaign.state.value}"
                                )
                              save <- repo.saveCampaignEvent(newCampaign)
                              post <-
                                newCampaign.state match {
                                  case Finished | Skipped(_) =>
                                    for {
                                      campaign <- campaignRepo.get(event.campaignId)
                                      up       <- scheduleCampaignEvent(campaign, newCampaign.end)
                                    } yield {
                                      up
                                    }
                                  case Scheduled | Running   =>
                                    for {
                                      _ <- queueCampaign(newCampaign)
                                    } yield {
                                      ()
                                    }
                                }
                            } yield {
                              ()
                            }
                        }
              } yield {
                ()
              }
            }.catchAll { err =>
              /*
               * When we have an error at that level, if we don't skip the event, it will be requeue and we are extremely
               * likely to get the same error, and to loop infinitely, filling the disk with logs at full speed.
               * See: https://issues.rudder.io/issues/22141
               */
              CampaignLogger.error(
                s"An error occurred while treating campaign event ${eventId.value}, skipping that event. Error details : ${err.fullMsg}"
              ) *>
              repo.saveCampaignEvent(event.copy(state = Skipped(s"An error occurred when processing event: ${err.fullMsg}")))
            }
        }
        .catchAll(failingLog) // this catch all is when event the previous level does not work. It should not create loop
    }

    def loop(): ZIO[Any, Nothing, Unit] = {
      for {
        c <- queue.take
        _ <- handle(c).forkDaemon
      } yield {
        ()
      }
    }

    def start(): ZIO[Any, Nothing, Nothing] = loop().forever
  }

  def scheduleCampaignEvent(campaign: Campaign, date: DateTime): IOResult[Option[CampaignEvent]] = {

    campaign.info.status match {
      case Enabled                      =>
        for {
          nbOfEvents <- repo.numberOfEventsByCampaign(campaign.info.id)
          events     <- repo.getWithCriteria(Running.value :: Nil, Nil, Some(campaign.info.id), None, None, None, None, None, None)
          _          <- repo.deleteEvent(None, Scheduled.value :: Nil, None, Some(campaign.info.id), None, None)

          lastEventDate = events match {
                            case Nil => date
                            case _   =>
                              val maxEventDate = events.maxBy(_.end.getMillis).start
                              if (maxEventDate.isAfter(date)) maxEventDate else date

                          }
          dates        <- MainCampaignScheduler.nextCampaignDate(campaign.info.schedule, lastEventDate)
          newEvent     <- dates match {
                            case Some((start, end)) =>
                              val ev = CampaignEvent(
                                CampaignEventId(uuidGen.newUuid),
                                campaign.info.id,
                                s"${campaign.info.name} #${nbOfEvents + 1}",
                                Scheduled,
                                start,
                                end,
                                campaign.campaignType
                              )
                              for {
                                _ <- repo.saveCampaignEvent(ev)
                                _ <- queueCampaign(ev)
                              } yield {
                                Some(ev)
                              }
                            case None               => None.succeed
                          }

        } yield {
          newEvent
        }
      case Disabled(_) | Archived(_, _) => None.succeed
    }
  }

  def init(): ZIO[Any, RudderError, Unit] = {
    inner match {
      case None    => Inconsistency("Campaign queue not initialized. campaign service was not started accordingly").fail
      case Some(s) =>
        for {
          alreadyScheduled <-
            repo.getWithCriteria(Running.value :: Scheduled.value :: Nil, Nil, None, None, None, None, None, None, None)
          _                <- CampaignLogger.debug("Got events, queue them")
          _                <- s.queue.takeAll // empty queue, we will enqueue all existing events again
          _                <- ZIO.foreach(alreadyScheduled)(ev => s.queueCampaign(ev))
          _                <- CampaignLogger.debug("queued events, check campaigns")
          campaigns        <- campaignRepo.getAll(Nil, CampaignStatusValue.Enabled :: Nil)
          _                <- CampaignLogger.debug(s"Got ${campaigns.size} campaigns, check all started")
          toStart           = campaigns.filterNot(c => alreadyScheduled.exists(_.campaignId == c.info.id))
          optNewEvents     <- ZIO.foreach(toStart)(c => scheduleCampaignEvent(c, DateTime.now()))
          newEvents         = optNewEvents.collect { case Some(ev) => ev }
          _                <- CampaignLogger.debug(s"Scheduled ${newEvents.size} new events, queue them")
          _                <- ZIO.foreach(newEvents)(ev => s.queueCampaign(ev))
          _                <- CampaignLogger.info(s"Campaign Scheduler initialized with ${alreadyScheduled.size + newEvents.size} events")
        } yield {
          ()
        }
    }
  }

  def start(initQueue: Queue[CampaignEventId]): ZIO[Any, RudderError, Unit] = {
    val s = CampaignScheduler(this, initQueue)
    inner = Some(s)
    for {
      _ <- CampaignLogger.info("Starting campaign scheduler")
      _ <- s.start().forkDaemon
      _ <- CampaignLogger.debug("Starting campaign forked, now getting already created events")
      _ <- init()
    } yield {
      ()
    }
  }

}

object MainCampaignScheduler {

  def nextDateFromDayTime(date: DateTime, start: DayTime): DateTime = {
    (if (
       date.getDayOfWeek > start.day.value
       || (date.getDayOfWeek == start.day.value && date.getHourOfDay > start.realHour)
       || (date.getDayOfWeek == start.day.value && date.getHourOfDay == start.realHour && date.getMinuteOfHour > start.realMinute)
     ) {
       date.plusWeeks(1)
     } else {
       date
     })
      .withDayOfWeek(start.day.value)
      .withHourOfDay(start.realHour)
      .withMinuteOfHour(start.realMinute)
      .withSecondOfMinute(0)
      .withMillisOfSecond(0)
  }

  def nextCampaignDate(schedule: CampaignSchedule, date: DateTime): IOResult[Option[(DateTime, DateTime)]] = {
    schedule match {
      case OneShot(start, end)        =>
        if (start.isBefore(end)) {
          if (end.isAfter(date)) {
            Some((start, end)).succeed
          } else {
            None.succeed
          }
        } else {

          Inconsistency(s"Cannot schedule a one shot event if end (${DateFormaterService
              .getDisplayDate(end)}) date is before start date (${DateFormaterService.getDisplayDate(start)})").fail
        }
      case WeeklySchedule(start, end) =>
        val startDate = nextDateFromDayTime(date, start)
        val endDate   = nextDateFromDayTime(startDate, end)

        Some((startDate, endDate)).succeed

      case MonthlySchedule(position, start, end) =>
        val realHour             = start.realHour
        val realMinutes          = start.realMinute
        val day                  = start.day
        def base(date: DateTime) = (position match {
          case First      =>
            val t = date.withDayOfMonth(1).withDayOfWeek(day.value)
            if ((t.getYear == date.getYear && t.getMonthOfYear < date.getMonthOfYear) || t.getYear + 1 == date.getYear) {
              t.plusWeeks(1)
            } else {
              t
            }
          case Second     =>
            val t = date.withDayOfMonth(1).withDayOfWeek(day.value)
            if ((t.getYear == date.getYear && t.getMonthOfYear < date.getMonthOfYear) || t.getYear + 1 == date.getYear) {
              t.plusWeeks(2)
            } else {
              t.plusWeeks(1)
            }
          case Third      =>
            val t = date.withDayOfMonth(1).withDayOfWeek(day.value)
            if ((t.getYear == date.getYear && t.getMonthOfYear < date.getMonthOfYear) || t.getYear + 1 == date.getYear) {
              t.plusWeeks(3)
            } else {
              t.plusWeeks(2)
            }
          case Last       =>
            val t = date.plusMonths(1).withDayOfMonth(1).withDayOfWeek(day.value)
            if ((t.getYear == date.getYear && t.getMonthOfYear > date.getMonthOfYear) || t.getYear == date.getYear + 1) {
              t.minusWeeks(1)
            } else {
              t
            }
          case SecondLast =>
            val t = date.plusMonths(1).withDayOfMonth(1).withDayOfWeek(day.value)
            if ((t.getYear == date.getYear && t.getMonthOfYear > date.getMonthOfYear) || t.getYear == date.getYear + 1) {
              t.minusWeeks(2)
            } else {
              t.minusWeeks(1)
            }
        }).withHourOfDay(realHour).withMinuteOfHour(realMinutes).withSecondOfMinute(0).withMillisOfSecond(0)
        val currentMonthStart    = base(date)
        val startDate            = {
          if (date.isAfter(currentMonthStart)) {
            base(date.plusMonths(1))
          } else {
            currentMonthStart
          }
        }
        val endDate              = nextDateFromDayTime(startDate, end)
        Some((startDate, endDate)).succeed
    }
  }
}
