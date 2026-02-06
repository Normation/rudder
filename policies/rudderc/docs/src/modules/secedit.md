# Secedit module

This module enables the use of [Secedit](https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/secedit)
on Windows systems.
It is available in Rudder version 9.1 and above.

The module requires an argument named `data`, which is a JSON object that contains
sub-objects representing each Secedit section. Below is an example of the
expected structure:

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
  "Privilege Rights": {
    "SeRemoteShutdownPrivilege": "*S-1-5-32-544"
  }
}
```

The *Registry Values* section is currently not supported.
To edit registry entries, refer to the registry methods
[documentation](https://docs.rudder.io/rudder-by-example/current/system/manage-registry.html)
