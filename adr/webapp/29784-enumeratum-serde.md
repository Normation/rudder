# Enumeratum usage for serialization and deserialization

* Status: accepted
* Deciders: CAN, FAR
* Date: 2026-09-22

## Context

The [_enumeratum_](https://github.com/lloydmeta/enumeratum) library is a Scala 3 library for enumerations,
with many library integrations that are useful in Rudder codebase (_doobie_, _cats_, our own _zio-json_ integration, ...).

Enumerations can be:
* **plain domain enumeration**: union of subtypes (plain old `sealed trait + case object`),
  used to pattern match and do some logic
* **serialization model (DTO)**: also a union but with additional way to serialize or deserialize the values,
  in a REST payload, in a database column, ...

The second kind is the one where we see the complete benefits of using _enumeratum_.
It offers many functionalities based on a public member `enumEntry: String`:
* string manipulation via `EnumEntry` mixins (`Lowercase`, `LowerCamelcase`, `Hyphencase`, ...),
  deriving the `entryName` automatically
* helpers methods for parsing: `withNameInsensitiveOption`, `withNameEither`, ... that can rely on the `entryName`
* it also has a macro can be used to obtain the list of enum subtypes: `findValues` can be used for error handling
  to get messages with all known values, with their `entryName`: `"valid enums are: a, b, c"`

But all of this comes at the cost of making discoverability and searching unintuitive:
* it cannot always be grepped easily (e.g. *kebab-case* and *snake_case*), which makes it harder to audit from the source
* `entryName` does not reveal the intent of serialization or deserialization,
  we need to rely on user-defined methods, which is not systematic, or on code comments

Our historical usage before adopting the enumeratum library was already based on explicit declaration of serialized values:
* enumeratum has been increasingly used as replacement of the scala 2 library [sealerate](https://github.com/mrvisser/sealerate) 
  which did not have the `entryName` API, and only provided the macro for the list of subtypes, see [PR #5499](https://github.com/Normation/rudder/pull/5499).
* parsing mostly happens to always be case-insensitive, so our previous API needed a dedicated `parse`
  with boilerplate for string manipulation

_enumeratum_ seems to be a battle-proof Scala 3 library and from the widespread adoption in our codebase,
we want to clarify the use cases and the convention of the API that we should rely on:


## Decision

1. _enumeratum_ should not be used if there is no deserialization on the object at all
  (i.e. if object is not a DTO), prefer plain language enum instead (e.g. scala 3 enum)

2. An enumeration that is serialized or deserialized (DTO for JSON/YAML/XML or external representation)
  should always have the **value written explicitly as a String**

3. Intent of serialization and deserialization should not rely on the `enumEntry` member but rather on **explicit members**:
  `serialize`/`parsedValue`

4. _enumeratum_ integrations should be subject to the same constraint (they mostly provide integration
  with a mixin on the companion object: `extends Enum[RestRollbackType] with CatsEnum[RestRollbackType]`)

To illustrate a typical exemple of serialization, simply define the override of `entryName` the in the base trait,
and provide the serialized value:

```scala
// override enumEntry (2)
sealed trait RestRollbackType(override val entryName: String) extends EnumEntry {
  // intent of serialization (3)
  def serialize: String = entryName
}
// zio-json integration (4)
object RestRollbackType extends Enum[RestRollbackType] extends EnumCodec[RestRollbackType] {
  case object Item             extends RestRollbackType("item")
  case object AllConfiguration extends RestRollbackType("allConfiguration")

  def values: Seq[RestRollbackType] = findValues
}
```

This implies that enumeratum's parsing with `RestRollbackType.withNameX`
will also rely on the passed values.


## Consequences

* `enumEntry` would not belong to public API of our enum type, but is exposed anyway:
  it should simply be ignored
* New enumerations should have an indication that if it is using _enumeratum_,
  then it is used for serialization/deserialization, and it clearly follows the proposed convention
* The existing enums that do not follow the convention (e.g. that still use a mixin for serialization)
  should be migrated when we have the opportunity, and we should cover test cases for them

And:
* We will be happy to search with grep and find results
* We have no excuse to not use `values` to provide a helpful messages about the set of known values

