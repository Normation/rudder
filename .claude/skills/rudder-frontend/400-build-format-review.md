# 400 — Build, format & review workflow

Toolchain: **Elm 0.19.1**, `elm-format` 0.8.x, `elm-review` 2.13.x, **gulp** 5. The `package.json`
that carries the scripts is the module's frontend one:

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

Enabled rules live in `elm/review/src/ReviewConfig.elm`. Currently active (others are commented
out, so don't rely on them): `Docs.ReviewAtDocs`, `NoDebug.Log`, `NoDebug.TodoOrToString`,
`NoMissingTypeExpose`, `NoUnused.Dependencies`. Practically: **no `Debug.*` in committed code,
no unused elm deps, keep exposed types' API coherent.**

## Type-check a single app fast

`elm make` type-checks without the whole gulp pipeline:

```bash
elm make sources/BenchmarkReports.elm --output=/dev/null   # run from src/main/elm
```

(The `elm` binary is under the module's `node_modules/@elm_binaries/<platform>/elm`, or install
elm 0.19.1.) A green `elm make` is the quick inner loop; still run `elm-format-all` + `elm-review`
before pushing.

## How apps are compiled & served (gulp)

The gulp `elm` task:
1. reads `elm/sources/*.elm`, **keeps only entry points** (greps for `Browser.element`),
2. compiles each and renames to `rudder-<basename>.js` (lowercased), minifying in `--production`.

- **rudder webapp:** `gulp` (via the maven frontend build) outputs into `webapp/javascript/rudder/elm`.
- **plugins:** `src/main/build.sh` copies `plugins-common/gulpfile.mjs` next to the sources, runs
  `npm ci` then gulp; output lands in `src/main/elm/generated/`, and the plugin POM's
  `copy-elm-toserve` copies `*.js`/`*.css` to `target/classes/toserve/${destDirectory}` — served
  at `/toserve/<destDirectory>/`. **SCSS** in `src/main/style/*.scss` is compiled by the same
  gulp run to that `toserve` dir.

So: adding a `Browser.element` module is enough to get a new `rudder-<name>.js` — no build-config
edit. `node`/`npm` aren't always available in a sandbox; in that case type-check with `elm make`
and let CI run the full gulp build.
