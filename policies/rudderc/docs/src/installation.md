# Installation

* `rudderc` is built into the Rudder server. The binary is available in `/opt/rudder/bin/rudderc`.
* On other systems, like workstations, you can download a
  [pre-built binary](https://repository.rudder.io/tools/rudderc/8.0/) for Linux or Windows x86_64 in the repository.

To be able to check and compile techniques, the `rudderc` program
needs access to the method library of the target systems.
When running on a Rudder server, which has a built-in `rudderc` binary,
the local library will be used.
The standalone binary from the repository includes the default library.
You can override the default library by passing a `--library` argument.
