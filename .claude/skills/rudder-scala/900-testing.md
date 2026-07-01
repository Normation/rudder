# 900 — Testing

Two frameworks coexist; pick by what you're testing.

## specs2 — the established style

Most tests are **specs2** `mutable.Specification`, run under JUnit so they integrate
with Maven/surefire:

```scala
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class BundleOrderTest extends Specification {
  "a BundleOrder" should {
    "sort lexicographically" in {
      BundleOrder.compare(a, b) must beLessThan(0)
    }
  }
}
```

- The `@RunWith(classOf[JUnitRunner])` annotation is required for these to run.
- For JSON assertions, use the project matcher `JsonSpecMatcher` / `beEqualToJson`
  (`rudder-core/.../JsonSpecMatcher.scala`) rather than comparing raw strings. These
  tests **lock the user-facing serialization contract** — assert the exact JSON of REST
  responses and persisted forms so a format change can't slip in unnoticed (see
  [`404`](404-serialization-contracts.md)).

## zio-test — preferred for new pure / ZIO code

For new code that is pure or returns `IOResult`, prefer **zio-test**
`ZIOSpecDefault` — tests are values, effects compose, no `.runNow` needed:

```scala
import zio.test.*

object TechniqueGenerationModeTest extends ZIOSpecDefault {
  def spec = suite("TechniqueGenerationMode")(
    test("round-trips through its codec") {
      val mode = TechniqueGenerationMode.MergeTechniqueDirective
      assertTrue(parse(mode.serialize) == Right(mode))
    },
    test("an effect succeeds") {
      for {
        res <- service.compute(input)        // IOResult[...]
      } yield assertTrue(res.size == 2)
    }
  )
}
```

Guideline: **new pure/effectful logic → zio-test**; extending an existing specs2 suite
→ stay in specs2 to match the file.

## Test doubles

There is **no mocking library** (no mockito/scalamock). A test double is just another
implementation of the trait, passed via the constructor (see
[`102`](102-traits-and-dependency-injection.md)) — e.g. an in-memory repository
implementing the repository trait. This is a direct payoff of programming to traits.

## Running

See [`800`](800-build-and-formatting.md) for the maven invocation (targeted runs need
`-Dsurefire.failIfNoSpecifiedTests=false -Dexec.skip=true`). Always run the relevant
suite and report real output — don't claim green without running it.
