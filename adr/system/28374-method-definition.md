# Genric Method definition

- Status: Proposal
- Deciders: FDA
- Date: 2026/02/18

## Context

Generic Methods are a core part of our techniques ecosystem. For historical reasons the methods
interface was different on Linux and Windows agents.

Starting 9.1 we finally have a common framework to execute, test and parse generic methods.
In order to prepare for future changes to them, we should state what a GM is.

This ADR applies to all Generic Methods starting 9.1. Existing and future ones.

## Decision:

- A Generic Method (GM) is a configurable policy execution unit based on a declarative system
  state description with an interface defined by its metadata, returning an outcome
  status and usable in techniques.

- GM main source file must be a `.cf` file containing an agent bundle of the same name
- GM metadata must be in its `.cf` file, mandatory fields are:
  - `@name`: Human readable name of the method
  - `@description`: Human readable short description
  - `@parameter`: Parameter description and constraints
  - `@class_prefix`: Legacy resulting condition prefix (first part)
  - `@class_parameter`: Legacy resulting condition prefix (second part)
- Windows compatible GM must also have a `.ps1` file containing a single function named
  after the Train-Cased version of the bundle name from the `.cf` file
  ```
  service_ensure_disabled_at_boot -> Service-Ensure-Disabled-At-Boot
  ```
- An existing GM input parameters can not be changed after being released

- GM execution always return a status:
  - *not_applicable* if the execution was skipped (due to compatibility for instance)
  - *kept* if the current system state matches the one defined by the GM call
  - *repaired* if the current system state was not matching the one defined by the GM call but
    the agent fixed it
  - *error* if the current system state was not matching the one defined by the GM call and
    the agent was unable to fix it. Or if an error occurred in the GM execution


- GM execution must define legacy conditions based on its outcome status following the pattern:
+
```
<class_prefix>_<class_parameter>_<outcome_status>
```

- GM execution must define unique conditions based on its outcome status following the pattern:
+
```
<directive_id>_<method_id>_<optional_loop_index>_<outcome_status>
```

⚠️  When a GM is inside a *foreach* loop, its resulting condition outside of the loop should be the worst
case of the individual resulting conditions.


⚠️  When inside of a *foreach* loops, we must ensure that each branch uses a unique resulting condition,
so the *method_id* is suffixed by `-0`, `-1`, etc.. where the digit is the index of the loop branch, starting at 0.
In case of nested *foreach* loops, the suffixes are concatenated, starting by the outer branch.
For worst case computation, we use by priority: *error*>*repaired*>*kept*>*not_applicable*

- GM execution always return a Rudder report based on its outcome status and the policy mode:

| outcome status \ Policy mode | Enforce           | Audit                                  |
| kept                         | result_success    | audit_compliant                        |
| repaired                     | result_repaired   | - (impossible)                         |
| error                        | result_error      | audit_error (runtime error) \| audit_noncompliant (state mismatch)     |
| not_applicable               | result_na         | audit_na                               |

## Consequences

Existing methods should be compliant, new ones must follow those guidelines.

Rudderc must be adapted to make the *foreach* loop cases work. It currently does not generate
the worst case result condition.
