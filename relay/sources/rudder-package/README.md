# RPKG format

## Architecture
The rpkg file is an [ar](https://linux.die.net/man/1/ar) archive containing:
* a metadata file
* several archives in txz format
```bash
~% ar t ./rudder-plugin-dsc-8.0.3-2.5.rpkg
metadata
files.txz
scripts.txz
hooks.txz
ncf.txz
techniques.txz
agent-policy.txz
dsc-common.txz
```
A given rpkg is always built for a given webapp version written in its metadata, if installed on a Rudder server with a different webapp version, the installation will be refused to ensure strict compatibility.
### Metadata
Each rpkg contain a metadata file at the start of the ar archive.
It is in __Json__ format and contains all the data of the rpkg and how it should be unpacked.

#### Mandatory fields
* `type`:
  * only `plugin` is supported
* `name`:
  * String
  * `^rudder-plugin-([-a-z])+$`
* `version`:
  * String
  * Of the form `<webappVersion>-<pluginVersion>`
    * `webappVersion`: `^\d+\.\d+\.\d+(~(alpha|beta|rc|git)\d+)?`
    * `pluginVersion`: `^\d+\.\d+(-nightly)?$`
* `build-date`:
  * Timestamp in UTC and RFC 3339 format
* `build-commit`: commit hash
* `content`:
  * Data
  *  Of the form:
    ```json
    {
      "txz_name.txz": "unpack_destination",
      ...
    }
    ```
  * `scripts.txz` must not be referenced here

#### Optional fields
* `depends`:
  * Data with the following optional fields:
    * `binary`:
      * Array
      * Each element will be validated if found as an executable on the system
    * `apt`:
      * Array
      * Each element will be validated if found installed using dpkg
      * Ignored if `apt` is not found on the system
    * `rpm`:
      * Array
      * Each element will be validated if found installed using rpm
      * Ignored if `rpm` is not found on the system
       * `python`:
         * Array
         * Deprecated, ignored
* `jar_files`:
  * Array
  * Absolute path to jar files contained in the rpkg that need to be loaded by the webapp.

#### Example
```json
{
  "type": "plugin",
  "name": "rudder-plugin-dsc",
  "version": "8.0.3-2.5",
  "build-date": "2023-11-23T17:52:03+00:00",
  "build-commit": "bfd5b32bafa097e81a25bcb0d33a1e0a94160b7c",
  "depends": {
    "binary": [ "zip" ]
  },
  "jar-files": [ "/opt/rudder/share/plugins/dsc/dsc.jar" ],
  "content": {
    "files.txz": "/opt/rudder/share/plugins",
    "agent-policy.txz": "/var/rudder/configuration-repository",
    "dsc-common.txz": "/var/rudder/configuration-repository/techniques/system",
    "techniques.txz": "/var/rudder/configuration-repository",
    "ncf.txz": "/var/rudder/configuration-repository",
    "hooks.txz": "/opt/rudder/etc"
  }
}
```
### TXZ archives

Rpkg content are packaged in several `.txz` tarball, each one of them is unpacked in the associated destination found in the metadata, except for the `scripts.txz` as described below.
A rpkg must at least contain one tarball other than the `scripts.txz` one.

When a package is uninstalled, each file deployed during the installation of the package will be removed.

#### Package scripts
The `scripts.txz` is mandatory and contains the eventual package scripts of the rpkg, they will always be unpacked under `/var/rudder/packages/<package_name_from_metadata>/`. 
This folder is created and the `scripts.txz` unpacked before any other txz archive at install time, and before any package script execution.
It is removed only after the execution of eventual package script when uninstalling a rpkg and after every other installed files has been removed.

Supported package scripts are:
* __preinst__: Executed before unpacking an rpkg content
 * __postinst__: Executed after the all files of the plugin content were unpacked at install time, before the webapp restart
* __prerm__: Executed before removing an rpkg installed files
* __postrm__: Executed after the removal of the installed files of an installed rpkg

Each package script can receive one argument when called by `rudder-package`:
* `install` when installing a new plugin
* `upgrade` when upgrading a plugin
* None otherwise

Each package script must be at the root of the `txz` archive, be executable and named exactly as its type. Please note that they will be run with as the root user.

#### Configuration files and persistent data

Package configuration and data that should survive to uninstallation should always be deployed in the `/var/rudder/plugins/<package_name_from_metadata>/` folder and not by the embedded txz as otherwise, they will be removed when uninstalling or upgrading the plugin.

###  Upgrade
A package upgrade follows the steps below:

* Check for dependencies of the new version
* Disable jar_files from installed version if any
* Execute the prerm from the installed version
* Remove installed files from the installed version
* Install the new version and runu the package scripts with the `upgrade` argument


