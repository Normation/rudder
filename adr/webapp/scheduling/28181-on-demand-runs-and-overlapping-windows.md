# On-demand runs ("run now") and overlapping occurrence windows

* Status: proposed (written for implementation review)
* Deciders: FAR
* Date: 2026-07-10

## Context

Users need to trigger one immediate execution of a scheduled feature (typically a security
benchmark) *whatever its schedule is and without changing it* - including when the schedule is
disabled ("never run, on-demand only" is a supported schedule choice). This creates occurrence
windows that may overlap the recurrent ones, which raises the question of overlap handling.

## Decision

### One-shot events stored in the campaign

`DirectiveScheduleDetails` gains `oneShots: List[DirectiveScheduleOneShot(eventId, start, end)]`:

* the event id is a **random UUID generated once and stored**. Contrary to recurrent events (derived
  deterministically from the recurrence, see ADR
  [28181 event generation](28181-schedule-event-generation-and-renewal.md)), there is no recurrence
  to derive from, and the id must stay stable across policy generations for the agent
  "run once by event" lock;
* one-shot events are emitted to nodes **even when the campaign is disabled** (that is what makes
  "disabled schedule + run now" work); recurrent events remain suppressed;
* expired one-shots are pruned whenever the campaign is next saved (renewal or new one-shot);
* one-shots are part of the schedule summary stored in node configurations and expected reports
  (`JsonDirectiveSchedule.os`, omitted from JSON when empty for compactness and upgrade
  compatibility). This is what makes a "run now" click change the node configuration hash (so the
  next generation actually rewrites node policies) and lets compliance see the on-demand window -
  including when the schedule is disabled.

`ScheduleManagement.addOneShotEvent(campaignId, start, duration)` appends one and saves the
campaign; the caller is responsible for triggering a policy generation so nodes receive the event.

Requesting a run while a window is **already active** at that time (an open one-shot, or a current
recurrent occurrence of an *enabled* schedule) does not create a new event: the agent would either
ignore it (already ran in the active window) or run the directive twice. The request is dropped with
an info log and the active window is returned to the caller. A disabled schedule's recurrent windows
generate no run, so they do not inhibit an on-demand request.

Concurrent campaign file access (a "run now" saving one-shots can race the renewal at generation
time, or another "run now") is serialized at the persistence layer: `CampaignRepositoryImpl` holds
one `zio.stm.TReentrantLock` per campaign id and takes it for read/write on every file operation.
Read-modify-write sequences above the repository remain last-write-wins, which is acceptable for
campaign data (the schedule horizon is recomputed at each generation, and one-shots are pruned).

### Security benchmark "run now"

`POST /securityBenchmarks/{id}/run` (`BenchmarkScheduleService.runNow`) adds a one-shot window of
**30 minutes starting now** and triggers a generation. It is refused when the *benchmark* is
disabled (its rule is disabled, nothing would run) - the UI button is disabled with an explanatory
tooltip in that case. It is also refused for pre-schedule benchmarks (no schedule: they already run
at every agent run).

### Overlapping windows: no special logic beyond creation-time dedupe

When windows overlap (recurrent + one-shot, or several one-shots):

* **creation** of an on-demand event is deduplicated against active windows (see above), but that is
  the only place: once created, events are never merged;
* **generation** emits all events as-is; no merging, no deduplication;
* **the agent** owns execution semantics: each event runs at most once, whatever other events cover
  the same time span;
* **compliance** has no overlap logic either: reports are processed when they arrive (rule 1 of the
  compliance decision table: reports present => compute). A closed on-demand window without any
  report makes the directive `missing`, which is the desired signal for "I asked for a run and got
  nothing".

## Alternatives

* Deriving one-shot ids like recurrent ones. Rejected: nothing stable to derive from; a
  generation-dependent id would break the agent once-lock and re-execute the directive.
* A dedicated one-shot campaign per request. Rejected: campaign churn, cleanup burden, no benefit
  over a small list in the existing campaign.
* Overlap merging in the webapp. Rejected by design: it would create three places (webapp
  generation, agent, compliance) that must agree on overlap semantics; keeping the agent as the
  single owner is simpler and matches ADR 28451's "one source of truth" spirit.

## Consequences

* "Run now" works uniformly for enabled, disabled, and infrequent schedules; the schedule itself is
  never modified by an on-demand run.
* An on-demand run close to a recurrent window may execute the directive twice (once per event):
  accepted, since the user explicitly asked for a run.
* The 30-minute window means nodes whose agent does not run within 30 minutes of the click (agent
  stopped, very long run period) will miss the on-demand run and show `missing` for it.
