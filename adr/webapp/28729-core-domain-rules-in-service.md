# Core domain rules implemented in service

* Deciders: PIO, CAN, FAR, VME, VHA
* Date: 2026-04-16

## Context

#### What is a core domain rule ?
In this ADR let's consider a core domain rule as a rule related to audit and/or patch
- system configurations
- system security management 
on environments, workstations, servers.

Ex:
- define a group of OS: debian family, redhat family, windows to apply dedicated rules
- create a set of rules using directives and techniques to apply to a group of nodes
- show the change logs created on user actions only (hide technical change logs)
- install a package `npm` on group of machine
- audit an apache configuration file on linux nodes
- system updates with excluded packages

> Note: other domains could be defined and hold their own business rules.

#### Issues

Currently some core domain rules can be found in different layers of the webapp: the api layer, the repository layer which makes the maintenance and the debugging difficult.

## Decision

Every core domain rule need to be implemented in a service backend side :
- core domain rules are in a service class
- core domain rules are not duplicated
- rudder repository: service classes are in `rudder-core` module
- rudder plugins: service classes are in a dedicated package
- the service class extends a trait exposing the service methods
- dedicated unit tests for core domain rules

No core domain rules in :
  - ELM code
  - REST API layer
  - Repositories

## Consequences

- New code designed as described
- When a refactoring is performed, this decision should be taken in consideration
- When some logic is adde d to some layer, we should check if it's the right place to implement it
