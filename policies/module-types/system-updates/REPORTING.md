# System updates reporting format

This document specifies the JSON documents produced by `rudder-module-system-updates`
and older implementations to
report the progress and result of a system update campaign event.
Results they produced are still stored on the server,
so changes must stay backward compatible with the server-side parser (`system-updates`
plugin).

This document describes the format as of Rudder 9.2.

The way the report is handled by the agent and filtered from the logs in database
is out of this document's scope.

## Producers

* **Rust module**: `rudder-module-system-updates`, the reference implementation. The
  formats below describe what it produces.
* **Python module** (Linux, removed): `system_update.py`, shipped in the technique as a
  fallback for agents older than 8.2 or unsupported OSes, removed by #28495 (see the
  `rudder-plugins-private` history).
* **PowerShell implementation** (Windows): used by the Windows technique when
  `rudder-module-system-updates.exe` is not installed. It writes its files itself and
  sends them with the same components.

The differences of the legacy producers (Python and PowerShell) are given in a *Legacy
producers* paragraph in each section. They are descriptive: new producers must follow the
formats of the Rust module.

## Schedule report

Written once, when the event is first seen and scheduled (for scheduled campaigns, the
date is the actual start time for this node, after splay in the campaign window).

```json
{"status":"success","date":"2026-09-30T22:41:17Z"}
```

| Field    | Type   | Required | Description                                             |
|----------|--------|----------|---------------------------------------------------------|
| `status` | string | yes      | Always `success`.                                       |
| `date`   | string | yes      | Planned start of the update, RFC 3339 date-time in UTC. |

Notes:

* `date` uses the `Z` suffix. It can carry a fractional seconds part (up to nanoseconds),
  notably for immediate events, where it is the current time.
* When the schedule date is already reached, the module writes the schedule file and
  continues with the update in the same run.

Legacy producers:

* Python: `status` is `scheduled` (not `success`), and `date` uses Python's
  `isoformat()`, with a `+00:00` offset instead of `Z`:
  `{"date": "2026-09-30T22:41:17+00:00", "status": "scheduled"}`. The splay is seeded by
  node id + event id (the Rust module only uses the node id), so the dates differ.
* PowerShell: `status` is `scheduled`, `date` is the splayed start formatted by
  `Rudder.Utils.ToRFC3339`. Written compressed.

## Update report

Written at the end of the event (after the post-update actions, so after the reboot if
any), or as soon as the event fails.

```json
{
  "software-updated": [
    {
      "name": "xz",
      "arch": "x86_64",
      "old-version": "5.2.4-3.el8",
      "new-version": "5.2.4-4.el8",
      "action": "updated"
    },
    {
      "name": "kernel-core",
      "arch": "x86_64",
      "new-version": "4.18.0-553.el8",
      "action": "added"
    }
  ],
  "status": "repaired",
  "output": "\ncmd: yum '-y' 'update'\nLast metadata expiration check: ...\nComplete!\n",
  "errors": "\nwarning: ..."
}
```

(Pretty-printed here, a single line in the actual file.)

