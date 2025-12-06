# Scheduler

Scheduler module type. Can be used for scheduling unique or recurring actions.

At takes a list of events:

* id (`[a-zA-Z-_]+`, likely an uuid)
* name (human-readable)
* start datetime (optional, default is ASAP)
* end datetime (optional, default is never)

The base behavior is to run the event asap, only once, between start and end. We might add CRON-like features later.
