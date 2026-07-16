# Generation and renewal of discrete schedule events

* Status: proposed (written for implementation review)
* Deciders: FAR
* Date: 2026-07-10

## Context

ADR [28451](../28451-generating-discrete-scheduled-events-for-agent.md) decided that the webapp
computes discrete `[notBefore, notAfter]` intervals ("events") for each schedule and sends them to
agents (via `MODULE_PARAM_SCHEDULE`, ADR
[28535](../28535-passing-discrete-scheduled-events-from-webapp-to-agent.md)). The known negative
consequence is that agents only know a finite number of events: if the webapp stops renewing them,
scheduled directives silently stop running. We need to decide how events are computed, how many, when
the series is renewed, and how nodes get the update.

Schedule frequencies range from very frequent (daily and, later, hourly: thousands of events per
year) to very infrequent (every N months: a couple per year), so a fixed "N days ahead" or a fixed
"N events" alone is not enough.

## Decision

All the logic lives in `com.normation.rudder.schedule` (`DirectiveScheduleServices.scala`), and is
invoked at each policy generation (`FetchAllInfoService` -> `ScheduleManagement.updateSchedules`)
and by the campaign handler (see ADR
[28181 scheduler](28181-use-campaign-engine-as-directive-scheduler.md)).

### Event series (pure computation, `DirectiveScheduleEvents`)

Events are a *pure function of (schedule, maxDate)* - there is no event store. For each enabled
schedule, the occurrence windows are enumerated from "now" (including the currently open window)
with `CampaignDateScheduler.nextCampaignDate`, and the generated series covers:

* the **horizon**: all occurrences starting within `rudder.directive.schedule.event.horizon.days`
  (default 30 days), and
* at least `rudder.directive.schedule.event.min` occurrences (default 3), so that infrequent
  schedules keep working long without renewal, but
* at most `rudder.directive.schedule.event.max` occurrences (default 100), so that frequent
  schedules don't bloat policies.

`details.maxDate` on the campaign records the end of the last generated occurrence: it is the
"generated up to" horizon, shared by expected reports (`JsonDirectiveSchedule.d`) so that compliance
knows whether an agent could have had an event (see ADR
[28181 compliance](28181-compliance-for-scheduled-directives.md)).

An occurrence can resolve to an **empty window** when the schedule time falls in a DST gap (e.g. a
2:00-3:00 Europe/Paris schedule on the spring transition day: both bounds resolve to the same
instant). Such a window can never contain an agent run: it is skipped everywhere - no event is
generated for it, and compliance neither treats it as a current window nor as a closed one (which
would report a false `missing` for the gap day).

### Deterministic event ids

The agent's "run once by event" lock is keyed on the event id, so the id of a given occurrence MUST
be stable across policy generations. Recurrent event ids are therefore *derived*, not stored:
name-based UUID of `"<scheduleId>:<window start epoch millis>"`
(`DirectiveScheduleEvents.eventId`).

### Renewal quantization

Recomputing `maxDate = now + horizon` at every generation would change every scheduled node's
configuration at every generation. Instead, the horizon is only *extended* (`needsRenewal`) when:

* fewer than `min(minEvents, series size)` generated occurrences remain ahead, or
* `maxDate` is closer than half of the fresh series coverage (`now + (last.end - now)/2`).

On renewal, the new `maxDate` is persisted in the campaign immediately (before policies are
written): since the series is a deterministic function of (schedule, maxDate), a failed generation
is simply caught up by the next successful one. In steady state a daily schedule renews about every
15 days.

### Propagation to nodes

* the per-node schedules (`NodeConfiguration.schedules`, i.e. id + enabled + maxDate + recurrence)
  are mixed into the node configuration hash (`nodeContextHash` component, only when non-empty so
  that nodes without schedules keep their historical hash): a horizon extension rewrites the
  policies of exactly the nodes using that schedule;
* `PolicyWriterService` writes into each node's `MODULE_PARAM_SCHEDULE` only the events of the
  schedules referenced by that node's policies;
* each event's `notBefore` is **splayed per node** (`DirectiveScheduleEvents.splayEvents`): it is
  shifted by a deterministic function of the node id (same hash-based algorithm as the agent run
  splay, `ComputeSchedule.computeSplayTime`), bounded to the *first half* of the window so that
  every node keeps at least half of the window to catch a run. This avoids the whole fleet
  executing the scheduled directives - and sending their reports - at the very start of the window
  (e.g. all nodes at 5:00 for the default benchmark schedule). Determinism keeps the written
  policies stable across generations; `notAfter` is unchanged so compliance window bounds stay
  fleet-wide identical;
* past events drop off naturally at each generation (series starts at "now").

### Disabled schedules

A disabled campaign generates no recurrent event: directives using it never run (that is the
user-facing semantic of disabling a schedule), and compliance keeps their last known status. Only
on-demand one-shot events are still emitted (see ADR
[28181 on-demand runs](28181-on-demand-runs-and-overlapping-windows.md)).

## Alternatives

* Persisting generated events in a DB table ("Scheduled Events DB" from the early design). Rejected:
  the deterministic (schedule, maxDate) recomputation gives the same result with no storage, no
  cleanup and trivial consistency.
* Renewing at every generation (no quantization). Rejected: permanent node configuration churn.

## Consequences

* After a long webapp outage, the first generation (or the first campaign tick) self-heals the
  series; agents keep working during the outage as long as the 30-day horizon lasts.
* A schedule change (recurrence, status) changes the campaign, hence the node configuration hash,
  hence the policies of affected nodes at next generation.
* The campaign is saved (file + git commit) at each renewal, about twice a month per schedule.
* The three bounds are configuration file properties; the renewal threshold (half coverage) is not
  configurable.
