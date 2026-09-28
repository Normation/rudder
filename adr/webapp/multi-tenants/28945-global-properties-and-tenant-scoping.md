# Global properties and the tenant boundary

* Status: accepted
* Deciders: FAR
* Date: 2026-09-28

## Context

A global property is the only configuration object that reaches every node without being targeted. Every other tagged object gets its tenant boundary enforced at generation by the node set it resolves to: a rule is clamped to the nodes its `SecurityTag` can see ([`28945-tenant-enforcement-at-policy-generation`](28945-tenant-enforcement-at-policy-generation.md)), a group only contains nodes its own tenants can see, compliance is keyed by node. A parameter has no node set to clamp.

[`29409-scoped-global-parameters`](../29409-scoped-global-parameters.md) fix this lead for a restricted case of producers by adding a `scope`. This can be done the case the producer knows its target (which is the case for security-benchmarks). 

A global property is consumed by two paths that share their source and nothing else:

```
              RoParameterRepository.getAllGlobalParameters      (system context, every global property)
                                    |
          +-------------------------+--------------------------+
          |                                                    |
 NodePropertiesServiceImpl.updateAll                  FetchAllInfoService.fetchAll
          |                                                    |
 MergeNodeProperties.forNode / forGroup              NodeContextBuilder.getNodeContexts
          |                                                    |
 PropertiesRepository (in-memory cache)          +-------------+--------------+
          |                                      |                            |
 inheritance view in the API and the UI   InterpolationContext.parameters   node property defaults
                                                 |                          
                                          NodeConfiguration.parameters       
                                                 |                           
                                         rudder-parameters.json
                                         ${rudder.param.X}
```

The left path holds the merge engine and therefore holds 29409's scope filter. The right path is where the policies are actually written, and it never touches `MergeNodeProperties`: it rebuilds a per-node global property map from the full list. A filter placed in the merge engine alone is invisible to it.

This has a consequence for 29409 that is not about tenants: the right path re-injects every global property, scoped or not, as a node property default (`CompareProperties.updateProperties` with the global properties as base and the resolved hierarchy as override). A name that `dropOutOfScope` removed for a node comes back there.

The right path is deprecated and will be removed, but not in 9.2, so for now, we must live with it. 

## Decision

**A global property reaches a node when both its scope and its tenants admit it.**

```
applicable(param, node) = inScope(param, node) && param.tenants.canSee(node.tenants)
```

**The predicate is applied at the two entry points.** 

The idea is to provide each branch with the already filtered global properties for that node, so that the following transformation agree by construction on what properties they see. 

**A tenant tag governs distribution of the global property only**

Contrary to scope that hide the whole hierarchy of property, the tenant on a global property only hide the global property. A group or a node can (re)define it - without knowing - and the value is then computed without taking into account the global property. 

This ensures that tenants can actually define group and node properties of global properties existing in other tenants. 

There is a limit to that, though: a tenant B can't redefine a global property with the same name as one defined by another tenant because global properties storage identity is their name only. 

The alternative would have been to forbid the whole hierarchy, because it creates inconsistencies explained below.
```
  global property backup_target [zoneA]
  group G [zoneB]  defines backup_target = "b.zoneB"
  node N [zoneB]   member of G

  absent root (chosen)                    domain of definition (rejected)
  --------------------                    -------------------------------
  N.backup_target = "b.zoneB"             N.backup_target = <undefined>
  zoneA value never reaches N             zoneA value never reaches N
  G keeps its own value                   G's value is destroyed by an
                                          object zoneB cannot see or name
```

Just hiding the global property is more sound: 

*Confidentiality.* 
With the rejected option, an existing group/node property in a given tenant would disappear if a global property with the same name is added, revealing its existence. 

*Correctness.* 
The same scenario means that two tenants' using a common name like `backup_target` become related, the value of one changing a value it doesn't know anything about. 
Making properties disappear will also lead to policy generation error.  

**The scope filter can be checked at the same place as the hiding in first case**, so that the whole scoping logic for a global property is defined at just one place. 

## Consequences

* The tenant boundary now holds at generation for every tagged object, including global properties.
* The scoping is also enforced for the deprecated right branch, which is a net win.
* `parameterHash` (`NodeConfigurationCacheRepository`) is computed from `NodeConfiguration.parameters`, which becomes per node. A parameter change now only regenerates the nodes the parameter reaches, instead of the whole fleet.
* The group-level inheritance view is filtered by the **group's** tenants, so a group spanning two tenants displays parameters from both. The node-level resolution is recomputed from scratch per node and is the authoritative one, as 29409 already states for scopes. A consequence to expect in support: with `inheritMode` merge on a JSON property, a multi-tenant group can legitimately display `parameter ⊕ group` while one of its nodes resolves to `group` alone. This can be surprising. 
* There is one `canSee` per node per distinct parameter tag, but not per parameter: parameters are grouped by tag once per generation, and distinct tags number as tenants do while nodes number as the fleet does.
* An untagged node (managed with grant '*') receives only untagged and `open` parameters. This is the same rule that already governs which nodes a tenant rule reaches.
