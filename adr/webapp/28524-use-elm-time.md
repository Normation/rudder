# Use elm/time to display dates

* Status: accepted
* Deciders: CAN, FAR, PIO, RGA, VHA, VME
* Date: 2026-03-11


## Context

The Elm ecosystem had a breakup from the upgrade of version 0.18 to the current 0.19 version: the default and recommended package for dates is now [elm/time](https://package.elm-lang.org/packages/elm/time), instead of [isaacseymour/deprecated-time](https://package.elm-lang.org/packages/isaacseymour/deprecated-time) (which was previously [`elm-community/elm-time`](https://github.com/elm-community/elm-time)).

The API is significantly different, and the new API of 0.19 has a main [`Posix`](https://package.elm-lang.org/packages/elm/time/latest/Time#Posix) type for time computations, and a [`Zone`](https://package.elm-lang.org/packages/elm/time/latest/Time#Zone) when needing dates computation or human display of computed time.

We have been developing with the 0.18 Elm version in some parts of the webapp frontend (notably in plugins), and since the upgrade to the 0.19 Elm version we still use the deprecated time package which kept the compatibility. But we are now also using the recommended _elm/time_ package in newer frontend components. This mix introduces some confusion since the previous types were named `DateTime` and `TimeZone` (which seems familiar to usual backend representations, namely in Java date and time API).


Since ADR [#28227](https://issues.rudder.io/issues/28227), when interacting with the REST API, we should be able to parse dates in the RFC 3339 format and also serialize with the same format, which can be done with other Elm packages : [rtfeldman/elm-iso8601-date-strings](https://package.elm-lang.org/packages/rtfeldman/elm-iso8601-date-strings), or more directly for decoding with [elm-community/json-extra](https://package.elm-lang.org/packages/elm-community/json-extra/latest/Json-Decode-Extra#datetime).

We should also display dates and time components most often by using a global timezone :
> * one that is set from user preferences, if there is a way to select a global default timezone for display in the webapp
> * if such preference do not exist, fallback to the server timezone, since is it the one that is already displayed most often
> * in some cases when the global timezone is conflicting with dates specific to a timezone (ex: in objects configured with explicit timezone, like _campaigns_), the date could be rendered with that specific timezone

Such global timezone or preference should be provided to the Elm app (e.g. from the _localStorage_ or directly from the server). But the _elm/time_ API is quite limited when used by itself to get timezones : only the UTC and the browser timezone can be obtained. There are other packages in the ecosystem that are dedicated for expanding an application with other timezones, such as [justinmimbs/timezone-data](https://package.elm-lang.org/packages/justinmimbs/timezone-data).

There is also a consideration that there are still some usage of JavaScript to display dates, whereas the native dates API is very restrained (the newer [`Temporal`](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Temporal) API is not yet fully supported by most browsers at the time of writing). But those JavaScript dates are meant to be replaced with Elm parts, so the main focus is to comply with ADR [#28227](https://issues.rudder.io/issues/28227) on this side.

## Decision

* Always use the _elm/time_ types to represent date and time in our Elm applications and libraries :
  * `Time.Posix` for time
  * `Time.Zone` for timezone
* Use libraries from the Elm ecosystem in complement to display dates with : 
  * [rtfeldman/elm-iso8601-date-strings](https://package.elm-lang.org/packages/rtfeldman/elm-iso8601-date-strings)
  * [elm-community/json-extra](https://package.elm-lang.org/packages/elm-community/json-extra/latest/Json-Decode-Extra#datetime)
  * _...other useful library of our choice_


## Consequences

* We will need to migrate usage of the deprecated 0.18 time package to be replaced with _elm/time_ to follow this ADR : 
  * replacing `Time.DateTime` or `Time.ZonedDateTime` with `Time.Posix`
  * replacing `Time.TimeZone` with `Time.Zone`
* We will also need to replace bits of JavaScript that do date computations with Elm applications that use _elm/time_
