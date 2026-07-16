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

package com.normation.rudder.services.reports

import com.normation.cfclerk.domain.TechniqueVersionHelper
import com.normation.inventory.domain.NodeId
import com.normation.rudder.campaigns.CampaignId
import com.normation.rudder.campaigns.Daily
import com.normation.rudder.campaigns.ScheduleTimeZone
import com.normation.rudder.campaigns.Time
import com.normation.rudder.domain.nodes.NodeState
import com.normation.rudder.domain.policies.*
import com.normation.rudder.domain.reports.*
import com.normation.rudder.reports.AgentRunInterval
import com.normation.rudder.reports.ComplianceModeName.FullCompliance
import com.normation.rudder.reports.GlobalComplianceMode
import com.normation.rudder.schedule.DirectiveScheduleOneShot
import com.normation.rudder.schedule.JsonDirectiveSchedule
import com.normation.rudder.services.policies.PolicyId
import com.normation.utils.DateFormaterService.toJodaDateTime
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.junit.runner.*
import org.specs2.mutable.*
import org.specs2.runner.*

/*
 * Test the compliance semantic of scheduled directives: a directive with a schedule only
 * executes on the agent during occurrence windows of that schedule (and only once by window),
 * so a run without reports for it is not an error in itself. Compliance must be kept from
 * the last known status, and only becomes "missing" when a whole occurrence window went by
 * without any update.
 */
@RunWith(classOf[JUnitRunner])
class ScheduledDirectiveComplianceTest extends Specification {

  // avoid noise in test output: we are testing cases that log
  org.slf4j.LoggerFactory
    .getLogger("explain_compliance")
    .asInstanceOf[ch.qos.logback.classic.Logger]
    .setLevel(ch.qos.logback.classic.Level.OFF)

  val nodeId:     NodeId      = NodeId("n1")
  val ruleId:     RuleId      = RuleId(RuleUid("rule1"))
  val normalId:   DirectiveId = DirectiveId(DirectiveUid("d-normal"))
  val schedDirId: DirectiveId = DirectiveId(DirectiveUid("d-sched"))
  val campaignId: CampaignId  = CampaignId("sched1")

  val mode: NodeModeConfig = NodeModeConfig(
    GlobalComplianceMode(FullCompliance),
    AgentRunInterval(None, 5, 14, 5, 4),
    None,
    GlobalPolicyMode(PolicyMode.Enforce, PolicyModeOverrides.Always),
    Some(PolicyMode.Enforce)
  )

  // all dates in june 2026, UTC. The schedule is daily between 4:00 and 6:00 UTC.
  def date(day: Int, hour: Int, minute: Int = 0): Instant = {
    OffsetDateTime.of(2026, 6, day, hour, minute, 0, 0, ZoneOffset.UTC).toInstant
  }

  def schedule(
      enabled: Boolean = true,
      maxDate: Option[Instant] = Some(date(30, 0)),
      os:      List[DirectiveScheduleOneShot] = Nil
  ): JsonDirectiveSchedule = {
    JsonDirectiveSchedule(campaignId, enabled, maxDate, Daily(Time(4, 0), Time(6, 0), Some(ScheduleTimeZone("UTC"))), os)
  }

  def expectedReports(
      schedules: List[JsonDirectiveSchedule],
      beginDate: Instant = date(1, 0),
      overrides: List[OverriddenPolicy] = Nil
  ): NodeExpectedReports = {
    NodeExpectedReports(
      nodeId,
      NodeConfigId("cfg1"),
      beginDate.toJodaDateTime,
      None,
      mode,
      schedules,
      List(
        RuleExpectedReports(
          ruleId,
          List(
            DirectiveExpectedReports(
              normalId,
              None,
              PolicyTypes.rudderBase,
              None,
              List(ValueExpectedReport("cmp-normal", ExpectedValueMatch("vn", "vn") :: Nil))
            ),
            DirectiveExpectedReports(
              schedDirId,
              None,
              PolicyTypes.rudderBase,
              Some(campaignId),
              List(ValueExpectedReport("cmp-sched", ExpectedValueMatch("vs", "vs") :: Nil))
            )
          )
        )
      ),
      overrides
    )
  }

