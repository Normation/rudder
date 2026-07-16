# Generation of discrete scheduled event for agent schedule module

- Status: accepted
- Deciders: AMO, FAR
- Date: 2026-03-16

## Context

We want to be able to schedule directives (from the point of view of users)/techniques (from the point of view of agent) on schedules that are slower than agent run period, typically "once a day".

In ADR #28451, we describe that we choose to pass discrete schedule intervals from webapp to agent so that we have only one source of truth about when a schedule is happening. 

Here, we describe how we pass these intervals from webapp to agent

## Alternatives

In all case, we have the same idea to pass the intervals in a JSON data structure that contains a list of schedule events. 

We had several main option to transfer that data from webapp to agent: 

- use a dedicated `module_param_schedule.json` file, potentially under a `modules_inputs` directory. This solution allows to clearly and cleanly split module parameters from the other parts of Rudder. 
- set an new attribute in `rudder.json` file, 
- use a system variable (which also implies having a new attribute in `rudder.json` with that system variable name)

## Decision

### Using a system variable

We chose the system variable :

- We don't have the agent side logic to handle module parameters like that, and it would have been extremely painful to parse the JSON file in CFEngine to then pass parameters to the module. 
- Updating only `rudder.json` was not bringing much so it was more consistent with other part of Rudder to have a system variable.


The system variable name is: `MODULE_PARAM_SCHEDULE`.
The name was chosen in the idea that we may have a pattern of such system variables that only contain a big JSON, which is exactly used as the input for a Rudder module on agent side. 


### JSON format

The JSON format is: 

```
{
  "events": [
    {
      "schedule": "once",
      "id": "600abb6b-c294-4ba1-9014-944b67d59935",
      "scheduleId": "df6ebe63-a13a-484f-9add-57836517947a",
      "name": "CIS RHEL9 - 2025/12/01",
      "type": "benchmark",
      "notBefore": "2025-12-01T11:23:05+01:00",
      "notAfter": "2025-12-01T23:23:05+01:00"
    },
    {
      "schedule": "always",
      "id": "b4f0e7e8-3767-4746-abf7-4a5e21f5dd47",
      "scheduleId": "df6ebe63-a13a-484f-9add-57836517947a",
      "name": "System update debug",
      "notAfter": "2025-12-01T23:23:05+01:00"
    },
    ...
  ]
}
```

The JSON is a map with `events` attribute, containing the list of events. That design was chosen so that if the `schedule` module needs more parameter in the
future, then can just be added with new attributes. 

Where event's attributes are: 
 
- `schedule`: type of schedule for that interval. In the `once` case, the module ensure that the event will be executed at most one time. 
- `id`: the *event* ID. Only used for log and internally by the module. Must be changed on webapp side if anything change with regard to that event. 
- `scheduleId`: the *techniques' schedule* ID. That part is stable for a schedule, and can be used in several events. It is used to derive the run class for valid intervals. 
- `name`: name of the event, for humans
- `type`: what Rudder feature defined that event. Purely informative. 
- `notAfter`/`notBefore`: interval boundaries to now when the schedule is valid for that event. 

### Class name when interval is valid

When an interval is valid for current time, the agent defines a class based on the `scheduleId` which is then used to enable the corresponding directives in the run sequence. 

The name of the defined class when we in an interval is: `schedule_${scheduleId}_run`


## Consequences

Any data defined in that ADR need a coordinated effort between webapp and agent to be updated. 
