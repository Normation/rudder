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

import com.normation.NamedZioLogger
import com.normation.errors.*
import com.normation.inventory.domain.NodeId
import com.normation.rudder.campaigns.*
import com.normation.rudder.services.policies.ComputeSchedule
import com.normation.rudder.services.policies.ScheduleData
import com.normation.rudder.services.policies.ScheduleManagement
import com.normation.rudder.services.policies.ScheduleRepository
import com.normation.utils.DateFormaterService.toJavaInstant
import com.normation.utils.DateFormaterService.toJodaDateTime
import com.softwaremill.quicklens.*
import io.scalaland.chimney.syntax.*
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import scala.annotation.tailrec
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.syntax.*

/*
 * This file contains the services managing directive schedules as campaigns:
 * - schedules are stored as `DirectiveSchedule` campaigns,
 * - at policy generation, `ScheduleManagementImpl` derives the list of occurrence
 *   events to send to nodes and extends the generation horizon (`maxDate`) when needed,
 * - `DirectiveScheduleCampaignHandler` hooks the campaign workflow engine to trigger
 *   a policy generation before nodes run out of events.
 */

object DirectiveScheduleLogger extends NamedZioLogger {
  override def loggerName: String = "directive-schedule"
}

/*
 * (de)serialization of directive schedule campaigns for the campaign repository.
 */
object DirectiveScheduleSerializer extends JSONTranslateCampaign {

  override def getRawJson(): PartialFunction[Campaign, IOResult[Json]] = { case c: DirectiveScheduleFamily => c.toJsonAST.toIO }

  override def read(): PartialFunction[(String, CampaignParsingInfo), IOResult[Campaign]] = {
    case (s, CampaignParsingInfo(DirectiveScheduleType, 1)) => s.fromJson[DirectiveScheduleFamily].toIO
  }

  override def campaignType(): PartialFunction[String, CampaignType] = {
    case DirectiveScheduleType.value => DirectiveScheduleType
  }
}

/*
 * Directive schedules are the campaigns of type `directive-schedule`.
 * Disabled/archived ones are returned too: they must reach node expected reports so that
 * compliance knows the schedule exists (and generates no event: the directive never runs).
 */
class CampaignScheduleRepository(campaignRepo: CampaignRepository) extends ScheduleRepository {
  override def getAll(): IOResult[Seq[DirectiveSchedule]] = {
    campaignRepo
      .getAll(DirectiveScheduleType :: Nil, Nil)
      .map(_.collect { case c: DirectiveSchedule => c })
      .chainError("Error when getting directive schedules from campaign repository")
  }
}

/*
 * Bounds for schedule event generation. We generate events covering `horizonDays` ahead,
 * but always at least `minEvents` (so that infrequent schedules keep working long without
 * regeneration) and at most `maxEvents` (so that frequent schedules don't bloat policies).
 */
final case class ScheduleEventBounds(
    horizonDays: Int,
    minEvents:   Int,
    maxEvents:   Int
)

/*
 * Pure computations for schedule events: which occurrence windows to generate,
 * when the generation horizon must be extended, and the events themselves.
 */
object DirectiveScheduleEvents {

  /*
   * Deterministic event id for an occurrence window. It MUST be stable across policy
   * generations for a same occurrence: the agent's "run once by event" lock is keyed
   * on it, so a changing id would re-execute the directive inside a same window.
   */
  def eventId(scheduleId: CampaignId, window: ScheduleWindow): String = {
    UUID.nameUUIDFromBytes(s"${scheduleId.serialize}:${window.start.toEpochMilli}".getBytes(StandardCharsets.UTF_8)).toString
  }

  /*
   * Splay events per node: shift `notBefore` by a node-derived duration within the first half
   * of the window, so that the whole fleet does not execute the scheduled directives (and send
   * their reports) at the very start of the window. `notAfter` is unchanged: every node keeps
   * at least half of the window to catch a run.
   * The splay is a deterministic function of the node id (same algorithm as the agent run
   * splay, see `ComputeSchedule.computeSplayTime`), so events stay stable across generations.
   */
  def splayEvents(nodeId: NodeId, events: Seq[DirectiveScheduleEvent]): Seq[DirectiveScheduleEvent] = {
    events.map { e =>
      val window = Duration.between(e.notBefore, e.notAfter)
      val splay  = ComputeSchedule.computeSplayTime(nodeId.value, window, window.dividedBy(2))
      e.modify(_.notBefore).using(_.plus(splay))
    }
  }