| Field              | Type                                   | Required | Description                                  |
|--------------------|----------------------------------------|----------|----------------------------------------------|
| `software-updated` | array of [package change](#package-change) | yes  | Changes in installed packages, may be empty. |
| `status`           | string, see [status](#status)          | yes      | Global result of the event.                  |
| `output`           | string                                 | yes      | Captured standard output of all steps.       |
| `errors`           | string                                 | no       | Captured error output of all steps.          |

Legacy producers:

* Python:
  * `errors` is always present, possibly empty;
  * `software-updated` is **missing** when the pre-run hooks fail or when a software
    update has an empty package list.
* PowerShell:
  * written by `ConvertTo-Json`, so **pretty-printed on several lines**;
  * `errors` is never present.

### Status

| Value      | Meaning                                                                          |
|------------|----------------------------------------------------------------------------------|
| `success`  | All steps succeeded, no package change was detected.                             |
| `repaired` | All steps succeeded, and at least one package was added, updated or removed.     |
| `error`    | At least one step failed. `software-updated` may still list the changes made.    |

`scheduled` is also a valid value of the enumeration but is never produced in an update
report.

The status is computed step by step: any failing step (pre-run hooks, package listing,
cache update, upgrade, pre-reboot hooks, reboot check, service restart, reboot, post-run
hooks) turns it into `error`, and it never goes back. The upgrade step only makes it
`repaired` if it is not already in `error`.

Legacy producers:

* PowerShell: `status` is only `success` or `error`, never `repaired`, even when updates
  were installed.

### Output and errors

`output` and `errors` are the concatenation, in execution order, of the lines logged by
each step, **each line prefixed by `\n`** (so non-empty values start with a newline). They
contain:

* for each external command, a `cmd: <program> '<arg1>' '<arg2>'...` line in `output`,
  followed by its standard output (unless it is not captured, e.g. for package listing) and
  its standard error in `errors`;
* the messages of the module itself (`output`), e.g.
  `A reboot is required to complete the update, rebooting now.`;
* the error chains of failed steps and the reason of an aborted event (`errors`), e.g.
  `Pre-run hooks failed, aborting upgrade`.

These fields are meant for display to humans only, their content is not part of the
format and must not be parsed.

`errors` is **omitted** when it is absent or contains only whitespace or control
characters. `output` is always present but can be empty (`""`).

Legacy producers:

* Python: `output`/`errors` hold `# Running <command>`, `# Running hook <name>`,
  `# Skipping hook <name>: <reason>` and `# Rebooting the system now` marker lines, the
  parts being joined with `\n` (no leading newline).
* PowerShell: `output` is the content of the campaign log file.

### Package change

A change is computed as the difference between the list of installed packages before and
after the upgrade. Packages are identified by the `(name, arch)` pair: the same name with
two architectures means two distinct packages.

| Field         | Type   | Required    | Description                                                      |
|---------------|--------|-------------|------------------------------------------------------------------|
| `name`        | string | yes         | Package name.                                                    |
| `arch`        | string | yes         | Architecture, as named by the package manager.                   |
| `old-version` | string | conditional | Version before the update. Present for `updated` and `removed`.  |
| `new-version` | string | conditional | Version after the update. Present for `updated` and `added`.     |
| `action`      | string | yes         | One of `added`, `updated`, `removed`, `pending-install` (9.2+).  |
| `details`     | string | conditional | Package manager specific details, for display. Only present for Windows packages. |

`pending-install` (9.2+) is an update that the package manager processed during the
event, but that is not in the list of installed packages after it: typically an update
waiting for a reboot to be fully installed, but also an update that failed to download or
install. The format does not constrain its versions (the only current producer, Windows
Update Agent, sets none, as hotfixes have no real version). These entries are appended
after the computed changes, and do **not** count for the `repaired` status: an event
where every entry is `pending-install` has status `success`.

Absent fields are omitted, never `null`. The order of the array is unspecified.

`details` is only set by package managers that provide per-package results (currently
Windows Update Agent).

#### Values per package manager

| Package manager             | `arch`                          | Versions                                                 |
|-----------------------------|---------------------------------|----------------------------------------------------------|
| APT                         | `amd64`, `all`, …               | Debian version, e.g. `1:2.4.52-1ubuntu4.6`               |
| DNF/YUM, Zypper (via `rpm`) | `x86_64`, `noarch`, …           | `[epoch:]version-release`, epoch omitted when empty or 0, e.g. `5.2.4-4.el8`, `1:1.12.8-18.el8.1` |
| Windows Update Agent        | always `noarch`                 | always `none:none`, omitted for `pending-install`        |

On Windows, `name` is the title of the update (e.g.
`2026-09 Cumulative Update for Windows Server 2022 for x64-based Systems (KB5065432)`),
installed updates are reported as `added` (or `pending-install` from 9.2)

Legacy producers:

* Python: no `details`, but an optional `error` field on `added` and `updated` entries,
  set to the dpkg state (`half-configured` or `half-installed`) when the package is not
  fully installed. Versions have the same format as in the Rust module (the epoch is
  stripped when empty or 0). Older versions of the script printed `(none):` for an empty
  epoch, as in the example of the module user documentation.
* PowerShell: every installed update is reported with `action: added`, `arch: noarch`,
  `old-version` **and** `new-version` both set to `(none):(none)`, and:
  * a per-package `status` field, `success` or `failed` (Windows Update result code other
    than 2, "succeeded");

  The per-package `status` is the only way to report a failed package; the Rust module
  reports failures in the global `status` and in `details` only.

## Error cases

* When the event fails after it has started (including a reboot command failure), the
  report stored in the database so far is written with `status: error` and the error
  appended to `errors`. When no report was stored yet, it is an empty report
  (`software-updated: []`, `output: ""`) with the error.
* When the module parameters are invalid, when the policy mode is audit, or on the Rudder
  root server for system/security campaigns, **no report file is written**: the module
  returns an error outcome and the technique only reports a standard `result_error`.
* When the agent does not support package excludes but the campaign uses some, the
  technique sends, without calling the module:

  ```json
  {"status": "error", "software-updated": [], "errors": "Module does not support package excludes, aborting."}
  ```

  so the `output` field can be missing in reports received by the server.

Legacy producers:

* Python: on the root server, the report is sent with `status: error`, an empty
  `software-updated`, and the reason in `output` (the Rust module sends no report).

## Consumer requirements

A consumer of these documents (the server-side parser in the `system-updates` plugin):

* must ignore unknown fields (`arch`, `action`, `error`…), to allow adding fields in the
  future;
* must accept documents pretty-printed on several lines;
* must accept a missing `output` and `errors`, and missing `old-version`, `new-version`,
  `details` and per-package `status`;
* should accept a missing `software-updated` (legacy Python reports), as an empty list;
* must accept `repaired`, `success`, `error` and `scheduled` as the global `status`;
* must accept `pending-install` package changes (9.2+) with or without `old-version`
  and `new-version` (they currently have neither), and should use `action` rather than
  the presence of the versions to classify them;
* must accept both the `Z` and `+00:00` forms, and fractional seconds, in the schedule
  `date`.

## Changes by version

### 9.2

* **New `pending-install` package action** (#29139, Windows Update Agent only). Updates
  that were downloaded or installed but are not reported as installed afterwards (pending
  reboot, failed download or install) were previously absent from the report; they are
  now listed with their `details` and no version.

## JSON Schema

The formats above are formalized as a JSON Schema (draft 2020-12) in
[`reporting.schema.json`](reporting.schema.json): `#/$defs/schedule` for the schedule
report and `#/$defs/update` for the update report. It describes what the Rust module
produces, not the [legacy producers](#producers), and does not forbid additional
fields.

The crate tests (`schema_tests` in [`src/output.rs`](src/output.rs)) validate reports
serialized by the module against it, so both have to be updated together.
