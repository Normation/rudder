# 601 — Web & output security (XML, XSS, CSRF, CSP)

Rules for handling untrusted input/output safely. The recurring theme: data from nodes
(inventories, reports), from users, and from URLs is **untrusted** and has historically
been the source of real vulnerabilities — parse it safely, escape it on output.

## Parse XML only with `XmlSafe`

**Never** parse XML with the default `scala.xml.XML` loader (or a raw
`SAXParser`/`DocumentBuilder`). Use **`com.normation.utils.XmlSafe`**
(`utils/.../XmlSafe.scala`), which builds a SAX parser hardened against **XXE / external
entity** attacks (`FEATURE_SECURE_PROCESSING`, `disallow-doctype-decl`, no XInclude).

```scala
import com.normation.utils.XmlSafe

val elem = XmlSafe.load(inputStream)   // also loadFile(file), loadString(s), load(source)
```

- `XmlSafe` is a drop-in `scala.xml.factory.XMLLoader[Elem]`, so it has the same
  `load*` methods as `scala.xml.XML` — just swap the object.
- This matters most for anything coming from outside: inventories, reports, technique
  files, API payloads, configuration-repository files. When in doubt, it's untrusted.
- It is already used across the XML parsing code (techniques, doobie XML columns, git
  archives) — match that.

## Don't emit raw JavaScript; if you must, justify and escape it

We avoid generating JavaScript from the Lift backend, preferring safe, escaped
abstractions. Raw JS through Lift's **`JsRaw`** is a **DOM-XSS hazard** whenever the
string embeds untrusted data (this has caused real CVEs, e.g. GHSA-fc5g-p464-qpp9).

Every `JsRaw` call has been audited and annotated; keep that discipline. When `JsRaw` is
unavoidable, you **must justify why it is safe** with an inline comment, and escape any
interpolated value:

```scala
val ruleId = StringEscapeUtils.escapeEcmaScript(rule.id.serialize)
JsRaw(s"""sessionStorage.removeItem('tags-${ruleId}');""") // JsRaw ok, ruleId escaped
```

The accepted justifications (state which one applies):

- `// JsRaw ok, const` — the string is a compile-time constant with no interpolation;
- `// JsRaw ok, escaped` — every interpolated value is escaped (`escapeEcmaScript`, etc.);
- the value provably comes from a known-safe source (and say which).

If you can't write one of those truthfully, don't use `JsRaw`. Prefer Lift's typed
`JsCmd`/`JE` builders or render through Elm. Lift pages are being replaced page by page,
so don't add new `JsRaw`.

## Escape untrusted data before it hits the DOM

Beyond `JsRaw`, any untrusted value rendered into HTML/attributes must be escaped
(server side, or in Elm via an `htmlEscape` helper). Vanilla JS that reads HTML
attributes (e.g. bootstrap tooltips with `data-html="true"`) bypasses Elm's built-in
escaping, so the value must be escaped *before* it is put in the attribute.

## CSRF, CSP, sessions (be aware, don't regress)

- **CSRF.** Internal API calls (`/rudder/secure/api/...`) require the custom header
  `X-Requested-With: XMLHttpRequest` (built into jQuery and our Elm HTTP clients) — keep
  sending it. Lift forms carry their own CSRF tokens; the session cookie is
  `SameSite=Lax`. Don't disable these mitigations.
- **CSP.** Content-Security-Policy is mandatory, strict (nonce-based).
  Don't introduce inline scripts/styles that force loosening it; on new pages,
  work within the nonce-based strict policy.
- **Sessions.** Session cookies are `Secure; HttpOnly; SameSite=Lax` — don't weaken
  these flags.

See [`600`](600-security-in-depth.md) for the security-in-depth principles and
[`602`](602-authentication-and-authorization.md) for auth.