  /*
   * Occurrence windows of the schedule starting from `from` (the window containing
   * `from`, if any, is included), limited to `maxCount` windows.
   */
  def occurrencesFrom(schedule: CampaignSchedule, from: Instant, maxCount: Int): PureResult[List[ScheduleWindow]] = {
    @tailrec
    def loop(cursor: Instant, acc: List[ScheduleWindow], remaining: Int): PureResult[List[ScheduleWindow]] = {
      if (remaining <= 0) Right(acc.reverse)
      else {
        // nextCampaignDate is still a Joda-Time API: bridge at that boundary only
        CampaignDateScheduler.nextCampaignDate(schedule, cursor.toJodaDateTime) match {
          case Left(err)                 => Left(err)
          case Right(None)               => Right(acc.reverse)
          case Right(Some((start, end))) =>
            val w = ScheduleWindow(start.toJavaInstant, end.toJavaInstant)
            if (w.isClosedAt(cursor)) {
              Left(Inconsistency(s"Cannot compute occurrences of schedule ${schedule} from ${from}: not moving forward"))
            }
            // empty DST-gap window (see ScheduleWindows.findWindows): no possible run, no event
            else if (w.start == w.end) loop(w.end, acc, remaining - 1)
            else loop(w.end, w :: acc, remaining - 1)
        }
      }
    }

    ScheduleWindows.findWindows(schedule, from).flatMap { w =>
      w.current match {
        case Some(current) => loop(current.end, List(current), maxCount - 1)
        case None          => loop(from, Nil, maxCount)
      }
    }
  }

  /*
   * The occurrence windows we would generate now: cover the horizon, with at least
   * `minEvents` and at most `maxEvents` occurrences.
   */
  def targetWindows(schedule: CampaignSchedule, now: Instant, bounds: ScheduleEventBounds): PureResult[List[ScheduleWindow]] = {
    occurrencesFrom(schedule, now, bounds.maxEvents).map { occurrences =>
      val horizonEnd = now.plus(bounds.horizonDays.toLong, ChronoUnit.DAYS)
      occurrences.zipWithIndex.takeWhile { case (w, i) => i < bounds.minEvents || w.start.isBefore(horizonEnd) }.map(_._1)
    }
  }

  /*
   * Does the stored generation horizon (`maxDate`) need to be extended?
   * We renew when less than half of the target coverage remains, or when fewer than
   * `minEvents` generated occurrences remain ahead. This quantization avoids changing
   * `maxDate` (and thus node configurations) at every generation.
   * It is arbitrary and can be updated if edge cases are found.
   */
  def needsRenewal(
      storedMaxDate: Option[Instant],
      target:        List[ScheduleWindow],
      now:           Instant,
      bounds:        ScheduleEventBounds
  ): Boolean = {
    target match {
      case Nil => false // nothing would be generated (e.g. one shot in the past): nothing to renew
      case ws  =>
        storedMaxDate match {
          case None     => true
          case Some(d0) =>
            val remaining   = ws.count(w => !w.start.isAfter(d0))
            val midCoverage = now.plus(Duration.between(now, ws.last.end).dividedBy(2))
            remaining < bounds.minEvents.min(ws.size) || d0.isBefore(midCoverage)
        }
    }
  }

  def toEvents(schedule: DirectiveSchedule, windows: List[ScheduleWindow]): List[DirectiveScheduleEvent] = {
    windows.map { w =>
      DirectiveScheduleEvent(
        id = schedule.info.id,
        eventId = eventId(schedule.info.id, w),
        eventType = schedule.details.scheduleType,
        name = s"${schedule.info.name} - ${w.start}",
        notBefore = w.start,
        notAfter = w.end
      )
    }
  }
}

/*
 * Compute schedule events at policy generation time, extending and persisting the
 * generation horizon (`maxDate` in campaign details) when needed.
 * The computation is deterministic from (schedule, maxDate), so persisting the extension
 * before policies are actually written is safe: a failed generation will simply be
 * caught up by the next successful one.
 */
class ScheduleManagementImpl(campaignRepo: CampaignRepository, bounds: ScheduleEventBounds) extends ScheduleManagement {

