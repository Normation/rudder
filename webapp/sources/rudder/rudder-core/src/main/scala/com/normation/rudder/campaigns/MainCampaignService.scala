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

import com.normation.errors.*
import com.normation.rudder.campaigns.CampaignEventStateType.*
import com.normation.utils.StringUuidGenerator
import org.joda.time.{Duration as JTDuration, *}
import zio.*

/*
 * Method used by API of the campaign service, implemented by the top level
 * MainCampaignService
 */
trait CampaignApiService {

  /*
   * Save a campaign
   */
  def saveCampaign(c: Campaign): IOResult[Unit]

  /*
   * Delete a campaign
   */
  def deleteCampaign(id: CampaignId): IOResult[Unit]

  /*
   * Delete a campaign event
   */
  def deleteCampaignEvent(id: CampaignEventId): IOResult[Unit]

  /*
   * Schedule next event for given campaign
   */
  def scheduleCampaignEvent(campaign: Campaign, date: DateTime): IOResult[Option[CampaignEvent]]

}

/*
 * Build the main campaign logic from its component bits.
 */
object MainCampaignService {

  def make(
      repo:         CampaignEventRepository,
      campaignRepo: CampaignRepository,
      hooksService: CampaignHooksService,
      uuidGen:      StringUuidGenerator,
      startDelay:   Int, // in hour
      endDelay:     Int  // in hour
  ): UIO[MainCampaignService] = {
    for {
      queue       <- Queue.unbounded[CampaignEventId] // we need to have metrics on that size
      handlersRef <- Ref.make(List.empty[CampaignHandler])
    } yield {
      val effects      = new DefaultCampaignOrchestrationEffects(
        handlersRef,
        queue,
        repo,
        campaignRepo,
        hooksService,
        uuidGen
      )
      val orchestrator = new CampaignOrchestrationLogic(effects)
      val sd           = JTDuration.standardHours(startDelay)
      val ed           = JTDuration.standardHours(endDelay)
      val scheduler    = CampaignScheduler(queue, orchestrator, sd, ed)

      MainCampaignService(repo, campaignRepo, effects, scheduler, handlersRef)
    }
  }

  /*
   * Start the service, in particular get all persisted information about
   * existing campaigns and start the scheduler loop.
   */
  def start(mainCampaignService: MainCampaignService): UIO[Unit] = {
    mainCampaignService.start().forkDaemon.unit
  }
}

/**
 * This is the main services for campaign scheduling. It delegates most of the logic to
 * underlying components, but it serves as en entry point for API and plugins which need
 * to register themselves as campaign handlers.
 *
 * As a nexus, its factory in companion object also manages the construction of necessary bits
 * for underlying components like the main event queue, and the correct order of instantiation.
 *
 * The event queueing logic is in `CampaignScheduler`, but all the logic is in
 * `CampaignOrchestrationLogic`.
 */
