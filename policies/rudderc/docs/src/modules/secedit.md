# Secedit module

The secedit module allows configuring Windows security policies using
[Secedit](https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/secedit).

This module is available starting from Rudder 9.1.

The module supports two modes:

- Enforce mode: The module applies changes specified by the *data* parameter.
- Audit mode: The module does not apply changes. Instead, it compares the
changes with the actual security policies and provides a diff view.

## Parameters

The module requires a parameter named *data*.
This parameter must contain a JSON object describing the security configuration to apply.
Each top-level key represents a Secedit configuration section, and contains the settings for that section.

Example:

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

## Supported sections

The following sections are supported:

- *Unicode*
- *System Access*
- *Event Audit*
- *Privilege Rights*

## Unsupported sections

The *Registry Values* section is **not supported**.
To edit registry entries, refer to the Rudder registry management
[documentation](https://docs.rudder.io/rudder-by-example/current/system/manage-registry.html).

## CLI

The module provides a CLI for debugging and manually applying security policies.

```
Usage: rudder-module-secedit [OPTIONS] --data <DATA> --tmp <TMP>

Options:
  -d, --data <DATA>  JSON data file
  -a, --audit        Audit mode
  -t, --tmp <TMP>    Path for temporary files
  -h, --help         Print help
  -V, --version      Print version
```
