# Use elm-format as elm files formatter


* Status: accepted
* Deciders: PIO, VHA, CAN, VME, FAR, AMO, RGA
* Date: 2026-06-08

## Context

There is no formatter applied automatically, no validation to check the files are correctly formatted.
The file format is not consistent, and it's getting worse.
Also we already enforce formatting for other languages (Scala, Rust, a bit of Python).

## Decision

After a discussion the team agreed on using elm-format.
Adding a check format step in gulp file.
Adding an elm-format command to apply format on all elm files.

## Consequences

Formatting the files will have a large impact on every files. 
Also the upmerge process needs to be considered.
https://issues.rudder.io/issues/28989