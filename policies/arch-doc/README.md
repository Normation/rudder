# Rudder agent & policies architecture documents

This folder contains the documentation for Rudder
agent and policies related components.

## Tooling

The sources are in Markdown, with [small extensions available in *pandoc*](https://pandoc.org/MANUAL.html#pandocs-markdown).
The provided `Makefile` allows compiling to HTML and PDF (with *typst*). See the included comments for
usage. There are targets to optimize images, check for typos, etc.

### Rationale

The user documentation is written in *AsciiDoc*, which is fine but not currently parseable by pandoc.
Using a format readable by pandoc gives more options
and flexibility.
In particular, *Asciidoctor*'s PDF output is not the best, and using *pandoc*
gives access to *LaTeX* or *Typst*, which are way better.

On the opposite, *pandoc* can easily translate Markdown into AsciiDoc
for inclusion in the user documentation if needed.

For the choice of Mermaid, is is based both on the
fact it is rendered by GitHub, allowing to consult the sources
directly in place, and the general quality of output,
which seems to make it the best currently available option.

The bibliography is stored as a *BibLaTeX* file
making it convenient to use with tools like Zotero,
or write manually.

### Dependencies

Except for C4 diagrams, everything is rendered automatically by GitHub,
so it is possible to work without any specific dependency.

* `pandoc` ([install docs](https://pandoc.org/)): optional, for HTML and PDF output
  * Should be in your distribution's repos, if not follow the docs.
* `typst` ([install docs](https://github.com/typst/typst?tab=readme-ov-file#installation)): optional, for PDF output
  * May be in your distribution's repos, if not follow the docs.
* `typos` cli ([install docs](https://github.com/crate-ci/typos?tab=readme-ov-file#install): optional, for typo check
  * Additionally, it is strongly advised to use an editor supporting grammatical spell check.
* `structurizr` ([install docs](https://github.com/structurizr/cli))): optional, for C4 diagrams
  * Translates the C4 DSL into Mermaid.
  * A standalone `.jar` file in GitHub releases.
* Mermaid's `mmdc` ([install docs](https://github.com/mermaid-js/mermaid-cli?tab=readme-ov-file#installation)): optional, for Mermaid-based diagrams
  * Installed with `npm`.
* `svgo` ([install docs](https://svgo.dev/docs/introduction/)): optional, for SVG optimization
  * Installed with `npm`.
* `zopflipng`: optional, for image optimization
  * Should be in your distribution's repos.
* `qpdf`: optional, for PDF compression
  * Should be in your distribution's repos.