  val expected: NodeExpectedReports = expectedReports(schedule() :: Nil)

  def reportNormal(t: Instant): Reports = {
    val d = t.toJodaDateTime
    ResultSuccessReport(d, ruleId, normalId, nodeId, "0", "cmp-normal", "vn", d, "normal is ok")
  }
  def reportSched(t: Instant):  Reports = {
    val d = t.toJodaDateTime
    ResultSuccessReport(d, ruleId, schedDirId, nodeId, "0", "cmp-sched", "vs", d, "sched is ok")
  }

  def compute(
      t:       Instant,
      exp:     NodeExpectedReports,
      last:    Option[NodeStatusReport],
      reports: Seq[Reports]
  ): NodeStatusReport = {
    val runTime = t.toJodaDateTime
    ExecutionBatch.getNodeStatusReports(
      nodeId,
      NodeState.Enabled,
      ComputeCompliance(runTime, exp, runTime.plusMinutes(10)),
      last,
      reports
    )
  }

  def directiveStatus(r: NodeStatusReport, id: DirectiveId): DirectiveStatusReport = {
    r.reports
      .getReports()
      .collectFirst { case rn if rn.directives.contains(id) => rn.directives(id) }
      .getOrElse(throw new Exception(s"directive ${id.serialize} not found in status report"))
  }

  // a previous compliance computed from a run inside the window of `day` at 5:00, with reports for both directives
  def previousAt(day: Int): NodeStatusReport = {
    val t = date(day, 5)
    compute(t, expected, None, Seq(reportNormal(t), reportSched(t)))
  }

  "a scheduled directive with reports in the run" should {
    val t      = date(10, 5)
    val result = compute(t, expected, None, Seq(reportNormal(t), reportSched(t)))

    "compute standard compliance" in {
      directiveStatus(result, schedDirId).compliance === ComplianceLevel(success = 1)
    }
    "stamp the directive with the run time" in {
      directiveStatus(result, schedDirId).agentRunTime === Some(t)
    }
    "not stamp the unscheduled directive" in {
      directiveStatus(result, normalId).agentRunTime === None
    }
    "also compute compliance for reports sent outside of the window (pre-9.2 agent compat)" in {
      val out = date(10, 12)
      val r   = compute(out, expected, None, Seq(reportNormal(out), reportSched(out)))
      directiveStatus(r, schedDirId).compliance === ComplianceLevel(success = 1) and
      directiveStatus(r, schedDirId).agentRunTime === Some(out)
    }
  }

