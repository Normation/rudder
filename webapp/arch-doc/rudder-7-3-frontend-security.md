# Rudder 7 - Web frontend security

*Notice*: This document was last reviewed and updated in 2023/03 (for Rudder 7.3.0). It may now be outdated.

## Resources

* [Security & hardening guide for Rudder](https://docs.rudder.io/reference/7.3/administration/security.html), including items related to this document.
* [Talk at CfgMgmtCamp 2023](https://speakerdeck.com/rudder/how-do-we-make-rudder-secure)

## Context

Frontend security is critical for Rudder, as the items managed in the Web interface are administrative access
to systems. After discovering several vulnerabilities, we conducted wider changes to 
improve Rudder frontend security.

---

## Dependency management

Our general dependency update process (across the whole Rudder stack) is to 
update all dependencies for every minor release (approximately twice a year),
to stay on maintained versions and facilitate emergency upgrades when detecting a vulnerability.
Additionally, we run daily vulnerability checks everywhere, and evaluate/fix them
as they are reported.
While it is well followed for backend and system components (Scala, Rust, F#, etc.), this process was not totally in place for frontend dependencies.

### JS/CSS

Prior to 7.3, Javascript and CSS dependencies were vendored in our repositories. This made upgrades
and vulnerabilities tracking hard, and lead to some dependencies lacking regular upgrades.

#### npm

Starting from 7.3, we removed dependencies from the repository and now use npm to download all dependencies at build time.
The build process itself is [implemented using gulp](https://github.com/Normation/rudder/blob/b8ee9ae5255d9d17e0a4f21a17d6b4ac7c8cd8c5/webapp/sources/rudder/rudder-web/src/main/gulpfile.js), doing mainly file copies for now, except for Elm applications where it also handles compilation and minification.

A point of attention is the method used to download the dependencies. In build context we never want to use `npm install` as it can modify the `packages-lock.json`,
hence producing unpredictable results. In order to enforce reproducible builds
we use the [`npm ci`](https://docs.npmjs.com/cli/v9/commands/npm-ci) command instead.

We also now use `npm` to install all Elm-related tooling,
making the web build process only relying on the presence of `maven`
and `npm` on the system, and forcing the exact version of all tooling and dependencies used for the build.

We audit the dependencies for known vulnerabilities using [`better-npm-audit`](https://www.npmjs.com/package/better-npm-audit), which add the ability to ignore specific vulnerabilities and set a severity level option in checks,
compared to the built-in tooling.

### Elm

Elm has its own dependency repository and package manager, that we already used
for all our applications.
Though is [does not have](https://discourse.elm-lang.org/t/security-resources-for-elm/8686/3) vulnerability detecting tooling,
it is [safer than Javascript by design](https://elm-radio.com/episode/security/), as dependencies [can't generally inject](https://github.com/elm/html/issues/172) arbitrary code in pages.
However, we already needed to upgrade the `virtual-dom` package
due to [various vulnerabilities](https://jfmengels.net/virtual-dom-security-patch/), so we need to keep
monitoring them.

## CSRF prevention

### Session cookie protection

Rudder's session cookie is `JSESSIONID` (managed by our authentication stack Jetty/Spring Security).
We need to make it as safe as possible, and prevent common attacks.
We already had `Secure` (to only transmit it over HTTPS) and `HttpOnly` (to prevent access to the cookie's value front Javascript code) flags on the cookie, but no `SameSite` value.

This `SameSite` cookie attribute, allowing to control whether to send the cookie in cross-domain requests, has three possible values:

* `None`: send the cookie for all requests
* `Lax`: only send the cookie in navigation to cross-domain links
* `Strict`: never send the cookie in cross-domain requests

When no value is provided, the behavior depends on the browser:

* In Chrome, it is `Lax` by default since 2020
  * **But** for two minutes after authentication, the cookie is still sent in `POST` cross-domain requests to prevent breaking to many sites.
* In other browser, it's `None`.

We need to prevent cross-site non-idempotent requests (a.k.a. non-GET/HEAD) while allowing direct links to 
specific pages inside Rudder (which would be prevented by a `Strict` value). We hence use the `Lax` value.

## XSS prevention

Resources:

* [Cross Site Scripting Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cross_Site_Scripting_Prevention_Cheat_Sheet.html)
* [DOM based XSS Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/DOM_based_XSS_Prevention_Cheat_Sheet.html)

We need different solutions depending on the context:

* Control code execution with `Content-Security-Policy` headers
* Escaping
  * Where we don't need to interpret the content
  * Be careful, it is context dependant:
    * HTML body: `<`, `>`, `'`, `"`, `&`
    * HTML attribute: non-alphanumeric characters encoded in the `&#xHH;` form
    * URL: `%` encoding
* Sanitization
  * When we need to interpret arbitrary HTML
  * Use reliable dedicated libraries
    * We already use [xss](https://github.com/leizongmin/js-xss) so let's keep it
    * [DOMPurify](https://github.com/cure53/DOMPurify) would be another valid option

In pure JS we need to keep track of user-defined content and not forget to add protections.

### Elm

Elm provides built-in XSS protection with automated sanitization of all application content (replaces `<script>` by `<p>`, onclick by data-onclick, etc.). 
Rendering an arbitrary HTML content actually requires special effort (like parsing it with `html-parser` as an `Html` object on client-side, and rendering it through the sanitizer).
It also strictly restrict interoperability with Javascript (only possible through ports ans WebComponents),
which protects from Javascript-specific vulnerabilities.

Despite these protections, we encountered XSS vulnerabilities in some of our Elm applications, due to interaction of Javascript scripts with the applications.
For example, some tooltips are produced by rendering HTML from a `"data-original-title"` attribute.

```javascript
$('[data-toggle="tooltip"]').bsTooltip();
```

As the content of the attribute is built as a `String` in Elm, it is not sanitized
and hence vulnerable if the tooltip contains user-provided content.
We fixed these cases by escaping the user-provided content (which did not require rendering itself):

```elm
buildTooltipContent : Tag -> String
buildTooltipContent tag =
  "<h4>Tags</h4><div>" ++ htmlEscape tag.value ++ "</div>"

htmlEscape : String -> String
htmlEscape s =
  String.replace "&" "&amp;" s
    |> String.replace ">" "&gt;"
    [...]
```

### Headers

#### `Content-Security-Policy`

The `Content-Security-Policy` header allows controlling the download
and execution of resources, including scripts.
This is the safest option as it works by allowing
specific script execution instead of manually disallowing
it everywhere it could happen.

For now, we can't use it to aggressively restrict script execution,
as Rudder relies on a lot of different scripts hard to track (especially in Lift-based pages).
However, we can already restrict external resource inclusion.

We also enable to report feature to be able to log all violations on the server,
and help Rudder server admins detect false positives.
The webapp logs look like:

```bash
WARN  application - Content security policy violation: blocked inline in https://127.0.0.1:8181/rudder/ because of script-src-elem directive
```

We end up with a header containing:

```text
Content-Security-Policy
	default-src 'self';
	img-src 'self' data:;
	script-src 'self' 'unsafe-inline' 'unsafe-eval';
	style-src 'self' 'unsafe-inline';
	report-uri /rudder/lift/content-security-policy-report
```

#### `X-Frame-Options`

The `X-Frame-Options` header allows controlling the ability to embed a page inside another (through `iframes`).
It has been superseded by `Content-Security-Policy` but out Web server (Lift) does not implement
this extension, so we still rely on `X-Frame-Options` for now.

As we need to embed HTML files in Rudder (e.g. OpenSCAP reports) we can't use the `DENY` value,
but we also want to limit the ability to include any external page.
As a consequence, we use the `SAMEORIGIN` value which prevent inclusion from external domains.

#### `X-XSS-Protection`

It was added via _Spring Security_ but is now useless (was only ever implemented in Chrome, and was [removed](https://chromium.googlesource.com/chromium/src.git/+/73d3b625e731badaf9ad3b8f3e6cdf951387a589) in 2019). We remove it as [recommended by OWASP](https://cheatsheetseries.owasp.org/cheatsheets/HTTP_Headers_Cheat_Sheet.html#recommendation_1).

## Session expiration

We have two goals for this one:

* Make session expiration work properly
* Do not degrade (or even improve) user experience regarding sessions

### Implementation

The existing session expiration problem was that our Web framework, Lift, includes regular requests on open pages
to allow receiving events from the server (without WebSockets).
Even if Jetty was supposed to enforce a default 30 minutes expiration, this was made inoperative
by the requests sent by the pages every 2 minutes (called `comet` requests).
So the expiration actually worked in case the browser was disconnected (e.g. suspended machine) but not when the tab stayed open
(e.g. locked session).

What we want is for the expiration to be based on actual user interaction and not automated requests.
There is no native feature for this in Lift, so we need to implement it ourselves.

To do so we store additional information inside the session object in the `lastNonCometAccessedTime` attribute,
containing the last user action.
We keep it up to date at every request through a hook provided by Lift, by filtering requests with
`r.standardRequest_?` which only selects real user action.

Then we use a hook in the session housekeeping process to close all session without activity since N minutes.

Note: We only implement an idle timeout and no limit in absolute session duration.

### Other changes

To improve user experience, especially as users will see more expired sessions:

* We change the redirect from `/` to `/rudder/secure` (the dashboard) instead of `/rudder` (the login page)
  * The allows opening the dashboard when navigating to the root URL when the browser is connected.
  * Before it would redirect to the login page, which invalidates session cookies and disconnects all tabs.
  * When the browser is not connected the `/rudder` URL is redirected to the login page anyway.
* When an open tab detects that the session has been closed it used to redirect it to the login page.
  Instead, we only display a notification.
  * This made the users lose what they were working on.
  * Was particularly cumbersome when chained with the login page redirect.
  * We replaced it with a persistent error notification in the upper right of the screen. If the user has reconnected since, they can just close it and continue working.

## Centralizing security options

We tried to group security settings in one place, contrary to the previous situation
(they were split between Apache httpd configuration, Sprint Security configuration,
some Lift code, etc.), making them hard to follow.

We removed all headers handling from Apache (as architecturally it should not do much more
than reverse proxying for the Rudder webapp).

The best place to put these settings were in code, to give us the best context over what's happening.
It is placed in the initialization part of the web application, in [Boot.scala](https://github.com/Normation/rudder/blob/91ebb3581ac6a7b3729f128ada5eb31009b3baf7/webapp/sources/rudder/rudder-web/src/main/scala/bootstrap/liftweb/Boot.scala#L340-L452).

## Enforcing HTTPS

To allow easily setting up the `` header with relevant values, we added the following settings in `rudder-web.properties`

```toml
rudder.server.hsts=true
rudder.server.hstsIncludeSubDomains=true
```

Which produces:

```bash
Strict-Transport-Security
    # max-age = 1 year
	max-age=31536000 ; includeSubDomains
```

They are not enabled by default as it could cause problems with non-HTTPS applications hosted on the same system.

## Information disclosure

We also worked on removing information about dependencies versions
as a general good practice (especially as it makes some security auditors happy):

* We removed the `X-Lift-Version` header
* We removed the Rudder version on the login page
* We added documentation to hide Apache version in our hardening page.
* We added a `X-Robots-Tag` header to prevent any search engine indexation (additionally to the robots.txt already in place)

---

## Time-line

### 2022/08: First batch of fixes (6.2.16/7.0.5/7.1.3)

- In Rudder 
- Session cookie security flag `SameSite=Lax`
- Various XSS protections in Javascript

### 2022 Q3/4: Hardening improvements (7.2.3/7.2.4)

- Session expiration
- Hardening configuration
  - `Content-Security-Policy`
  - `Strict-Transport-Security`
  - `X-Frame-Options`
  - `X-Robots-Tag`
- Centralized security settings
- Rudder hardening documentation

### 2023/04: Dependency management (7.3.0)

- Dependency management with npm for everything (except a few manually fixed angularJS libraries)
  - Full upgrade of all frontend dependencies (a few documented exceptions)
- Vulnerability checks in dependencies using `better-npm-audit`

### Future

- Replace usage of AngularJS
- Avoid interactions between Elm and JS
