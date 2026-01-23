# Format dates with explicit timezones

* Status: accepted
* Deciders: AMO, CAN, FAR, FDA, MHA, PIO, RGA, VHA, VME
* Date: 2025-01-21


## Context

Dates, with or without time components are present around many places in the webapp, in web pages and in the REST API.
In this ADR, we will mostly refer to dates with time components.
There are different kind of possible formats for dates : 
* starting with a common example that is compatible with [ISO 8601](https://en.wikipedia.org/wiki/ISO_8601) and [RFC 3339](https://www.rfc-editor.org/rfc/rfc3339#section-5.6) : `2026-01-21T06:56:23Z`, it can also include offset and timezone in various formats, for example `2026-01-21T07:56:23+01:00`
* any custom display : 
  * a human-readable one that is relative to user locale : `Thu Jan 21 2026 07:56:23 GMT+0100 (Central European Standard Time)`,
  * one that is not compliant with ISO 8601 but which is valid with respect to RFC 3339 and which is more user-friendly : `2026-01-21 07:56:23+01:00`,
  * one that has no timezone, which then needs to be assumed implicitly : `2026-01-21 07:56:23`.

There has been quite a number of RFC that propose specific formats of dates : 
* [RFC 822](https://www.rfc-editor.org/rfc/rfc822#section-5.1) and [RFC 1123](https://www.rfc-editor.org/rfc/rfc1123#section-5) for e-mail headers
* [RFC 3339](https://www.rfc-editor.org/rfc/rfc3339#section-5.6) and [RFC 9557](https://www.rfc-editor.org/rfc/rfc9557#name-syntax-extensions-to-rfc-33) (addin  for dates
* [RFC 4517](https://www.rfc-editor.org/rfc/rfc4517.html#section-3.3.13) for LDAP specific date format ("Generalized time")

In the backend, dates are stored in different formats, including some among those ones, depending on the kind of storage.

In the public REST API and internal representations, we often use the UTC timezone with the format compatible with both ISO 8601 and RFC 3339, but there might still be some dates with explicit server timezone as specified by RFC 9557.

Across web pages in Rudder, mixed formats have been used to display dates, and in some places they do not even include an explicit timezone.
The timezone that is used for display in web pages is :
* often the server one (in Rudder version 8.3 and below),
* when it is not the case, it is either : 
  * the UTC timezone,
  * the timezone of the browser.

There is no current way to customize the format, nor the timezone, that is already not the same one across all places.

## Decision

* In output formatting of dates that may or may not be visible by users, add the timezone explicitly to all dates, regardless of the formatting of the date and time parts.
  * For example, `2026-01-21 07:56:23` should become `2026-01-21 07:56:23+01:00`
* In the REST API, generally use a RFC 3339 and ISO 8601 compatible format :
  * in queries, strictly support parsing of both RFC 3339 and ISO 8601 compatible formats, for example with formatters provided by [java-time](https://docs.oracle.com/javase/8/docs/api/java/time/format/DateTimeFormatter.html#ISO_OFFSET_DATE_TIME)
  * in responses, output dates with RFC 3339 format with the UTC timezone in general, and use RFC 9557 if timezone are needed in the date representation, the java-time formatters should also be useful for that (since some match the RFC 3339 format while being ISO 8601 compatible)
* In all web pages, use a global timezone that can be shared by all components that display dates :
  * one that is set from user preferences, if there is a way to select a global timezone for display in the webapp
  * if such preference do not exist, fallback to the server timezone, since is is the one that is already displayed most often
  * in some cases when the global timezone is conflicting with dates specific to a timezone (ex: in objects configured with explicit timezone), the date could be rendered with that timezone

## Consequences

We will need to audit the current places dates are being displayed in the UI and update them to follow this ADR.
We will also need to audit the responses of the API with tests and ensure they are in UTC.
