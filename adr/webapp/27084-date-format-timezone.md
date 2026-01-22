# Format dates with explicit timezones

* Status: accepted
* Deciders: AMO, CAN, FAR, FDA, MHA, PIO, RGA, VHA, VME
* Date: 2025-01-21


## Context

Dates are present around many places in the webapp, in web pages and in the REST API.
There are different kind of possible formats for dates : 
* starting with the [ISO 8601](https://en.wikipedia.org/wiki/ISO_8601) one (ex: `2026-01-21T07:56:23+01:00`), which can includes timezones in various formats
* any custom display : 
  * a human-readable one that is relative to user locale : `Thu Jan 22 2026 07:56:23 GMT+0100 (Central European Standard Time)`,
  * one that is much like the ISO 8601 but which is more user-friendly : `2026-01-21 07:56:23+01:00`,
  * one that has no timezone, which then needs to be assumed implicitly : `2026-01-21 07:56:23`.

In the public REST API and internal representations, we often use the UTC timezone with the ISO 8601 format, so with the `Z` offset, but there might still be some dates with server timezone.
Across web pages in Rudder, mixed formats have been used to display dates, and in some places they do not even include an explicit timezone.
The timezone that is used for display in web pages is :
* often the server one (in Rudder version 8.3 and below),
* when it is not the case, it is either : 
  * the UTC timezone,
  * the timezone of the browser.

There is no current way to customize the format, nor the timezone, that is already not the same one across all places.

## Decision

* Add the timezone explicitly to all dates, regardless of the formatting of the date and time parts.
* In the REST API, use the ISO 8601 format, support all timezones, and format dates with UTC timezone in responses.
* In all web pages, use a single timezone that can be shared by all components that display dates :
  * if there is a way to set a specific timezone to be used in the webapp, use it
  * if not, fallback to the server timezone, since is is the one that is already displayed most often

## Consequences

We will need to audit the current places dates are being displayed in the UI and update them to follow this ADR.
We will also need to audit the responses of the API with tests and ensure they are in UTC.
