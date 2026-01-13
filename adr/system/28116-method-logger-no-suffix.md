# [Simpler method logger without suffix]

- Status: accepted
- Deciders: AMO, MHA
- Date: 2025/11/20

## Context

Our current method logger automatically adds suffixes based on the method outcome.
It works pretty well for simple methods as it allows to only describe what if being configured
and let the logger build the reported messages:

```
log_rudder_v4("${id}", "Package presence", "");
```

gives:

``` Package presence was repaired ```

But in a lot of cases this is undesirable as it makes more specific and
nice-looking reports harder. In particular, module-based reports like `template` or `augeas` produce detailed
messages already containing all the required information.
We need a way to use these messages as is.

Another problem with the current logger is the split between `message` and `details` parameters. It was intended
to allow implementing conditional details, to allow reducing the risk of sending sensitive data in the reports.
But it was never actually used, adds an unwanted space at the end of eahc message, and is better implement
with a specific parameter to the method/module (`show_content`), which allows for a more refined approach.

## Decision

- Introduce the new `log_rudder_v4_2` logger as an alternative to `log_rudder_v4` in 9.0.
  - The implementation is exactly the same except for the message content.
- Use it for module-based methods already.

## Consequences

We can continue migrating methods to the new logger when we detect it would be more appropriate.
The two loggers will continue to exist in the foreseable future.

Note: A lot of methods currently call the logger with action vers, while we should strive to use
states / names in order to produce a valid sentence once the suffix is added ("Remove the file" vs. "File absence")
