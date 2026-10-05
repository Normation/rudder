# The reference technique library and tenants

* Status: accepted
* Deciders: FAR
* Date: 2026-09-28

## Context

Rudder has two views of the technique libraries:
- the **reference** library is the file system: `techniques/<category>/<id>/<version>/`, read through the cfclerk `TechniqueRepository` and written by the `ncf` writers. 
- the **active** library is what LDAP holds: active techniques and their categories, with the directives attached to them.

Both must be aligned with regard to tenants. It was already done for the LDAP part with the filtering proxy pattern, and we need to do the same for the FS part (create, update, move, delete,...).

The tag itself is already there:
- a technique declares it in `metadata.xml` (written by rudderc from `technique.yml`), 
- a category in its `category.xml`, 
- and `TechniqueLibraryTenantSync` copies those declarations onto the active library. 

## Decision

- the reference library follow the same proxy pattern than LDAP repositories.
- writing a technique is a save
- as for other object, security tag absent means administrator only, else we use whatever the security tag say.
- reads follow the tag where the reference library is reached directly.
- technique ids and names are one global namespace, and a collision discloses that the id exists.
- an unknown technique and an invisible one give the same answer.

## Consequences

* the main one is that tenant are now working down to the FS. 
* Plugins that ship their own technique categories must declare `open-ro` for technique and categories, else they won't be usable by tenant user. It's the case of `openscap` and security benchmarks.
* The technique editor's own category (`ncf_techniques`) is `open-ro`, which is what makes it a place every tenant may create under while the categories they create there carry their own tenants.