  override def updateSchedules(now: Instant, schedules: Seq[DirectiveSchedule]): IOResult[ScheduleData] = {
    ZIO.foldLeft(schedules)(ScheduleData.empty) {
      case (acc, campaign) =>
        updateOne(now, campaign).map {
          case (json, events, wasUpdated) =>
            val acc2 = if (wasUpdated) {
              acc.modify(_.updated).using(_ + (json.id -> json))
            } else {
              acc.modify(_.upToDate).using(_ + (json.id -> json))
            }
            acc2.modify(_.events).using(_ + (json.id -> events))
        }
    }
  }

  /*
   * Add an on-demand occurrence window ("run now"): directives using that schedule will
   * be executed once by agents during [start, start+duration], whatever the schedule
   * recurrence is - including when the schedule is disabled. The caller is in charge
   * of triggering a policy generation so that nodes get the event.
   */
  override def addOneShotEvent(id: CampaignId, start: Instant, duration: Duration): IOResult[DirectiveScheduleOneShot] = {
    for {
      campaign       <- campaignRepo
                          .get(id)
                          .notOptional(s"Cannot add an on-demand run: directive schedule '${id.serialize}' was not found")
      schedule       <- campaign match {
                          case c: DirectiveSchedule => c.succeed
                          case c =>
                            Inconsistency(
                              s"Cannot add an on-demand run: campaign '${id.serialize}' is not a directive schedule but a " +
                              s"'${c.campaignType.value}'"
                            ).fail
                        }
      // an on-demand run is dropped when a window is already active at that time: the agent
      // will already execute the directives once in that window, a second event would either
      // be redundant or run them twice
      activeOneShot   = schedule.details.oneShots.find(o => !start.isBefore(o.start) && start.isBefore(o.end))
      activeRecurrent = {
        if (schedule.info.status.value == CampaignStatusValue.Enabled) {
          ScheduleWindows.findWindows(schedule.info.schedule, start).toOption.flatMap(_.current)
        } else {
          None
        }
      }
      oneShot        <- (activeOneShot, activeRecurrent) match {
                          case (Some(active), _) =>
                            DirectiveScheduleLogger.info(
                              s"On-demand run for directive schedule '${id.serialize}' ignored: the on-demand window " +
                              s"'${active.eventId}' [${active.start} -> ${active.end}] is already active"
                            ) *> active.succeed
                          case (None, Some(w))   =>
                            DirectiveScheduleLogger.info(
                              s"On-demand run for directive schedule '${id.serialize}' ignored: a scheduled occurrence " +
                              s"window [${w.start} -> ${w.end}] is already active"
                            ) *> DirectiveScheduleOneShot(
                              DirectiveScheduleEvents.eventId(id, w),
                              w.start,
                              w.end
                            ).succeed
                          case (None, None)      =>
                            val o       = DirectiveScheduleOneShot(java.util.UUID.randomUUID().toString, start, start.plus(duration))
                            // also prune expired one shots while we are saving
                            val updated = schedule.modify(_.details.oneShots).using(os => o :: os.filter(_.end.isAfter(start)))
                            DirectiveScheduleLogger.info(
                              s"On-demand run for directive schedule '${id.serialize}': event '${o.eventId}' from " +
                              s"${o.start} to ${o.end}"
                            ) *>
                            campaignRepo
                              .save(updated)
                              .chainError(s"Error when saving on-demand run for directive schedule '${id.serialize}'")
                              .as(o)
                        }
    } yield oneShot
  }

  // the on-demand one shot events that are still relevant (not expired)
  private def oneShotEvents(now: Instant, campaign: DirectiveSchedule): List[DirectiveScheduleEvent] = {
    campaign.details.oneShots.collect {
      case o if o.end.isAfter(now) =>
        DirectiveScheduleEvent(
          id = campaign.info.id,
          eventId = o.eventId,
          eventType = campaign.details.scheduleType,
          name = s"${campaign.info.name} - on-demand run at ${o.start}",
          notBefore = o.start,
          notAfter = o.end
        )
    }
  }

