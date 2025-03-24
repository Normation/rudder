# Template

Template module type.

It supports:

* Mustache
* MiniJinja which is a subset of Jinja2

## CLI

The module provides a CLI to help debug the rendering of templates.

```shell
$ /opt/rudder/bin/rudder-module-template --help
Usage: rudder-module-template [OPTIONS] --template <TEMPLATE> --data <DATA> --out <OUT>

Options:
  -e, --engine <ENGINE>      Template engine [default: mini-jinja] [possible values: mustache, mini-jinja]
  -t, --template <TEMPLATE>  Template file
  -d, --data <DATA>          JSON data file
  -o, --out <OUT>            Output file
  -h, --help                 Print help
  -V, --version              Print version
```