  "a scheduled directive without reports in the run" should {

    "keep last compliance when it was updated during the last closed window" in {
      val previous = previousAt(10)
      val t        = date(10, 7) // window 4:00-6:00 of day 10 is closed
      val result   = compute(t, expected, Some(previous), Seq(reportNormal(t)))

      directiveStatus(result, schedDirId).compliance === ComplianceLevel(success = 1) and
      directiveStatus(result, schedDirId).agentRunTime === Some(date(10, 5)) and
      directiveStatus(result, normalId).compliance === ComplianceLevel(success = 1)
    }

    "be missing when there is no last compliance and a window closed since config deployment" in {
      val t      = date(10, 7)
      val result = compute(t, expected, None, Seq(reportNormal(t)))

      directiveStatus(result, schedDirId).compliance === ComplianceLevel(missing = 1) and
      directiveStatus(result, normalId).compliance === ComplianceLevel(success = 1)
    }

    "be pending when there is no last compliance and the config is more recent than the last window" in {
      // config deployed at 6:30, just after the day 10 window: the agent never had a chance to run it
      val exp    = expectedReports(schedule() :: Nil, beginDate = date(10, 6, 30))
      val t      = date(10, 7)
      val result = compute(t, exp, None, Seq(reportNormal(t)))

      directiveStatus(result, schedDirId).compliance === ComplianceLevel(pending = 1)
    }

    "transition from pending to missing once a full window closes without execution" in {
      val exp      = expectedReports(schedule() :: Nil, beginDate = date(10, 6, 30))
      // first computation, day 10 after config deployment: pending (and saved as last compliance)
      val previous = compute(date(10, 7), exp, None, Seq(reportNormal(date(10, 7))))
      // day 11: the 4:00-6:00 window closed without any report for the scheduled directive
      val t        = date(11, 7)
      val result   = compute(t, exp, Some(previous), Seq(reportNormal(t)))

      directiveStatus(previous, schedDirId).compliance === ComplianceLevel(pending = 1) and
      directiveStatus(result, schedDirId).compliance === ComplianceLevel(missing = 1)
    }

    "be missing when a whole window went by without update" in {
      val previous = previousAt(8)
      val t        = date(10, 7) // window of day 10 closed, last update on day 8
      val result   = compute(t, expected, Some(previous), Seq(reportNormal(t)))

      directiveStatus(result, schedDirId).compliance === ComplianceLevel(missing = 1)
    }

    "keep last compliance while the current window is still open" in {
      val previous = previousAt(9)
      val t        = date(10, 5) // inside day 10 window, agent did not execute the directive yet
      val result   = compute(t, expected, Some(previous), Seq(reportNormal(t)))

      directiveStatus(result, schedDirId).compliance === ComplianceLevel(success = 1) and
      directiveStatus(result, schedDirId).agentRunTime === Some(date(9, 5))
    }

    "keep last compliance when the last window is past the event generation horizon" in {
      // events were only generated up to day 9 6:00: the agent has no event for day 10
      val exp      = expectedReports(schedule(maxDate = Some(date(9, 6))) :: Nil)
      val previous = previousAt(8)
      val t        = date(10, 7)
      val result   = compute(t, exp, Some(previous), Seq(reportNormal(t)))

      directiveStatus(result, schedDirId).compliance === ComplianceLevel(success = 1)
    }

    "keep last compliance when the schedule is disabled" in {
      val exp      = expectedReports(schedule(enabled = false) :: Nil)
      val previous = previousAt(8)
      val t        = date(10, 7)
      val result   = compute(t, exp, Some(previous), Seq(reportNormal(t)))

      directiveStatus(result, schedDirId).compliance === ComplianceLevel(success = 1)
    }

    "keep last compliance when the schedule is unknown in expected reports" in {
      val exp      = expectedReports(Nil)
      val previous = previousAt(8)
      val t        = date(10, 7)
      val result   = compute(t, exp, Some(previous), Seq(reportNormal(t)))

      directiveStatus(result, schedDirId).compliance === ComplianceLevel(success = 1)
    }

    "use the rule run time as fallback when previous directive status has no run time (upgrade)" in {
      // simulate a pre-upgrade compliance: directive status without agentRunTime
      val previous = {
        val p = previousAt(10)
        NodeStatusReport(
          p.nodeId,
          p.runInfo,
          p.statusInfo,
          ComposedReport(p.reports.getReports().map { rn =>
            rn.copy(directives = rn.directives.view.mapValues(_.copy(agentRunTime = None)).toMap)
          })
        )
      }
      val t        = date(10, 7)
      val result   = compute(t, expected, Some(previous), Seq(reportNormal(t)))

      // rule run time is day 10 5:00, inside the day 10 window: compliance is kept
      directiveStatus(result, schedDirId).compliance === ComplianceLevel(success = 1)
    }
  }

