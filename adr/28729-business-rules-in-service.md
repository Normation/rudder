# Business rules implemented in service

* Deciders: PIO, CAN, FAR, VME, VHA
* Date: 2026-04-16

## Context

Business rules need to be implemented in one place, no duplication code.
Currently some business rules can be found in different layers of the webapp: the api layer, the repository layer.
It makes the maintenance and the debugging difficult. 

## Decision

Every business rule should be implemented in a service backend side :
- business rules are in a service class
- business rules are not duplicated
- rudder repository: service classes are in rudder-core module
- rudder plugins: service classes are in a dedicated package
- the service class extends a trait exposing the service methods
- dedicated unit tests for business rules
- no business rules in
  - ELM code
  - REST API layer
  - Repositories

## Consequences

New code designed as describes. 
When a refactoring is performed, this decision should be taken in consideration.
