/*
 *************************************************************************************
 * Copyright 2025 Normation SAS
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

package com.normation.rudder.campaign

import com.normation.errors.*
import com.normation.rudder.*
import com.normation.rudder.campaigns.*
import com.normation.rudder.campaigns.CampaignEventStateType.*
import com.normation.utils.DateFormaterService
import com.normation.utils.DateFormaterService.toJavaInstant
import com.normation.zio.*

import java.util.UUID
import java.util.concurrent.TimeUnit
import org.joda.time.{Duration as JTDuration, *}
import org.junit.runner.RunWith
import zio.*
import zio.Clock.currentTime
import zio.syntax.*
import zio.test.*
import zio.test.Assertion.*
import zio.test.junit.ZTestJUnitRunner

@RunWith(classOf[ZTestJUnitRunner])
class CampaignOrchestrationLogicTest extends ZIOSpecDefault {

  // the delay before / after configured window to change state for workflow
  private val bufferDelayAroundRun = JTDuration.standardMinutes(5)
  // the windows for exec "run" state
  private def runWindow(d: DateTime) = (d.minusMinutes(1), d.plusMinutes(1))

  private val now: DateTime = DateTime.now(DateTimeZone.UTC)

  private val c0: TestCampaign = TestCampaign(
    CampaignInfo(
      CampaignId("c0"),
      "first campaign",
      "a test campaign present when rudder boot",
      Enabled,
      WeeklySchedule(DayTime(Monday, 3, 42), DayTime(Monday, 4, 42), None)
    ),
    TestCampaignDetails("test campaign")
  )

  private val eb0 = CampaignOrchestrationLogic
    .bootstrapEvent(
      c0,
      c0.info.id.serialize + "_event0",
      runWindow(now)._1,
      runWindow(now)._2,
      0
    )

  private val e0: CampaignEvent = eb0.event

  // a failing campaign
  private val failingCampaign: TestCampaign = TestCampaign(
    CampaignInfo(
      CampaignId("c1"),
      "failure in pre-hooks",
      "a test campaign that fails",
      Enabled,
      WeeklySchedule(DayTime(Monday, 3, 42), DayTime(Monday, 4, 42), None)
    ),
    TestCampaignDetails("test campaign")
  )

  private val eb1 = CampaignOrchestrationLogic
    .bootstrapEvent(
      failingCampaign,
      failingCampaign.info.id.serialize + "_event0",
      runWindow(now)._1,
      runWindow(now)._2,
      0
    )

  private val e1: CampaignEvent = eb1.event

  // for hooks, we just log the run hooks
  private def successHookResult(e: CampaignEvent, out: String) = HookResults(Seq(HookResult(e.id.value, 0, out, "", "")))
  private def failureHookResult(e: CampaignEvent, out: String) = HookResults(Seq(HookResult(e.id.value, 1, out, "", "")))
  private val preHooksLog  = Ref.make(List.empty[CampaignEvent]).runNow
  private val postHooksLog = Ref.make(List.empty[CampaignEvent]).runNow

  // the log for the workflow trail

  object Effects extends CampaignOrchestrationEffects {
    // the orchestrator
    val orchestrator: Ref[Option[CampaignOrchestrationLogic]] = Ref.make(Option.empty[CampaignOrchestrationLogic]).runNow

    val eventStore: Ref[Map[CampaignEventId, CampaignEvent]] = Ref.make(Map()).runNow
    val eventTrail: Ref[List[CampaignEvent]]                 = Ref.make(List.empty[CampaignEvent]).runNow

    private val nextEvent = Ref.make((eb0, 0)).runNow

    override val campaignHandlers: Ref[List[CampaignHandler]] = Ref.make(List.empty[CampaignHandler]).runNow

    def reset(boot: EventOrchestration.SaveAndQueue): UIO[Unit] = {
      eventTrail.set(Nil) *> eventStore.set(Map()) *> nextEvent.set((boot, 0)) *> campaignHandlers.set(Nil)
    }

    override def createNextScheduledCampaignEvent(campaign: Campaign, date: DateTime): IOResult[EventOrchestration] = {
      for {
        x      <- nextEvent.get
        (eb, i) = x
        d       = now.plusDays(1)
        _      <- nextEvent.set(
                    (
                      CampaignOrchestrationLogic
                        .bootstrapEvent(campaign, UUID.randomUUID().toString, d.minusMinutes(5), d.plusMinutes(5), i + 1),
                      i + 1
                    )
                  )
      } yield eb
    }

    override def getEventInfo(eventId: CampaignEventId): IOResult[(Campaign, CampaignEvent)] = {
      eventStore.get
        .map(_.get(eventId))
        .notOptional(s"missing event '${eventId.value}' in tests")
        .map { e =>
          e.campaignId match {
            case c0.info.id              => (c0, e)
            case failingCampaign.info.id => (failingCampaign, e)
            case x                       => throw new RuntimeException(s"TEST: missing campaign ID case for event: ${x}")
          }
        }
    }

    // For testing, the run pre-hook fails when the campaign name contains "failure".
    override def runPreHooks(c: Campaign, e: CampaignEvent): IOResult[HookResults] = {
      preHooksLog
        .update(e :: _)
        .map(_ => {
          if (c.info.name.toLowerCase.contains("failure")) failureHookResult(e, "pre-hooks")
          else successHookResult(e, "pre-hooks")
        })
    }

    override def runPostHooks(c: Campaign, e: CampaignEvent): IOResult[HookResults] = {
      postHooksLog.update(e :: _).map(_ => successHookResult(e, "post-hooks"))
    }

    // this is the main logic. The enqueue just call back orchestration handle with the new event
    override def saveAndQueueEvents(events: Seq[EventOrchestration]): IOResult[Unit] = {
      def saveEvent(e: CampaignEvent) = {
        effectUioUnit(println(s"save event ${e.id}: ${e.state.value.entryName}")) *>
        eventTrail.update(e :: _) *> eventStore.update(_ + (e.id -> e))
      }

      def queueEvent(o: CampaignOrchestrationLogic, id: CampaignEventId, d: DateTime): IOResult[Unit] = {
        for {
          event <- eventStore.get.map(_.get(id)).notOptional("test:queue")
          // for testing purpose, we DON'T TSchedule next iteration but end the process.
          // In that case, we stop the process like an "ignore". We check with 12h around event, because
          // we can set whatever time we want
          _     <- if (event.state.value == ScheduledType && d.plusHours(12).isBefore(event.start)) {
                     ZIO.unit
                     // If the test is TRunning, since the logic is to wait for the running window to
                     // close, we fork the process, then advance the clock
                   } else if (event.state.value == RunningType) {
                     for {
                       t     <- currentTime(TimeUnit.MILLISECONDS)
                       adjust = event.end.plusMillis(5).plus(bufferDelayAroundRun).getMillis - t
                       f     <- o.handle(id, d, bufferDelayAroundRun, bufferDelayAroundRun).fork
                       _     <- TestClock.adjust(Duration(adjust, TimeUnit.MILLISECONDS))
                       _     <- f.await
                     } yield ()
                   } else {
                     o.handle(id, d, bufferDelayAroundRun, bufferDelayAroundRun)
                   }
        } yield ()
      }

      for {
        o <- orchestrator.get.notOptional("error in test, no orchestrator")
        d <- currentTime(TimeUnit.MILLISECONDS)
        _ <- ZIO
               .foreach(events) {
                 case EventOrchestration.SaveAndQueue(e)              => saveEvent(e) *> queueEvent(o, e.id, new DateTime(d))
                 case EventOrchestration.SaveThenUpdateAndQueue(e, s) =>
                   saveEvent(e) *> saveEvent(e.copy(state = s)) *> queueEvent(o, e.id, new DateTime(d))
                 case EventOrchestration.Queue(id)                    => queueEvent(o, id, new DateTime(d))
                 case EventOrchestration.SaveAndStop(e)               => saveEvent(e)
                 case EventOrchestration.IgnoreAndStop                => ZIO.unit
               }
               .unit
      } yield ()
    }
  }

  // init orchestrator
  private val orchestrator = new CampaignOrchestrationLogic(Effects)
  Effects.orchestrator.update(_ => Some(orchestrator)).runNow

  org.slf4j.LoggerFactory
    .getLogger("campaign")
    .asInstanceOf[ch.qos.logback.classic.Logger]
    .setLevel(ch.qos.logback.classic.Level.TRACE)

  object TestCampaignHandler extends CampaignHandler {
    import com.normation.rudder.campaigns.CampaignEventStateType.*

    override def name: String = "test-campaign-handler"

    override def handle(event: CampaignEvent): PartialFunction[Campaign, IOResult[EventOrchestration]] = {
      case c: TestCampaign =>
        event.state.value match {
          // stop in case of end state (success or error)
          case FailureType | DeletedType | FinishedType =>
            EventOrchestration.SaveAndStop(event).succeed
          case x                                        =>
            // else the handler just make progress until success
            val i = CampaignEventStateType.indexOf(x)
            val t = CampaignEventStateType.values(i + 1)
            val s = CampaignEventState.getDefault(t)
            EventOrchestration.SaveAndQueue(event.copy(state = s)).succeed
        }
    }

    override def delete(event: CampaignEvent): PartialFunction[Campaign, IOResult[Unit]] = ???
  }

  override def spec: Spec[TestEnvironment & Scope, Any] = {
    suite("all orchestration tests")(
      test("When there is no handler, a campaign even should stop immediately") {
        for {
          _ <- Effects.reset(eb0)
          _ <- TestClock.setTime(now.toJavaInstant)
          n <- Effects.createNextScheduledCampaignEvent(c0, now)
          _ <- Effects.saveAndQueueEvent(n)
          e <- Effects.eventStore.get.map(_.get(e0.id)).notOptional("missing event in store")
        } yield {
          assert(e.state.value)(equalTo(CampaignEventStateType.FailureType))
        }
      },
      test("if before date-1h then stop at schedule") {
        for {
          _ <- Effects.reset(eb0)
          _ <- TestClock.setTime(now.minusDays(1).toJavaInstant)
          n <- Effects.createNextScheduledCampaignEvent(c0, now)
          _ <- Effects.saveAndQueueEvent(n)
          e <- Effects.eventStore.get.map(_.get(e0.id)).notOptional("missing event in store")
        } yield {
          assert(e.state.value)(equalTo(CampaignEventStateType.ScheduledType))
        }
      },
      suite("When we have a campaign handler")(
        for {
          _ <- Effects.reset(eb0)
          _ <- Effects.campaignHandlers.set(List(TestCampaignHandler))
          _ <- TestClock.setTime(now.toJavaInstant)
          n <- Effects.createNextScheduledCampaignEvent(c0, now)
          _ <- Effects.saveAndQueueEvent(n)
          e <- Effects.eventStore.get.map(_.get(e0.id)).notOptional("missing event in store")
          t <- Effects.eventTrail.get
        } yield Chunk(
          test("progress to the end successfully") {
            assert(e.state.value)(equalTo(CampaignEventStateType.FinishedType))
          },
          test("all state must have been saved") {
            import com.normation.rudder.campaigns.CampaignEventState.*

            assert(t.reverse.map(_.state))(
              equalTo(
                List(
                  Scheduled,
                  PreHooks(HookResults(Nil)),
                  PreHooks(HookResults(HookResult(e.id.value, 0, "pre-hooks", "", "") :: Nil)),
                  Running,
                  PostHooks(FinishedType, HookResults(Nil)),
                  PostHooks(FinishedType, HookResults(HookResult(e.id.value, 0, "post-hooks", "", "") :: Nil)),
                  Finished,
                  Scheduled
                )
              )
            )
          }
        )
      ) @@ TestAspect.diagnose(Duration(1, TimeUnit.SECONDS)),
      suite("When an pre-hook is in error, switch to failure state but do exec post-hooks")(
        for {
          _ <- Effects.reset(eb1)
          _ <- Effects.campaignHandlers.set(List(TestCampaignHandler))
          _ <- TestClock.setTime(now.toJavaInstant)
          n <- Effects.createNextScheduledCampaignEvent(failingCampaign, now)
          _ <- Effects.saveAndQueueEvent(n)
          e <- Effects.eventStore.get.map(_.get(e1.id)).notOptional("missing event in store")
          t <- Effects.eventTrail.get
        } yield Chunk(
          test("progress to the end in a failure state") {
            assert(e.state.value)(equalTo(CampaignEventStateType.FailureType))
          },
          test("all state must have been saved, and we skip from pre-hook to post-hook") {
            import com.normation.rudder.campaigns.CampaignEventState.*

            assert(t.reverse.map(_.state))(
              equalTo(
                List(
                  Scheduled,
                  PreHooks(HookResults(Nil)),
                  PreHooks(HookResults(HookResult(e.id.value, 1, "pre-hooks", "", "") :: Nil)),
                  PostHooks(FailureType, HookResults(Nil)),
                  PostHooks(FailureType, HookResults(HookResult(e.id.value, 0, "post-hooks", "", "") :: Nil)),
                  Failure("pre-hooks were in error and post-hooks completed successfully", ""),
                  Scheduled
                )
              )
            )
          }
        )
      ) @@ TestAspect.diagnose(Duration(1, TimeUnit.SECONDS))
    ) @@ TestAspect.sequential @@ TestAspect.timeout(Duration(2, TimeUnit.SECONDS))
  }

}
