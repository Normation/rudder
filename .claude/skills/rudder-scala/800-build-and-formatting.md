# 800 — Build, tooling & formatting

The Scala lives in `webapp/sources` (Maven multi-module, Scala 3, Java 25). This file
captures the gotchas that bite when building/formatting here.

## Building (this devcontainer/sandbox)

- **Maven isn't on PATH.** Install once with `mise install maven`, then run from
  `webapp/sources` as `mise exec maven -- mvn ...`.
- **Skip the frontend** (no node/npm here): add `-Dexec.skip=true` so the
  `build-frontend` exec step doesn't fail.
- **Offline (`-o`) is reliable once deps are cached.** If offline resolution breaks on
  stale metadata, delete `~/.m2/repository/**/_remote.repositories` and
  `*.jar.lastUpdated`, then run **online once** (omit `-o`) to fetch, and go offline.
- **Targeted test run:**
  ```
  mvn -o -pl rudder/rudder-core -am test -Dtest=SomeSpec \
      -Dsurefire.failIfNoSpecifiedTests=false -Dexec.skip=true
  ```
- Building the reactor: prefer
  `-pl rudder/rudder-core,rudder/rudder-rest,rudder/rudder-web -am`
  (the root pulls in `rudder-templates-cli`, which needs `scopt_3`, often uncached).

(See the `build-env-maven` memory for the full detail.)

## Formatting & lint — must pass

- **Spotless** (wrapping **scalafmt**) is bound before compile and is enforced. Fix
  formatting with:
  ```
  mvn -o -pl <module> spotless:apply
  ```
- **`-Werror` is on.** Warnings fail the build. In particular **unused imports are
  fatal** — keep imports tight (which also aligns with our "minimize imports" style,
  see [`001`](001-scala3-idioms.md)).

## Style: what the formatter decides, and what you must write

`webapp/sources/.scalafmt.conf` is the source of truth and `spotless:apply` is how you
comply — never hand-format, never argue with the output. But the formatter only fixes
*half* of the style. Knowing which half is which is what stops a build cycle being spent
on syntax.

### Braces, always — significant indentation does not build here

scalafmt parses with `runner.dialect = scala213Source3`: Scala 3 *features* are enabled
one by one (`given`/`using`, `extension`, `opaque type`, `derives`, trait parameters…),
but the optional-brace **syntax** is not. A braceless block fails the build at the
*formatting* step, with an error that doesn't look like a syntax policy:

```
[ERROR] Step 'scalafmt' found problem in '.../Foo.scala':
<input>:3: error: [dialect scala213source3 [with overrides]] ; expected but : found
```

So `object Foo { … }` and `def f(x: Int): Int = { … }` — never `object Foo:`,
`if x then`, or `end Foo`. Same cause if some newer Scala 3 syntax is rejected while the
compiler accepts it: scalafmt's dialect lags, and the dialect wins.

### What spotless fixes — don't spend a turn on it

Alignment (of `:`, `=`, `<-`), `import x._` → `import x.*`, import expansion and
sorting, modifier order (`private final` → `final private`), `for (a; b)` → `for { … }`,
unicode arrows (`⇒` → `=>`), braces around a one-line lambda → parens.

Write it plainly and run `mvn -o -pl <module> spotless:apply`. Don't hand-align columns,
don't sort imports by hand, don't reorder modifiers, and don't "fix" what it produced.

### What it does *not* fix — write it right the first time

- **Always `${value}` in interpolation**, not `$value`. scalafmt leaves both alone, so
  this one is on you; the codebase is ~97% braced. It reads consistently and it doesn't
  break the moment the expression grows a `.field`.
- **Multi-line lambda: `foo.map { x =>`**, not `foo.map(x => {`. Both survive the
  formatter; the codebase prefers the first ~7:1.
- **`maxColumn = 130`** — it will not break a long string literal or a comment for you.
- **Comments are yours** (`docstrings.style = keep`): scalafmt never reflows them. House
  style is in
  [principle 10](../rudder-principles/SKILL.md#10-comments-explain-why-not-how).

## License header on every file

Every new `.scala` file starts with a copyright header. **The header must match the
repository's root `LICENSE` file and be consistent within that repo.**

- For all `rudder` projects the rule is the **GPLv3 "with the Additional permissions"
  (Related Module) exception** — the long block beginning:
  ```
  /*
   *************************************************************************************
   * Copyright <YEAR> Normation SAS
   *************************************************************************************
   *
   * This file is part of Rudder.
   *
   * Rudder is free software: you can redistribute it and/or modify
   * it under the terms of the GNU General Public License ... version 3 ...
   *
   * In accordance with the terms of section 7 ... the following Additional
   * permissions: ... "Related Module" ...
   ...
   */
  ```
  Copy it verbatim from a neighbouring file and set the current year.
- Some **dependency-style modules can be ASL2** (Apache-2.0) instead — e.g. `utils`
  (see `ZioCommons.scala`). Use Apache **only** where the surrounding module already
  does and its `LICENSE` says so.
- Rule of thumb: **match the neighbouring files / the repo's root `LICENSE`.** Don't mix
  licenses within a repo, and don't invent a header — when unsure, ask.

## Before you call a change done

1. Compiles with `-Werror` (no unused imports / warnings).
2. `spotless:apply` run so formatting is clean.
3. Relevant tests run and pass (report real output — don't claim green if you didn't run
   them).
