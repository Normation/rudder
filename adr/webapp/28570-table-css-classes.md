# Use non-DataTables specific CSS classes for tables

* Status: accepted
* Deciders: CAN, PIO, RGA, VHA, VME
* Date: 2026-03-20


## Context

The _DataTables_ table library has changed its naming convention for CSS classes since [version 2](https://datatables.net/upgrade/2), the version we now have in Rudder version 9.1 :
* previously, classes were prefixed with `dataTables_`
* in the newer version, the prefix is `dt-`

We have been relying on the previous convention to create our own CSS classes atop the ones in library (e.g. `dataTables_wrapper`, `dataTables_wrapper_top`).
Also, we have been using them in two places :
* actual DataTables tables, initialized with JavaScript
* Elm tables, that have an HTML similar to the one of DataTables table, but have nothing to do with the JavaScript library (Elm apps define their own event handlers)

Such convention is no longer viable now that the library has changed its API :
* we should make our Elm tables independent from DataTable in the HTML and CSS classes they use (see https://github.com/Normation/rudder-elm-library for a Table component that we would prefer to use)
* therefore, by foreseeing future migration of JavaScript tables, we should also be using CSS classes that are independent from DataTables

## Decision

Do's and don'ts :
* ✅ Define and use CSS classes that do not mention DataTables
* ✅ For new tables, prefer using [our own library](https://github.com/Normation/rudder-elm-library)
* ❌ Avoid using the `dataTables_` prefix in CSS classes
* ❌ Avoid referencing DataTables in our Elm tables in general


## Consequences

* We will need to migrate all custom `dataTables_` CSS to the new CSS class we will be using, which will be an alias
* This will make two CSS classes live together until we get rid of DataTables'
