# Use the campaign workflow engine as the scheduler for directive schedules

* Status: proposed (written for implementation review)
* Deciders: FAR
* Date: 2026-07-10

## Context

Directive scheduling (see ADR [28451](../28451-generating-discrete-scheduled-events-for-agent.md))
needs a webapp-side scheduler with three responsibilities:

* know *when* the next occurrences of a schedule happen;
* wake up regularly to renew the discrete event series sent to agents before they run out;
* give operators visibility (state, history) on what the scheduler is doing.

Rudder already has a workflow engine for campaigns (`MainCampaignService` and friends), used by the
system-updates plugin. This ADR decides to reuse it, and documents its behavior (states, handler
contract, hooks) as the reference for how directive schedules are orchestrated.

## Alternatives

* A dedicated scheduler (cron-like batch in the webapp). Rejected: it would duplicate persistence,
  state tracking, timezone-aware date computation (`CampaignDateScheduler`) and UI/API that campaigns
  already provide.
* Pure generation-time renewal without a scheduler: rely only on policy generations happening for other
  reasons. Rejected: a quiet Rudder (no changes) would stop renewing events and agents would run out.

## Decision

A directive schedule *is* a campaign: `DirectiveSchedule` (campaign type `directive-schedule`,
version 1, in `com.normation.rudder.schedule.DirectiveSchedule`), persisted through the standard
`CampaignRepository` (file + git) with its own `JSONTranslateCampaign`
(`DirectiveScheduleSerializer`, registered on `CampaignSerializer` in `RudderConfig`).
Directives reference a schedule with `Directive.scheduleId: Option[CampaignId]`.

### Campaign workflow engine behavior (reference)

The engine (`CampaignOrchestrationLogic` + `CampaignScheduler` queue) processes campaign events
one *step* at a time; a handler step never loops. Event states and transitions
(`CampaignEventState`):

```
Scheduled -> [PreHooks] -> Running -> [PostHooks] -> Finished
                 |                        |
                 +---> Failure <----------+          (also: Skipped, Deleted)
```

* `Scheduled`: if the campaign is disabled/archived, the event becomes `Skipped` (handlers are not
  called). Otherwise the engine sleeps until `start - startDelay`, then delegates to the campaign-type
  handler.
* `PreHooks` / `PostHooks`: run by the engine itself (`CampaignHooksService`, filesystem hooks); a
  pre-hook error routes the workflow to `Failure` after post-hooks.
* `Running`: the engine sleeps until `end + endDelay`, then delegates to the handler.
* Terminal states (`Finished`, `Skipped`, `Failure`, `Deleted`): the handler is notified, then the
  engine computes the next occurrence with `CampaignDateScheduler.nextCampaignDate` and bootstraps the
  next `Scheduled` event.
* At webapp start, `MainCampaignService.init()` re-queues persisted `Scheduled`/`Running` events and
  bootstraps an event for enabled campaigns that have none.

Campaign-type handlers implement `CampaignHandler`: a partial function from the campaign to an
`EventOrchestration` (`SaveAndQueue`, `SaveThenUpdateAndQueue`, `Queue`, `SaveAndStop`,
`IgnoreAndStop`). Handlers must be registered on `MainCampaignService` before it starts.

### The directive schedule handler

`DirectiveScheduleCampaignHandler` (registered in `RudderConfig`) maps directive schedules onto this
engine. Each campaign event is one occurrence window of the schedule; the actual work (running or not
the directives) happens on the agent, so the handler only uses the workflow ticks:

* on `Scheduled` (window start): recompute the event series (`ScheduleManagement.updateSchedules`,
  see ADR [28181 event generation](28181-schedule-event-generation-and-renewal.md)); if the horizon
  was extended, trigger a policy generation. Then move to `Running`.
* on `Running` (window end): move to `Finished`; the engine schedules the next occurrence.
* no pre/post hooks and nothing to clean on delete (events only live in generated policies).

A disabled campaign produces no occurrence events (engine skips) and no recurrent schedule events at
generation: directives using it never run, except on demand (see ADR
[28181 on-demand runs](28181-on-demand-runs-and-overlapping-windows.md)). Logs on the
`directive-schedule` logger let sysops trace generation horizon extensions, disabled schedules and
triggered generations.

## Consequences

* One campaign event per schedule occurrence in the DB/UI (e.g. one per day for a daily schedule) -
  the same volume as existing campaigns; operators see schedule activity with existing campaign
  tooling.
* Anything that improves the campaign engine (pause, audit, hooks) benefits directive schedules.
* The engine's start/end delays (1h) mean the renewal check happens up to 1h before the window; this
  is harmless since the renewal quantization works in days.
