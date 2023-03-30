# Usage

## The `rudderc` CLI

Rudder comes with a tool dedicated to the
techniques development and usage.
It is especially important as technique are not run as YAML, but compiled into
an executable policy file depending on the target platform.
There are currently two possible target, which are the platforms Rudder has agents for:

* Linux(/AIX)
* Windows

These platforms use different agent technology but the YAML policies
allows unifying them.
To be able to check and compile technique, the `rudderc` program
needs access to the methods library of the target systems.

FIXME solution pour les repos

### Create a technique

To setup the technique structure:

```shell
$ rudderc new my_technique
       Wrote ./my_technique/technique.yml

$ cd my_technique
```

This will create the base structure of your new technique:

```text
my_technique/
  | technique.yml
  | resources/
```

The `technique.yml` is the technique content,
and the `resources` directory can be used to include
external files (configuration files, templates, etc.).

### Checking a technique

TODO library

You can check the current technique syntax with:

```shell
$ rudderc check --library /path/to/methods/lib
        Read 179 methods (/path/to/methods/lib)
   Compiling my_technique v0.1 [Linux] (technique.yml)
   Compiling my_technique v0.1 [Windows] (technique.yml)
     Checked technique.yml
```

This will check the technique schema and check the compilation
to the target platforms.

### Compile for the target platforms

```shell
$ rudderc build -l /path/to/methods/lib
        Read 179 methods (/path/to/methods/lib)
   Compiling my_technique v0.1 [Linux] (technique.yml)
       Wrote target/technique.cf
   Compiling my_technique v0.1 [Windows] (technique.yml)
       Wrote target/technique.ps1
  Generating my_technique v0.1 [metadata] (technique.yml)
       Wrote target/metadata.xml
      Copied resources
```