  "an on-demand run window (one shot)" should {
    // an on-demand window on day 10, 6:50 -> 7:20, i.e. outside the recurrent 4:00-6:00 window
    val oneShot = DirectiveScheduleOneShot("event-1", date(10, 6, 50), date(10, 7, 20))

    "keep last compliance while the window is open, even when the schedule is disabled" in {
      val exp      = expectedReports(schedule(enabled = false, os = List(oneShot)) :: Nil)
      val previous = previousAt(8)
      val t        = date(10, 7) // inside the on-demand window
      val result   = compute(t, exp, Some(previous), Seq(reportNormal(t)))

      directiveStatus(result, schedDirId).compliance === ComplianceLevel(success = 1)
    }

    "be missing when the window closed without update, even when the schedule is disabled" in {
      val exp      = expectedReports(schedule(enabled = false, os = List(oneShot)) :: Nil)
      val previous = previousAt(8)
      val t        = date(10, 8) // the on-demand window is closed, last update on day 8
      val result   = compute(t, exp, Some(previous), Seq(reportNormal(t)))

      directiveStatus(result, schedDirId).compliance === ComplianceLevel(missing = 1)
    }

    "be missing when the window closed without update, even when past the event generation horizon" in {
      // recurrent events only generated up to day 9 (no event for the day 10 recurrent window),
      // but the on-demand window did have an event: its lack of report is a real missing
      val exp      = expectedReports(schedule(maxDate = Some(date(9, 6)), os = List(oneShot)) :: Nil)
      val previous = previousAt(8)
      val t        = date(10, 8)
      val result   = compute(t, exp, Some(previous), Seq(reportNormal(t)))

      directiveStatus(result, schedDirId).compliance === ComplianceLevel(missing = 1)
    }

    "be pending while the window is open and there is no last compliance" in {
      val exp    = expectedReports(schedule(enabled = false, os = List(oneShot)) :: Nil)
      val t      = date(10, 7)
      val result = compute(t, exp, None, Seq(reportNormal(t)))

      directiveStatus(result, schedDirId).compliance === ComplianceLevel(pending = 1)
    }
  }

  "an overridden scheduled directive" should {
    "be reported as overridden, not kept from last compliance" in {
      val rule2 = RuleId(RuleUid("rule2"))
      val po    = OverriddenPolicy(
        PolicyId(ruleId, schedDirId, TechniqueVersionHelper("1.0")),
        PolicyId(rule2, normalId, TechniqueVersionHelper("1.0"))
      )

      val exp      = expectedReports(schedule() :: Nil, overrides = po :: Nil)
      val previous = previousAt(9)
      val t        = date(10, 5) // window open: a non-overridden scheduled directive would keep compliance
      val result   = compute(t, exp, Some(previous), Seq(reportNormal(t)))

      directiveStatus(result, schedDirId).overridden === Some(rule2)
    }
  }

  "json serialization of schedules in expected reports" should {
    import zio.json.*

    "omit the on-demand windows field when empty, and default it when absent (upgrade compat)" in {
      val s    = schedule()
      val json = s.toJson

      (json must not contain "\"os\"") and
      (json.fromJson[JsonDirectiveSchedule] === Right(s))
    }

    "round trip with on-demand windows" in {
      val s    = schedule(os = List(DirectiveScheduleOneShot("event-1", date(10, 6, 50), date(10, 7, 20))))
      val json = s.toJson

      (json must contain("\"os\"")) and
      (json.fromJson[JsonDirectiveSchedule] === Right(s))
    }
  }

  "the agentRunTime of a scheduled directive" should {
    "survive a serialization round trip" in {
      import com.normation.rudder.domain.reports.JsonPostgresqlSerialization.JNodeStatusReport
      import zio.json.*

      val t        = date(10, 5)
      val report   = compute(t, expected, None, Seq(reportNormal(t), reportSched(t)))
      val json     = JNodeStatusReport.from(report).toJson
      val readBack = json.fromJson[JNodeStatusReport].map(_.to)

      readBack.map(r => directiveStatus(r, schedDirId).agentRunTime) === Right(Some(t))
    }
  }
}
