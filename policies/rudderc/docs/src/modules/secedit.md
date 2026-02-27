# Secedit module

This module enables the use of [Secedit](https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/secedit)
on Windows systems.
It is available in Rudder version 9.1 and above.

The module requires an argument named `data`, which is a JSON object that contains
properties representing each Secedit section.

The sections supported are:

- *Unicode*
- *System Access*
- *Event Audit*
- *Privilege Rights*

The *Registry Values* section is not supported.
To edit registry entries, refer to the rudder registry
[documentation](https://docs.rudder.io/rudder-by-example/current/system/manage-registry.html).

Below is an example of the expected structure:

```
{
  "Unicode": {
    "Unicode": "yes"
  },
  "System Access": {
    "MinimumPasswordLength": 0,
    "MinimumPasswordAge": 0,
    "NewAdministratorName": "Administrator"
  },
  "Event Audit": {
    "AuditSystemEvents": 0
  },
  "Privilege Rights": {
    "SeRemoteShutdownPrivilege": "*S-1-5-32-544",
    "SeChangeNotifyPrivilege": "*S-1-1-0,*S-1-5-19,*S-1-5-20,*S-1-5-32-544,*S-1-5-32-545,*S-1-5-32-551"
  }
}
```

## CLI

The module provides a CLI.

```
Usage: rudder-module-secedit [OPTIONS] --data <DATA> --tmp <TMP>

Options:
  -d, --data <DATA>  JSON data file
  -a, --audit        Audit mode
  -t, --tmp <TMP>    Path for temporary files
  -h, --help         Print help
  -V, --version      Print version
```
