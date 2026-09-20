# 400 — Build, format & review workflow

Toolchain: **Elm 0.19.1**, `elm-format` 0.8.x, `elm-review` 2.13.x, **gulp** 5 — this file is
the *only* place versions are pinned in this skill, because the skill is versioned along the
Rudder branch and the exact toolchain of a branch is worth knowing. Everywhere else, just "Elm".
The authoritative versions are the module's `package.json`; check it if something disagrees.

The `package.json` that carries the scripts is the module's frontend one:

- rudder webapp: `webapp/sources/rudder/rudder-web/src/main/package.json`
- a plugin: `<plugin>/src/main/package.json`

Run the npm scripts from that directory (the `elm-format` scripts target `elm/sources`).

## Format — required before every commit

```bash
npm run elm-format-all      # elm-format elm/sources --yes   (rewrites in place)
npm run elm-format-check    # elm-format elm/sources --validate  (CI gate; no changes)
```

Never hand-format. "This will need … elm-format 🙂" in review = `elm-format-all` wasn't run.

## Review

```bash
npx elm-review               # run from src/main/elm
```

**The source of truth for which rules are enabled is `elm/review/src/ReviewConfig.elm`** — read
it rather than trusting a list here, since rules get enabled and commented out over time. Don't
rely on a rule being active without checking, and don't introduce code that only passes because
a rule happens to be off today.

## Type-check a single app fast

`elm make` type-checks without the whole gulp pipeline:

```bash
elm make sources/BenchmarkReports.elm --output=/dev/null   # run from src/main/elm
```

(The `elm` binary is under the module's `node_modules/@elm_binaries/<platform>/elm`, or install
elm 0.19.1.) A green `elm make` is the quick inner loop; still run `elm-format-all` + `elm-review`
before pushing.

## How apps are compiled & served (gulp)

**Read the gulpfile** — `gulpfile.mjs` next to the module's `package.json` (for a plugin,
`plugins-common/gulpfile.mjs`, which `src/main/build.sh` copies into place). It is short, it is
the source of truth, and describing its steps here would only go stale.

The one contract worth stating, because it is what you rely on when adding a screen: an Elm
module that is an **entry point** (it declares a `Browser.element`) is picked up automatically
and compiled to its own bundle. Adding a screen needs no build-config edit — confirm the naming
and output directory in the gulpfile.

`node`/`npm` aren't always available in a sandbox; in that case type-check with `elm make` and
let CI run the full gulp build.
