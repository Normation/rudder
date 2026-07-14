# Security benchmark schedules: storage, campaign derivation and shared UI

* Status: proposed (written for implementation review)
* Deciders: FAR
* Date: 2026-07-10

## Context

Security benchmarks (rudder-plugins-private) are the first user-facing feature configuring directive
schedules. Requirements:

* the schedule UI must look and behave like the system-updates campaign schedule UI, but with only a
  frequency + start selector: the end is a **spread duration** after the start (1 to 12 hours: 0 was
  initially considered but rejected, since a zero-length window can never match an agent run);
* the frequency choices include **disabled** ("never run", on-demand only, with an explanation);
* default for new benchmarks: daily, starting at 5:00, 1 hour spread;
* directive scheduling must stay **transposable** to other consumers than benchmarks (e.g. pure
  directive scheduling): benchmark-specific choices must not leak into how schedules are stored;
* the schedule is set in a "Schedule" tab at creation, displayed as a human-readable phrase under the
  description of existing benchmarks, and editable in their "Information" tab.

## Decision

### Storage: the campaign is the single serialization point, the benchmark only references it

The schedule of a benchmark is serialized **only** in its directive schedule campaign.
`SecurityBenchmark` gains `scheduleCampaign: Option[CampaignId]`, a plain reference to that campaign
(deterministic id `security-benchmark-<benchmarkId>`). Serializing the schedule a second time inside
the benchmark was initially implemented and rejected in review: duplicated serialization of the same
data is a code smell, and since the scheduling feature must be transposable beyond benchmarks, any
duplication belongs to the consumer - so the consumer must not have one.

`BenchmarkSchedule(enabled: Boolean, schedule: CampaignSchedule)` survives as the **API/UI view
only**, assembled from the campaign by `BenchmarkScheduleService` (`enabled` maps to the campaign
status, the recurrence is the campaign schedule) and applied back to it on update:

* the recurrence is a plain core `CampaignSchedule` (wire format of the campaign API,
  `@jsonDiscriminator("type")`): **no new schedule type is introduced**; the spread is simply
  `end - start`, computed in the UI (`Scheduling.DataTypes.spreadHours` / `withSpreadHours`);
* `enabled = false` (campaign disabled) keeps the recurrence (re-enabling restores it) but means
  "never run except on demand";
* a `None` reference only exists transiently, for benchmarks created before schedules existed: they
  are **migrated at plugin init** (`MigrateBenchmarkSchedules`, a dedicated bootstrap check run
  before the campaign existence check): their campaign is created with the default schedule
  (`BenchmarkSchedule.default`: daily 5:00-6:00, server timezone), the reference is stored in the
  benchmark, and their policies are regenerated so that their directives get the schedule id. Until
  migration runs (or if it fails, it is retried at next start), a `None` benchmark keeps its
  previous behavior: run at every agent run;
* on creation without an explicit schedule, the server applies the default; on update without the
  field, the campaign's current schedule is kept.

The campaign lifecycle follows the benchmark (`BenchmarkScheduleService`): created/updated on
benchmark save, event generation state (`maxDate`, one-shots) preserved, deleted with the benchmark.
At webapp start, `CheckBenchmarkScheduleCampaigns` verifies that every referenced campaign exists
and recreates missing ones with the default schedule (e.g. a benchmark imported without its
campaign). Benchmark directives are generated with `scheduleId = scheduleCampaign` whenever the
reference exists, *including when the campaign is disabled* (that is what enforces "never runs").

### Shared UI: `common-elm/Scheduling`, not a symlink

The schedule selector, summary phrasing, JSON codecs and schedule data types are extracted from the
system-updates Campaigns app into `rudder-plugins-private/common-elm/Scheduling/{DataTypes,
DateUtils, JsonCodec, View}.elm`, consumed through elm.json `source-directories` - the repository's
established sharing mechanism (already used by `common-elm/Dashboard`). A symlink under one plugin's
sources was considered and rejected: shared code owned by a single plugin, fragile symlinks
(Windows checkouts, IDE indexing), and a second ad-hoc sharing mechanism.

`Scheduling.View.recurrentScheduleForm` is host-agnostic (config with an `onSchedule` message
constructor) and supports two end modes: `ExplicitEnd` (campaigns, with the duration-lock toggle)
and `SpreadHours` (benchmarks: a 1-12h duration selector replaces the end controls, duration is
always preserved when the start moves). This extraction also deduplicates the two previously
copy-pasted `scheduleForm` implementations inside system-updates. The one-shot mode and its date
picker stay in system-updates (benchmarks don't use them).

### Benchmark screens

* creation: a "Schedule" tab hosting the selector (frequency including
  "Disabled (run on demand only)" with an explanation message, start, timezone - included for
  consistency with campaigns -, spread, summary card);
* existing benchmark: the phrase (`ViewSchedule.schedulePhrase`) under the description, above the
  tab bar; the same selector in the "Information" tab; the "Run now" button next to the other action
  buttons, disabled with a tooltip when the benchmark is disabled (see ADR
  [28181 on-demand runs](28181-on-demand-runs-and-overlapping-windows.md));
* the plugin exposes its own support endpoints (`GET .../schedule/timezones`, `GET .../schedule/tz`,
  `POST .../schedule/preview`) mirroring the system-updates ones, so benchmarks work without the
  system-updates plugin installed.

## Alternatives

* The benchmark as the source of truth, with the schedule serialized inside it and the campaign
  derived from it. Initially implemented (for export/import self-containment), rejected in review:
  it serializes the same schedule in two places, and every future consumer of directive scheduling
  would have to replicate that duplication. Trade-off accepted instead: an exported benchmark does
  not carry its custom schedule; importing it recreates the campaign with the default schedule.
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
* Migrating legacy benchmarks changes their behavior at upgrade: they go from "checks at every
  agent run" to the default daily schedule. This is the intended trade-off for a uniform model.
