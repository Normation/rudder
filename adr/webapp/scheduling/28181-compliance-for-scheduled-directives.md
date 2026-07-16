# Compliance computation for scheduled directives

* Status: proposed (written for implementation review)
* Deciders: FAR
* Date: 2026-07-10

## Context

A scheduled directive does not report at every agent run: agents execute it **once per occurrence
window** (events are `once`, see ADR
[28451](../28451-generating-discrete-scheduled-events-for-agent.md)). Consequently:

* "no report in this run" is NOT an error in itself - even inside an open window (the directive may
  have run earlier in the window, or may run in a later run of the window);
* Rudder must keep showing the last known compliance between executions, tell since when it is
  known, and only report an error when a whole window went by without any execution.

Compliance for a node is recomputed from scratch at each new run
(`FindNewNodeStatusReports.buildNodeStatusReports`), so the previous computed compliance must become
an input of the computation.

## Decision

### Inputs

* `ExecutionBatch.getNodeStatusReports` takes a new parameter
  `lastCompliance: Option[NodeStatusReport]`, fed by `FindNewNodeStatusReportsImpl` from
  `NodeStatusReportRepository` (the `nodelastcompliance`-backed cache; restart-safe).
* Node expected reports carry the schedules used by the node (`ss`:
  `JsonDirectiveSchedule(id, enabled, maxDate, recurrence)`) and each directive expected report its
  `scheduleId` (`sid`) - from the generation part of the feature.
* `DirectiveStatusReport` gains `agentRunTime: Option[Instant]` (serialized as `art` in
  `nodelastcompliance`, absent for pre-existing data): the time compliance data of a *scheduled*
  directive was actually collected. It is stamped when reports are present, propagated by
  `DirectiveStatusReport.merge` (most recent wins), and carried over when compliance is kept.

All window computations compare instants produced on the server (window bounds) with instants
carried by node runs: the standard Rudder requirement that **server and node clocks are
synchronized** (NTP) applies, nothing specific is added for schedules.

### Decision table (per scheduled directive, in `ExecutionBatch.decideScheduledDirectiveWithoutReports`)

Windows (start inclusive, end exclusive) are recomputed from the recurrence with
`ScheduleWindows.findWindows` (the window containing the run time, and the last closed one before
it). "keep/pending" means: reuse the previous `DirectiveStatusReport` if one exists (with its
`agentRunTime`), else report all components as `Pending` (decision: no new `ReportType` is
introduced; pending already means "expected, not received yet, not an error").

| # | situation (at run time t) | result |
|---|---|---|
| 1 | reports for the directive are in the run | standard compliance, `agentRunTime = t` (works with pre-9.2 agents that ignore schedules: reports are always accepted) |
| 2 | schedule id unknown in expected reports | warn log, keep/pending |
| 3 | schedule disabled | info log ("never runs, enable the campaign"), keep/pending |
| 4 | window computation error | error log, keep/pending |
| 5 | a window is currently open | keep/pending (agent may not have executed yet) |
| 6 | no window ever closed before t | keep/pending |
| 7 | last closed window starts after `maxDate` (event was never generated) | warn log ("regeneration needed"), keep/pending |
| 8 | last update >= last closed window start | keep (the window was honored) |
| 9 | last closed window starts before the node config `beginDate` | keep/pending (grace: the agent may not have had the directive during that window) |
| 10 | otherwise | **missing** (a whole window went by without execution) |

"last update" is the previous directive `agentRunTime`; for compliance saved before this feature it
falls back to the rule-level run time, but ONLY when the previous status carries actual data (a pure
pending status is never treated as an execution - otherwise a never-executing directive would stay
pending forever instead of transitioning to missing).

On-demand one-shot windows (`JsonDirectiveSchedule.os`, see ADR
[28181 on-demand runs](28181-on-demand-runs-and-overlapping-windows.md)) participate in the same
table: an open one-shot behaves like an open recurrent window (rule 5), a closed one like a closed
recurrent window (rules 8/10, using the most recent closed window of either kind) - including when
the schedule is disabled or the recurrent window is past `maxDate`, since a one-shot always had an
event. An overridden scheduled directive is reported as overridden like any other directive; the
schedule logic does not apply to it.

Two known, accepted limits:

* **keep uses the previous configuration's data**: when compliance is kept, the reused
  `DirectiveStatusReport` reflects the components of the configuration the directive last ran with.
  If the directive content changed since, the detail may be stale until the next scheduled run -
  the standard "pending after change" trade-off, bounded by one occurrence window;
* **a run can report inside a window and be merged with older data**: `DirectiveStatusReport.merge`
  keeps the most recent `agentRunTime`, so partial per-window updates converge to the same result
  as a full run (invariant checked by tests).

### What does not change

* Node-level run analysis (`NoReportInInterval`, `Pending`, unexpected versions, the
  disconnected-node "keep last compliance" policy...) is untouched: when the whole node is silent,
  scheduled directives are `NoAnswer` like everything else. The two keep-compliance mechanisms stay
  orthogonal.
* Change detection (repaired reports) works on raw reports and is unaffected.

Logs explaining each keep/pending/missing decision go to the sysops-facing
`explain_compliance.<nodeId>` logger.

## Alternatives

* A new `ReportType` for "skipped by schedule". Rejected for now: a new enum value ripples through
  `ComplianceLevel`, storage, API and UI; `Pending` carries the right "not an error, not received"
  semantic. Can be revisited for display purposes.
* Keeping compliance via the node-level expiration policy (as for disconnected nodes). Rejected: it
  is node-global, while scheduling needs per-directive granularity.

## Consequences

* Compliance of a scheduled directive is stable between executions and updated at each execution;
  `agentRunTime` gives the "as of" date (not yet displayed in UI - future work).
* A new configuration gets one window of grace before "missing" (consistent with Rudder's
  pending-after-change behavior); this also avoids flapping to missing right after each generation.
* The intended invariant, checked by tests (`ScheduledDirectiveComplianceTest`): a full run without
  schedule guards is equivalent to the sum of partial per-window updates.
