# Passing scheduled events to the agent through a generated JSON file

- Status: accepted, supersedes [28535 - Generation of discrete scheduled event for agent schedule module](28535-passing-discrete-scheduled-events-from-webapp-to-agent.md)
- Deciders: FAR
- Date: 2026-08-20

## Context

ADR #28535 chose to pass the list of discrete schedule events from webapp to agent through a system variable, `MODULE_PARAM_SCHEDULE`, and explicitly rejected the "dedicated JSON file" alternative on the ground that parsing a JSON file in CFEngine to then pass parameters to the module would be "extremely painful".

That decision did not survive contact with reality (#29567). The system variable was expanded by StringTemplate into the generated `common.cf`:

```
"schedule_events"       string => '&MODULE_PARAM_SCHEDULE&';
```

and read back by `scheduler.cf` as `"data" string => "${system_common.schedule_events}"`.

Embedding a whole JSON document as a CFEngine string literal inside a generated `.cf` file is syntactically fragile: the value crosses three escaping layers in a row — StringTemplate substitution, the CFEngine parser (a multi-line single-quoted literal), then CFEngine variable expansion. A single quote in an event or schedule name is enough to make `cf-promises` reject the whole `common` technique, so one badly named benchmark breaks the node's entire policy, not merely its scheduling. It also puts a growing, unbounded blob in `rudder.json` and in the promises, for data that is only ever consumed verbatim by one module.

The rejected alternative turns out to be cheap after all: nothing needs to parse the JSON in CFEngine. The `scheduler` module wants the document as-is in its `data` attribute, so CFEngine only has to read the file and hand the bytes over.

## Decision

### A dedicated file, next to the promise that reads it

The webapp writes `scheduled_events.json` into the node's `common/1.0/` directory, i.e. right beside `scheduler.cf`, from `PolicyWriterServiceImpl.writeScheduledEventsJson` (path constant `filepaths.SCHEDULED_EVENTS_JSON`). It is written for every node on every generation, **including when the event list is empty** (`{"events": []}`), so that the agent-side `readfile` can never fail on a missing file.

Being in the policy tree, the file is distributed to the node like any other input and is covered by the node configuration hash, so a change of schedule triggers a new generation for that node exactly like any other policy change.

`scheduler.cf` reads it relative to its own location, so nothing has to know the absolute node inputs path:

```
"data" string => readfile("$(this.promise_dirname)/scheduled_events.json", inf);
```

and passes the result as the module's `data` attribute. CFEngine parses a scalar custom-promise attribute value as JSON when it can, so the module receives the object, not a string.

Consequently `MODULE_PARAM_SCHEDULE` is removed everywhere: the `SystemVariableSpec`, `common.st`, `rudder.json` and the technique's `metadata.xml`.

We deliberately do not introduce a `module_inputs/` directory for now: putting the file next to the promise that consumes it keeps `$(this.promise_dirname)` usable and avoids inventing a convention before we have a second module needing one.

### JSON format

The wire format is snake_case, because it is deserialized directly by the module's `Event` struct (`policies/module-types/scheduler/src/event.rs`) and the field names are the serde contract:

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

The JSON is still a map with an `events` attribute rather than a bare array, for the same reason as in #28535: the module may need other parameters later, and they can then be added as sibling attributes. That envelope is defined by `SchedulerParameters` in `policies/module-types/scheduler/src/lib.rs`, and mirrored by `ScheduledEventJsonFormat.ScheduledEventsJson` on the webapp side.

Event attributes are unchanged in meaning from #28535, only renamed:

- `schedule`: type of schedule for that interval. In the `once` case, the module ensures the event runs at most once.
- `id`: the *event* ID. Only used for logs and internally by the module. Must change on webapp side if anything about that event changes.
- `schedule_id` (was `scheduleId`): the *technique's schedule* ID. Stable across the events of a schedule, and used to derive the run class.
- `name`: name of the event, for humans.
- `type`: which Rudder feature defined that event. Purely informative.
- `not_before`/`not_after` (were `notBefore`/`notAfter`): interval boundaries during which the schedule is valid for that event.

Note that the camelCase names documented in #28535 could never have worked: the module has always expected snake_case. A duplicate `event_id` field (an alias of `id`) existed transiently on the webapp side and has been removed — the module only reads `id`.

### Where the module keeps its state

The module's SQLite database lives in the directory given by the promise's `state_dir` attribute. It is now `/var/rudder/scheduler/`, which is also `MODULE_DIR`, the default the module's own CLI uses. Previously `scheduler.cf` passed `/var/rudder/tmp/`, so `rudder-module-scheduler --list` inspected an entirely different (empty) database than the one the agent was writing to, which reads exactly like "the events are not inserted". The directory is created and kept `0700` by the agent packages, since it holds the node's scheduled events.

### Class name when interval is valid

Unchanged: `schedule_${schedule_id}_run`.

## Consequences

- Policy generation can no longer be broken by the *content* of an event: the JSON never transits through the CFEngine parser as a literal.
- The contract is a real file on the node, so it can be inspected, diffed between generations, and replayed by hand against the module — which makes the whole feature debuggable in a way an inlined system variable was not.
- `rudder.json` and the generated `common.cf` stay small and stable, regardless of how many events a node has.
- One residual sharp edge: the file content still goes through one round of CFEngine variable expansion (it is read into a `string` and passed as `"${data}"`), so a `${...}` or `$(...)` sequence inside an event name would be substituted instead of reaching the module. Using `readjson()` directly in the attribute would remove that, at the cost of relying on a data container rather than a scalar; not done yet, it needs validation on a real agent.
- As with #28535, any change to the data defined here needs a coordinated webapp and agent change.
