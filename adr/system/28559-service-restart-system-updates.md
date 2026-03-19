# Service restarts in system-updates module

* Status: accepted
* Deciders: AMO-FDA
* Date: 2026-03-19

## Context

The `system-updates` modules works by disabling service restarts at the package manager level when existing,
and running service restarts according to configuration after the upgrade is done.
Until now these restarts were all done using `systemctl` directly. This allowed a consistent approach to service restarts
across all supported platforms.

But as reported in [#28385](https://issues.rudder.io/issues/28385), we get frequent service restart errors, due to some special
system services that can't be restarted.

## Decision

We decide to switch to using the system's native service restart mechanism as much as possible, as it can handle these special cases better. 

Especially, on APT-based systems, we use `needrestart` to handle service restarts, as it comes with a [preconfigured list](https://github.com/liske/needrestart/tree/6d7a76b065dc82386f3e10a77f4cb1bca853804b/ex/restart.d) of exceptions.
To allow this, we remove the `System` trait that abstracted restarts and reboots, and instad provide these as `PackageManager` methods.
The `systemd`-based implementation stays the default for both.

## Consequences

* The `system-update` module will now use `needrestart` on APT-based systems to perform service restarts, and `systemctl` on others.
* The upgrade and service restarts stay distinct steps for consistency.
* We have an interface allowing easily adding exceptions for other package managers / operating systems in the future when needed.