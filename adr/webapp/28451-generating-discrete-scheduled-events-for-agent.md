# Generation of discrete scheduled event for agent schedule module

- Status: accepted
- Deciders: AMO, FAR
- Date: 2026-02-27

## Context

We want to be able to schedule directives on schedules that are slower than agent run period, typically "once a day".

The exact schedule format is not the subject of that ADR, but it is assumed it provides the same kind of possibilities as the scheduler for system update campaigns.

In that context, the webapp needs to analyze for each node, for each directive, if a schedule is applied, and reflects that constraint in the generated policies.

## Alternatives

There are three main options:

- 1/ the webapp generates directives only when the schedule is near, with a "execute once" kind of lock, and then clean them up after the interval ends. This is alike what is done in software campaign.
- 2/ we have a common concept of `schedule` between webapp and agent, and we exchange a serialized `schedule` in generated policies. At each run, the agent needs to compute the schedule to check if current time matches the schedule and run directives accordingly
- 3/ only the webapp knows about `schedule` concept and when policy are generated, the webapp also generates a list of future intervals corresponding to that schedule. An interval is just a pair of [starting UTC datetime, ending UTC datetime]. On a run, an agent only needs to check if any existing interval matches current time to enable directives accordingly.

## Decision

We chose the second alternative :
The webapp creates a list of discrete computed intervals to communicate the schedule for next events to the agent for each directive that has a schedule.

The first one is rejected because from experience with software update campaign, it is brittle and was complicated to set up.
It is brittle because there is a lot of latencies to take into account between generation and execution. Also, a lot of ad-hoc logic needs to be added to the execution logic, which would be hard to make work in a generalized way for any directive. Finally, contrary to software campaign, we are really in the realm of periodic compliance here, ie something that should just happen regularly and report standard compliance - just not at the agent period.

The last one is rejected because it would force us to define a common schedule concept between webapp and agent, and be sure that concept is interpreted in exactly the same fashion by the two systems.
This is a big work, that we will certainly want to do given the decoupling and transferred data size reduction that solution would provide. But we are not mature enough for now, and the other part of the scheduling feature, especially the compliance computation, will be a challenge enough to get right. So we prefer to start with only one source of truth about when a schedule is happening. 

## Consequences

Negative:

- webapp and agent are more coupled: an agent only knows for a finite number of scheduled event. They are coupled to the point where something that never change ("always execute that policy once a day") STILL need agent updates to continue to work.

- there is a lot more data transferred between webapp and agent. In place of one schedule object, we need to transfer list of intervals

- we need to ask ourselves "what is the correct size for the list of interval for a schedule?". We want to find a balance between:
  - not having a need to update thing too often (Rudder server should be able to be offline for a couple of hours without consequences, even if there is an hour-based schedule)
  - and still be relevant (for a monthly schedule, do we really need like 10 months ahead?)

Positive:

- we don't need to specify an intercommunication schedule format between agent and webapp yet. That schedule concept remains something internal to the webapp.

- webapp is the only interpreter of a schedule: there is no risk of having webapp and agent differing about when the "next schedule" happens, and so there is no risk of sending reports at an unexpected time
