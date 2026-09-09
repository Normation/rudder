# Security benchmark schedules: storage, campaign derivation and shared UI

* Status: proposed (written for implementation review)
* Deciders: FAR
* Date: 2026-07-10

## Context

Security benchmarks are the first user-facing feature configuring directive schedules. 
They have specific requirements:

* we must ensure consistency between Linux and Windows platform while in the same time accounting for  
  the fact that Windows doesn't support scheduling yet;
* the schedule is set in a "Schedule" tab at creation, displayed as a human-readable phrase under the
  description of existing benchmarks, and editable in their "Information" tab;
* it must look similar to system-updates campaign schedule UI. 

Moreover, the benchmark scheduling must stay transposable to other consumers than benchmarks (e.g. pure
directive scheduling). 

## Decision

* the scheduling UI elements common to system-updates and benchmarks are factored out in a common part, 
  * that ensure the benchmark case looks and behaves like the system-updates campaign schedule UI, 
  * but we choose to have only a frequency + start selector: the end is a duration after the start (1 to 12 hours);
  * node execution is spread on window duration like for update campaigns;
* the frequency choices include a case **at every agent run**. It matches the behavior on Rudder 9.1 (no schedule 
  at all). This schedule will be only frequency for Windows. 
* schedule can be disabled, which is different from disabling the benchmark. In that case, a benchmark never 
  automatically run, but still run on an "run now" interactive trigger.

### Storage: benchmark only have a reference to the campaign which is the single serialization point

The schedule ID of a benchmark is serialized only in its directive schedule campaign.
`SecurityBenchmark` gains `scheduleCampaign: Option[CampaignId]`, a plain reference to that campaign

The ID uses a deterministic pattern `security-benchmark-<benchmarkId>`

`BenchmarkSchedule` is defined as an ADT used at API/UI view level and managed by `BenchmarkScheduleService`. 
It has three cases:
- `EveryAgentRun`: historical mode where `scheduleCampaign` is `None`; directive doesn't have a schedule id, so no run condition
- `Recurrent(schedule)`: campaign enabled; the schedule id defines events for run during the recurrence windows. 
- `OnDemandOnly(schedule)`: campaign is disabled but recurrence kept; run only in on-demand windows. 

**INVARIANT: a benchmark has a schedule campaign if and only if its mode is not `EveryAgentRun`.**

This is enforced in `BenchmarkScheduleService.setSchedule`.

Other aspects of schedules: 
- on creation without an explicit schedule the server applies the default (`Recurrent`, daily
  5:00-6:00, server timezone); 
- on update without the field, the current mode is kept.

The campaign lifecycle follows the benchmark (`BenchmarkScheduleService`): created/updated/deleted
on benchmark save, event generation state (`maxDate`, one-shots) preserved, deleted with the
benchmark. Benchmark directives are generated with `scheduleId = scheduleCampaign`, *including when
the campaign is disabled* (that is what enforces "never runs except on demand").

### Benchmark platform attribute for specialized behavior on Windows

A scheduled directive is guarded by its schedule run condition in the generated policies, 
then checked by the scheduler module (ADR
[29567](29567-passing-scheduled-events-through-a-generated-json-file.md)). 
Scheduling a Windows benchmark is not possible untile the module also exists on Windows: a schedule guard would 
never be true and the benchmark never executed. 

To be able to differentiate between Linux and Windows cases, we added a `platform` attribute to the benchmark model. 

`platform` is optional, with values: 
- `linux`: the default when absent, which is the case of every model published before it existed
- `windows`.

`BenchmarkScheduleService.forPlatform` coerces the mode of a Windows benchmark to `EveryAgentRun`.

At webapp start, `CheckBenchmarkScheduleCampaigns` re-asserts both invariants: it puts back to
`EveryAgentRun` any benchmark whose platform can not honour its schedule (which is what heals a
Windows benchmark created before its platform was known), and recreates a referenced campaign that
does not exist with the default schedule (e.g. a benchmark imported without its campaign).

### Shared UI: `common-elm/Scheduling`

The schedule selector, summary phrasing, JSON codecs and schedule data types are extracted from the
system-updates Campaigns app into `rudder-plugins-private/common-elm/Scheduling`.

`Scheduling.View.recurrentScheduleForm` is host-agnostic (config with an `onSchedule` message
constructor) and supports two end modes: `ExplicitEnd` (campaigns, with the duration-lock toggle)
and `SpreadHours` (benchmarks: a 1-12h duration selector replaces the end controls, duration is
always preserved when the start moves). 

This extraction also deduplicates the two previously copy-pasted `scheduleForm` implementations inside system-updates. 
The one-shot mode and its date picker stay in system-updates since they are specific to it.


## Alternatives

* The benchmark as the source of truth, with the schedule serialized inside it and the campaign
  derived from it. Initially PoC'ed then rejected: it serializes the same schedule in two places,  
  and every future consumer of directive scheduling would have to replicate that duplication. 
  Trade-off accepted instead: an exported benchmark does not carry its custom schedule automatically. If it is imported, 
  schedule needs to be imported to, else a new one with default values will be recreated. 
* A dedicated "frequency + spread" schedule type. Rejected: `CampaignSchedule` already expresses it
  as start/end; a new type would ripple through core serialization, the scheduler and the agent
  interface for no expressiveness gain.

## Consequences

* Everything user-visible about a benchmark schedule round-trips through the benchmark API (the
  responses join the schedule from the campaign); the campaign is an implementation detail (still
  visible to campaign tooling for operators).
* Disabling a benchmark and disabling its schedule are **intentionally asymmetric**: disabling the
  *benchmark* disables its rule - nothing runs, on-demand runs are refused, and its compliance and
  score disappear from the dashboard; disabling the *schedule* keeps the benchmark active - its
  compliance and score stay on the dashboard, computed from the last runs - but hands the run
  timing over to humans ("run now").
* system-updates and security-benchmarks now share one schedule UI: future fixes/features (e.g.
  hourly frequency) land in one place.
* A user who wants the pre-scheduling behaviour back can ask for it
* Windows benchmarks runs as in Rudder 9.1 and will be able to get the scheduling feature once 
  the scheduler module is implemented for that platform. 
