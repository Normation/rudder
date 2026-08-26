# Passing scheduled events to the agent through a generated JSON file

- Status: accepted, supersedes [28535 - Generation of discrete scheduled event for agent schedule module](28535-passing-discrete-scheduled-events-from-webapp-to-agent.md)
- Deciders: FAR
- Date: 2026-08-20

## Context

ADR #28535 chose to pass the list of discrete schedule events from webapp to agent through a system variable, `MODULE_PARAM_SCHEDULE`.

```
"schedule_events"       string => '&MODULE_PARAM_SCHEDULE&';
```

Where `MODULE_PARAM_SCHEDULE` is a JSON string (not JSON data) which is latter passed in scheduler module input. 

That decision missed the problem of correctly escaping the JSON string with regard to CFEngine rules. 
To avoid the fragility of managing in Scala the CFEngine escaping rule in something as complex as JSON, we decided to revert the previous choice. 


## Decision

### Use a dedicated `scheduled_events.json` file

The webapp writes `scheduled_events.json` into the node's root policy directory, next to `rudder.json`.
This place exists in both Linux and Windows agent and will allow a common layout. 

`scheduled_events.json` is then directly read by `scheduler.cf` as a string, managing the internal escaping needed for CFEngine: 

```
"data" string => readfile("$(this.promise_dirname)/../../scheduled_events.json", inf);
```

The result as the module's `data` attribute. 

Consequently `MODULE_PARAM_SCHEDULE` is removed everywhere: `SystemVariableSpec`, `common.st`, `rudder.json` and `metadata.xml`.

### JSON format

The wire format is snake_case to follow convention from `rudder-module-scheduler`:

```
{
  "events": [
    {
      "schedule": "once",
      "id": "600abb6b-c294-4ba1-9014-944b67d59935",
      "schedule_id": "df6ebe63-a13a-484f-9add-57836517947a",
      "name": "CIS RHEL9 - 2025/12/01",
      "type": "benchmark",
      "not_before": "2025-12-01T11:23:05+01:00",
      "not_after": "2025-12-01T23:23:05+01:00"
    },
    {
      "schedule": "always",
      "id": "b4f0e7e8-3767-4746-abf7-4a5e21f5dd47",
      "schedule_id": "df6ebe63-a13a-484f-9add-57836517947a",
      "name": "System update debug",
      "not_after": "2025-12-01T23:23:05+01:00"
    },
    ...
  ]
}
```

That envelope is defined by `ScheduledEventJsonFormat.ScheduledEventsJson` on the webapp side ; and read by 
`SchedulerParameters` in `policies/module-types/scheduler/src/lib.rs` on Rust side. 

Event attributes are unchanged in meaning from #28535, only renamed:

- `schedule`: type of schedule for that interval (only `once` for now).
- `id`: the *event* ID. Only used for logs and internally by the module. Must change on webapp side if anything about that event changes.
- `schedule_id` (was `scheduleId`): the *technique's schedule* ID. Stable across the events of a schedule, and used to derive the run class.
- `name`: name of the event, for humans.
- `type`: which Rudder feature defined that event. Purely informative.
- `not_before`/`not_after` (were `notBefore`/`notAfter`): interval boundaries during which the schedule is valid for that event.

### Where the module keeps its state

The module's SQLite database lives in the directory given by the promise's `state_dir` attribute. It is now `/var/rudder/scheduler/`, which is also `MODULE_DIR`, the default the module's own CLI uses. Previously `scheduler.cf` passed `/var/rudder/tmp/`, so `rudder-module-scheduler --list` inspected an entirely different (empty) database than the one the agent was writing to, which reads exactly like "the events are not inserted". The directory is created and kept `0700` by the agent packages.

### Class name when interval is valid

Unchanged: `schedule_${schedule_id}_run`.

## Consequences

- CFEngine can't be incorrectly escaped since we read from an external JSON file (escaping is managed by CFEngine).
- The contract is a real file on the node, so it can be inspected, diffed between generations, and replayed by hand against the module — which makes the whole feature debuggable in a way an inlined system variable was not.
- `rudder.json` and the generated `common.cf` stay small and stable, regardless of how many events a node has.
- As with #28535, any change to the data defined here needs a coordinated webapp and agent change.
