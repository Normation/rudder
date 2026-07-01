# 404 — Serialization contracts & DTOs

Anything we serialize **for someone outside the code** — REST API responses/requests,
persisted files, DB JSON, anything a user's scripts, monitoring, or another system reads
— is a **contract**. The internal business model is not.

These two have opposite lifecycles, and keeping them separate is the point:

| | Business model | Serialized contract |
|---|---|---|
| Who depends on it | our code | users, ops, other systems, stored data |
| Change policy | **change freely & often** to model the domain accurately/efficiently | **never change unintentionally**; backward-compatible, bridged if needed |
| Lifecycle | fast, dev-driven | slow, spans several minor versions |

This is the macro "pure core / stable adapter" boundary (see
[`101`](101-architecture-ddd-hexagonal.md) and the DevoxxFR error-management talk
referenced in [`SKILL.md`](SKILL.md)): the core sub-system iterates rapidly; the API
sub-system keeps promises. The bigger the promise, the stricter the contract.

## Keep it simple first, split when it pays (the rule)

Don't build a translation layer before you need it. The progression:

1. **No DTO at first.** If the serialized shape happens to match the business object
   exactly, serialize the business object directly (codec in its companion,
   [`401`](401-json-zio-json.md)). Don't add a parallel type "just in case".
2. **But pin the output in tests immediately.** Even with no DTO, the user-facing
   serialization is a contract: assert the exact JSON in REST API tests (and
   file/persistence tests) with the JSON matcher (`beEqualToJson`, see
   [`900`](900-testing.md)). These tests are the **guardrail** that makes an accidental
   format change fail loudly.
3. **Introduce a DTO when the business model needs to evolve** (which happens often).
   Add a dedicated serialization type — `XxxDto`/`JsonXxx`/`RestXxx` — and map
   **to/from** the business object with **chimney** ([`402`](402-chimney-transformers.md)).
   The business object is now free to change; the DTO absorbs it.
4. **Then evolve the contract on its own path.** Changes to the DTO are deliberate and
   backward-compatible: add optional fields, keep old ones, bridge/migrate, version at
   the REST layer (`StartsAtVersion*`, see [`103`](103-rest-api-and-endpoints.md)).
   A breaking change is a versioned, intentional act — never a side effect of a core
   refactor.

So the trigger to add a DTO is **"the business model wants to move but the wire form
must not"** — not dogma. Until then, the directly-serialized object plus its locked-down
test is the simpler, preferred design.

## Stable serialized tokens

Within either form, individual serialized tokens are part of the contract too — choose
them explicitly so refactors can't shift them:

- **Enum `entryName`** — set it explicitly so renaming a `case object` doesn't change
  the wire value (see [`401`](401-json-zio-json.md#enums)).
- **Field names / discriminators** — when the JSON field must differ from the Scala
  name, pin it (custom codec / `@jsonDiscriminator`), don't let it ride on the
  identifier.
- Accept old input spellings (e.g. enumeratum `extraNamesToValuesMap`) when you need to
  broaden inputs without breaking the canonical output.

## Checklist

- Is this data read by anyone outside our code (API, file, DB JSON)? → it's a contract.
- Is its exact serialized form asserted in a test? → if not, add it.
- Am I about to change a business object that is serialized directly? → if the wire form
  must stay, introduce a DTO + chimney mapping first, then change the core.
- Am I changing a DTO/contract on purpose? → is it backward-compatible / versioned?
