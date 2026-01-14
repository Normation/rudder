# The main JSON library should be zio-json instead of lift-json

* Status: accepted
* Deciders: CAN, PIO, FAR, VHA, VME
* Date: 2021-02-03


## Context

The lift-json library is no longer maintained.
We are facing other challenges with lift-json due to its slow performance, which is affecting critical and expensive parts of the webapp, such as generating and parsing expected reports, compliance, serialization/deserialization from postgres data, ...
Additionally, it also bloats our final war build with ~10 MB of unused dependencies.

So, we want to use a modern JSON library, from the plethora of existing ones from the community. Among them we have the choice of zio-json which has good benchmarks for runtime and compilation, is ligthweight, and it has a friendly and quite active community.

## Decision

Use zio-json in place of all usage of lift-json, and remove the lift-json dependency completely from the webapp.

## Consequences

The way lift-json is used with its custom DSL is quite divergent from zio-json : we will need to migrate to zio-json encoder and decoder type-class instances, create case classes and datastructures that map to the DSL fields.
In some cases we would need to use the JSON AST API in zio-json, which is similar to the one of lift-json.