  // returns (schedule for expected reports, events for nodes, was the horizon extended)
  private def updateOne(
      now:      Instant,
      campaign: DirectiveSchedule
  ): IOResult[(JsonDirectiveSchedule, List[DirectiveScheduleEvent], Boolean)] = {
    val id = campaign.info.id
    if (campaign.info.status.value != CampaignStatusValue.Enabled) {
      // a disabled schedule means "never run" - but on-demand runs ("run now") remain possible
      DirectiveScheduleLogger.info(
        s"Directive schedule '${id.serialize}' is '${campaign.info.status.value.entryName}': no recurrent schedule " +
        s"event is generated and directives using it only run on demand. Enable the campaign to resume scheduled executions."
      ) *>
      (campaign.transformInto[JsonDirectiveSchedule], oneShotEvents(now, campaign), false).succeed
    } else {
      for {
        target <- DirectiveScheduleEvents
                    .targetWindows(campaign.info.schedule, now, bounds)
                    .toIO
                    .chainError(s"Error when computing occurrence windows for directive schedule '${id.serialize}'")
        renew   = DirectiveScheduleEvents.needsRenewal(campaign.details.maxDate, target, now, bounds)
        res    <- if (renew) {
                    // target is non empty when renew is true
                    val newMaxDate = target.last.end
                    val updated    = campaign
                      .modify(_.details.maxDate)
                      .setTo(Some(newMaxDate))
                      // also prune expired on-demand one shots while we are saving
                      .modify(_.details.oneShots)
                      .using(_.filter(_.end.isAfter(now)))
                    for {
                      _ <- DirectiveScheduleLogger.info(
                             s"Extending schedule events of directive schedule '${id.serialize}' up to ${newMaxDate} " +
                             s"(${target.size} events)"
                           )
                      _ <- campaignRepo
                             .save(updated)
                             .chainError(s"Error when saving extended horizon for directive schedule '${id.serialize}'")
                    } yield {
                      (updated.transformInto[JsonDirectiveSchedule], DirectiveScheduleEvents.toEvents(updated, target), true)
                    }
                  } else {
                    // keep events up to the already generated horizon
                    val kept = campaign.details.maxDate match {
                      case None    => Nil
                      case Some(d) => target.filter(w => !w.start.isAfter(d))
                    }
                    DirectiveScheduleLogger.debug(
                      s"Directive schedule '${id.serialize}' horizon is up to date (${campaign.details.maxDate}), " +
                      s"${kept.size} events"
                    ) *>
                    (campaign.transformInto[JsonDirectiveSchedule], DirectiveScheduleEvents.toEvents(campaign, kept), false).succeed
                  }
      } yield {
        res.modify(_._2).using(oneShotEvents(now, campaign) ::: _)
      }
    }
  }
}

/*
 * The campaign handler for directive schedules. An event of that campaign is an
 * occurrence window of the schedule. The actual work (deciding to run or not the
 * directive) happens on the agent; here we only use the workflow ticks to extend
 * the event generation horizon (through a policy generation) before nodes run
 * out of events.
 */
class DirectiveScheduleCampaignHandler(
    scheduleManagement: ScheduleManagement,
    triggerGeneration:  () => IOResult[Unit]
) extends CampaignHandler {
  import CampaignEventStateType.*

  override def name: String = "directive-schedule"

  override def handle(event: CampaignEvent): PartialFunction[Campaign, IOResult[EventOrchestration]] = {
    case c: DirectiveSchedule =>
      event.state.value match {
        case ScheduledType =>
          // an occurrence window is starting: check that nodes have enough events ahead
          for {
            now  <- Clock.instant
            data <- scheduleManagement.updateSchedules(now, c :: Nil)
            _    <- ZIO.when(data.updated.nonEmpty) {
                      DirectiveScheduleLogger.info(
                        s"Directive schedule '${c.info.id.serialize}' events horizon was extended: triggering a policy " +
                        s"generation to update nodes"
                      ) *> triggerGeneration()
                    }
          } yield EventOrchestration.SaveAndQueue(event.copy(state = CampaignEventState.Running))

        case RunningType =>
          // the workflow engine wakes us up at the end of the occurrence window
          EventOrchestration.SaveAndQueue(event.copy(state = CampaignEventState.Finished)).succeed

        case _ =>
          // final states: nothing to do, the engine schedules the next occurrence
          EventOrchestration.IgnoreAndStop.succeed
      }
  }

  override def delete(event: CampaignEvent): PartialFunction[Campaign, IOResult[Unit]] = {
    // schedule events only live in generated policies: the next generation cleans them up
    case _: DirectiveSchedule => ZIO.unit
  }
}