class MainCampaignService(
    repo:         CampaignEventRepository,
    campaignRepo: CampaignRepository,
    effects:      CampaignOrchestrationEffects,
    scheduler:    CampaignScheduler,
    handlersRef:  Ref[List[CampaignHandler]]
) extends CampaignApiService {

  /*
   * Service registering must be done before `MainCampaignService` starts in `postPluginInitActions`
   * (typically ok for plugins)
   */
  def registerService(h: CampaignHandler): IOResult[Unit] = {
    CampaignLogger.info(s"Register campaign handler '${h.name}'") *>
    handlersRef.update(h :: _)
  }

  // entry point for API
  override def deleteCampaign(id: CampaignId): IOResult[Unit] = {
    for {
      campaign <- campaignRepo.get(id).notOptional(s"Campaign with id ${id.value} not found")
      events   <- repo.getWithCriteria(campaignId = Some(id))
      _        <- ZIO.foreachDiscard(events) { event =>
                    handlersRef.get.flatMap(services => {
                      ZIO
                        .foreachDiscard(services)(s => s.delete(event)(campaign).unit)
                        .catchAll { _ =>
                          CampaignLogger.warn(
                            s"An error occurred while cleaning campaign event ${event.id.value} during deletion of campaign ${id.value}"
                          )
                        }
                    })
                  }
      _        <- repo.deleteEvent(campaignId = Some(id))
      _        <- campaignRepo.delete(id)
    } yield ()
  }

  // entry point for API
  override def saveCampaign(c: Campaign): IOResult[Unit] = {
    for {
      _  <- campaignRepo.save(c)
      ev <- effects.createNextScheduledCampaignEvent(c, DateTime.now(DateTimeZone.UTC))
      _  <- effects.saveAndQueueEvents(List(ev))
    } yield ()
  }

  // entry point for API
  override def deleteCampaignEvent(id: CampaignEventId): IOResult[Unit] = {
    for {
      eventOpt <- repo.get(id)
      _        <- ZIO.foreachDiscard(eventOpt) { event =>
                    for {
                      campaign <- campaignRepo.get(event.campaignId).notOptional(s"Campaign with id ${id.value} not found")
                      _        <-
                        handlersRef.get.flatMap(services => {
                          ZIO
                            .foreachDiscard(services)(s => s.delete(event)(campaign).unit)
                            .catchAll(_ => {
                              CampaignLogger.warn(
                                s"An error occurred while cleaning campaign event ${event.id.value} during deletion of campaign ${id.value}"
                              )
                            })
                        })
                    } yield ()
                  }
      _        <- repo.deleteEvent(Some(id))
    } yield ()
  }

  // entry point for API
  override def scheduleCampaignEvent(campaign: Campaign, date: DateTime): IOResult[Option[CampaignEvent]] = {
    effects.createNextScheduledCampaignEvent(campaign, date).flatMap { event =>
      effects.saveAndQueueEvent(event).map { _ =>
        event match {
          case EventOrchestration.SaveAndQueue(e)              => Some(e)
          case EventOrchestration.SaveThenUpdateAndQueue(e, s) => Some(e.copy(state = s)) // return the final event state
          case EventOrchestration.Queue(_)                     => None
          case EventOrchestration.SaveAndStop(e)               => Some(e)
          case EventOrchestration.IgnoreAndStop                => None
        }
      }
    }
  }

  def init(): IOResult[Unit] = {
    for {
      alreadyScheduled <-
        repo.getWithCriteria(
          RunningType :: ScheduledType :: Nil,
          Nil,
          None,
          None,
          None,
          None,
          None,
          None,
          None
        )
      _                <- CampaignLogger.debug("Got events, queue them")
      _                <- scheduler.queue.takeAll // empty queue, we will enqueue all existing events again
      _                <- ZIO.foreach(alreadyScheduled)(ev => scheduler.queueCampaign(ev.id))
      _                <- CampaignLogger.debug("queued events, check campaigns")
      campaigns        <- campaignRepo.getAll(Nil, CampaignStatusValue.Enabled :: Nil)
      _                <- CampaignLogger.debug(s"Got ${campaigns.size} campaigns, check all started")
      toStart           = campaigns.filterNot(c => alreadyScheduled.exists(_.campaignId == c.info.id))
      optNewEvents     <- ZIO.foreach(toStart)(c => effects.createNextScheduledCampaignEvent(c, DateTime.now(DateTimeZone.UTC)))
      newEvents         = optNewEvents.collect { case ev if ev != EventOrchestration.IgnoreAndStop => ev }
      _                <- CampaignLogger.debug(s"Scheduled ${newEvents.size} new events, queue them")
      _                <- effects.saveAndQueueEvents(newEvents)
      _                <- CampaignLogger.info(s"Campaign Scheduler initialized with ${alreadyScheduled.size + newEvents.size} events")
    } yield ()
  }

  /*
   * actually start processing events
   */
  def start(): IOResult[Unit] = {
    for {
      _ <- CampaignLogger.info("Starting campaign scheduler")
      _ <- scheduler.start().forkDaemon
      _ <- CampaignLogger.debug("Starting campaign forked, now getting already created events")
      _ <- init()
    } yield ()
  }
}

/*
 * The campaign scheduler.
 * Each event related to a change in a campaign instance (also named "campaign event")
 * is queued.
 *
 * The queue processing strategy is to have one fiber for each handle(eventId).
 *
 * The main scheduler doesn't handle state progression, this is managed the orchestration logic.
 * Here, we only manage the queue and related actions (enqueue, dequeue).
 */
class CampaignScheduler(
    val queue:    Queue[CampaignEventId],
    orchestrator: CampaignOrchestrationLogic,
    startDelay:   JTDuration,
    endDelay:     JTDuration
) {

  // used in main service for bootstrapping
  def queueCampaign(id: CampaignEventId): UIO[Unit] = {
    queue.offer(id).unit
  }

  // call the underlying logic.
  def handle(eventId: CampaignEventId, now: DateTime): UIO[Unit] = {
    orchestrator.handle(eventId, now, startDelay, endDelay)
  }

  /*
   * Main event processing queue processing logic: here, we process each event in queue in its own fiber:
   * - take one element
   * - fork a fiber to process it (wait for the correct time, do something, etc)
   *
   * The handler can not fail - it deals with the business logic and try to manage what happens to an event in
   * case of failure (in logic, underlying plugin handling, or when saving/re-queuing it).
   * Here, we just need to deal with the case where we have ZIO error: fiber error, or whatever.
   * In that case, we just let the fiber crash and give relevant info for developers. Since it's on a forked fiber,
   * it won't crash the main queue-loop.
   */
  def loop(): UIO[Unit] = {
    for {
      c <- queue.take
      _ <- handle(c, DateTime.now(DateTimeZone.UTC)).forkDaemon
    } yield ()
  }

  def start(): ZIO[Any, Nothing, Nothing] = loop().forever
}
