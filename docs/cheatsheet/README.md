# Rudder cheat sheet

A two-page, print-ready quick reference for written in [Typst](https://typst.app).

## Build

```sh
make            # build both PDFs into target/
make screen     # screen version only (with clickable "docs ↗" links)
make print      # print version only (links hidden)
make clean      # remove target/

make watch      # to build continuously
```

The print version is selected with `--input print=true`; everything guarded by
`if not print` (the `docs ↗` buttons) disappears.

## Requirements

- [Typst](https://github.com/typst/typst) (`typst` command).
- Fonts. If they're missing, `typst` warns and falls back to defaults, so the PDF
  still builds but looks off:
  - **Raleway** — titles
  - **Lato** — body text
  - **JetBrains Mono** — code / keys

