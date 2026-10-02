# Enumeration modeling in Rudder Scala 3 code base

* Status: accepted
* Deciders: CAN, FAR
* Date: 2026-09-22

## Context

Enumerations can be of two kinds, and this ADR addresses both:

1/ **plain domain enumeration**: union of subtypes used to pattern match and do some logic,

2/ **serialization model (DTO)**: also a union, but with an additional way to serialize or deserialize
  the values, in a REST payload, in a database column, ...


The first kind have a new language feature since our Scala 3 update: the [Scala 3 enumeration](https://docs.scala-lang.org/scala3/reference/enums/enums.html).
It is notably similar and more concise than _`sealed trait` + `case object/class` in Scala 2_, but different from plain Java enum that are quite limited (which it offers integration with).
It can be used to represent:
* simple cases of enumeration *values* that each directly map to a `String`
* more complex data type hierarchies (with cases classes), for which complex serialization can apply (with typeclass instances)



The second kind is the one where we see the complete benefits of using the [_enumeratum_](https://github.com/lloydmeta/enumeratum) library,
a Scala 2 and Scala 3 library for enumerations, with many library integrations that are useful in Rudder codebase (_doobie_, _cats_, our own _zio-json_ integration, ...).

It is mainly useful to represent values that map to `String` for serialization.
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
we want to clarify the use cases and the convention of the API that we should rely on.


## Decision

### 1/ Plain domain enumeration: the Scala 3 `enum`

* the native **Scala 3 `enum` is our standard** for that kind of type
* `sealed trait` + `case object`/`case class` remain the **fallback** (e.g. when extending an existing hierarchy,
  when a type parameter is used differently by each case, ...)

When enumeration has to cross the boundary of the domain, for examples when a typeclass is needed to model serialization,
we can:
* keep the `enum` for complex data types that do not directly map to a `String` (e.g. for JSON objects that needs `derives` syntax)
* chose to define another dedicated _enumeratum_ data type otherwise, see 2/

Therefore _enumeratum_ should not be used if there is no serialization nor deserialization on the object at all
(i.e. if the object is not a DTO): the language already gives us what we need.


### 2/ Serialization model (DTO): _enumeratum_ with explicit values

* **2.1** An enumeration that is serialized or deserialized (DTO for JSON/YAML/XML or external representation)
  should always have the **value written explicitly as a String**

* **2.2** Intent of serialization and deserialization should not rely on the `enumEntry` member but rather on **explicit members**:
  `serialize`/`parsedValue`

* **2.3** _enumeratum_ integrations should be subject to the same constraint (they mostly provide integration
  with a mixin on the companion object: `extends Enum[RestRollbackType] with CatsEnum[RestRollbackType]`)

To illustrate a typical exemple of serialization, simply define the override of `entryName` the in the base trait,
and provide the serialized value:

```scala
// override enumEntry (2.1)
sealed trait RestRollbackType(override val entryName: String) extends EnumEntry {
  // intent of serialization (2.2)
  def serialize: String = entryName
}
// zio-json integration (2.3)
object RestRollbackType extends Enum[RestRollbackType] extends EnumCodec[RestRollbackType] {
  case object Item             extends RestRollbackType("item")
  case object AllConfiguration extends RestRollbackType("allConfiguration")

  def values: Seq[RestRollbackType] = findValues
}
```

This implies that enumeratum's parsing with `RestRollbackType.withNameX`
will also rely on the passed values.


## Consequences

* New modelling should use Scala 3 `enum` for closed hierachies
* Current _`sealed trait` + `case object/class` hierarchies are kept as is, but can be migrated to Scala 3 `enum` opportunistically
* Seeing `EnumEntry` in a type becomes the signal that it is serialized somewhere
* Caveat of using _enumeratum_: `enumEntry` would not belong to public API of our enum type, but is exposed anyway:
  it should simply be ignored
* New enumerations should have an indication that if it is using _enumeratum_,
  then it is used for serialization/deserialization, and it clearly follows the proposed convention
* The existing enums that do not follow the convention (e.g. that still use a mixin for serialization)
  should be migrated when we have the opportunity, and we should cover test cases for them

And:
* We will be happy use Scala 3 features that come along with `enum`
* We will be happy to search with grep and find results
* We have no excuse to not use `values` to provide a helpful messages about the set of known values

