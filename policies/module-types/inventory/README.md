# minifusion

## Goals

* In terms of content, there are two goals:
  * Provide everything needed for Rudder to operate correctly
  * Provide everything needed to satisfy common use cases in Cloud/Virtualized contexts

## Non-goals

* Replace Fusion Inventory or any other _real_ inventory tool.
* In particular, there is absolutely no support for hardware information.

## Compatibility

`minifusion` should be minimally compatible with all Unix systems.
Full inventory information requires:

* systemd for machine type information
* the `/etc/os-release` file for OS information

## Usage

To try it on a Rudder agent:

* Disable inventory check script
* Add this to `/opt/rudder/bin/run-inventory`:

```shell
path/to/minifusion "$@"
```