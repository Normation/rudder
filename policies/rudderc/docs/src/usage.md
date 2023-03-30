# Usage

## The `rudderc` CLI

Rudder comes with a tool dedicated to the
techniques development and usage.
It is especially important as techniques are not run as YAML, but compiled into
an executable policy file depending on the target platform.
There are currently two possible targets, which are the platforms Rudder has agents for:

* Linux/AIX
* Windows

These platforms use different agent technology but the YAML policies
unify them.
To be able to check and compile techniques, the `rudderc` program
needs access to the methods library of the target systems.

To get access to the base Linux methods set, you can use git
and use the repository as library argument:

```shell
$ git clone https://github.com/Normation/ncf/
# [...]
$ rudderc subcommand -l /.../ncf/tree/30_generic_methods/ 
```

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
  ├── technique.yml
  └── resources/
```

The `technique.yml` is the technique content,
and the `resources` directory can be used to include
external files (configuration files, templates, etc.).
All files produced by `rudderc` will be placed in the `target`
directory.

### Checking a technique

You can check the current technique syntax with:

```shell
$ rudderc check --library /path/to/methods/lib
        Read 179 methods (/path/to/methods/lib)
   Compiling my_technique v0.1 [Linux]
   Compiling my_technique v0.1 [Windows]
     Checked technique.yml
```

This will check the technique [schema](https://raw.githubusercontent.com/Normation/rudder/master/policies/rudderc/src/technique.schema.json) and check the compilation
to the target platforms.

### Compile for the target platforms

```shell
$ rudderc build -l /path/to/methods/lib
        Read 179 methods (/path/to/methods/lib)
   Compiling my_technique v0.1 [Linux]
       Wrote target/technique.cf
   Compiling my_technique v0.1 [Windows]
       Wrote target/technique.ps1
  Generating my_technique v0.1 [Metadata]
       Wrote target/metadata.xml
      Copied resources
```

### Clean you produced

The `clean` command allows removing all generated files.

```shell
$ rudderc clean
     Cleaned target
```

### Build the documentation

You can build this documentation directly using `rudderc`.
This can be specially useful if you use custom methods not
present in the public documentation.

```shell
$ rudderc lib -l /.../ncf/tree/30_generic_methods/
        Read 179 methods (/.../ncf/tree/30_generic_methods/)
Book building has started
Running the html backend
       Wrote target/doc/book/index.html
```

To open the documentation in your browser when built, pass the `--open` option.