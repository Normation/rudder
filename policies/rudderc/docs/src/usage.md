# Usage

## The `rudderc` CLI

Rudder comes with a tool dedicated to the
techniques development and usage.
It is especially important as techniques are not run as YAML, but compiled into
an executable policy file depending on the target platform.
There are currently two possible targets, which are the platforms Rudder has agents for:

* Linux/AIX
* Windows

These platforms use different agent technology, but the YAML policies
unify them.

### Installation

* `rudderc` is built into the Rudder server. The binary is available in `/opt/rudder/bin/rudderc`.
* On other systems, like workstations, you can download a
  [pre-built binary](https://repository.rudder.io/tools/rudderc/8.0/rudderc) for Linux x86_64 in the repository.

To be able to check and compile techniques, the `rudderc` program
needs access to the method library of the target systems.
When running on a Rudder server, which has a built-in `rudderc` binary,
the local library will be used.
The standalone binary from the repository includes the default library.
You can override the default library by passing a `--library` argument.

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
  ├── resources/
  └── tests/
```

The `technique.yml` is the technique content,
and the `resources` directory can be used to include
external files (configuration files, templates, etc.).
The tests directory will contain your technique's tests.
All files produced by `rudderc` will be placed in the `target`
directory.

### Check a technique

You can check the current technique syntax with:

```shell
$ rudderc check
        Read 179 methods (/path/to/methods/lib)
   Compiling my_technique v0.1 [Linux]
   Compiling my_technique v0.1 [Windows]
     Checked technique.yml
```

This will check the technique [schema](https://raw.githubusercontent.com/Normation/rudder/master/policies/rudderc/src/technique.schema.json) and check the compilation
to the target platforms.

### Compile for the target platforms

```shell
$ rudderc build
        Read 179 methods (/path/to/methods/lib)
   Compiling my_technique v0.1 [Linux]
       Wrote target/technique.cf
   Compiling my_technique v0.1 [Windows]
       Wrote target/technique.ps1
  Generating my_technique v0.1 [Metadata]
       Wrote target/metadata.xml
      Copied resources
```

### Clean files

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
$ rudderc lib
        Read 179 methods (/.../ncf/tree/30_generic_methods/)
Book building has started
Running the html backend
       Wrote target/doc/book/index.html
```

To open the documentation in your browser after build, pass the `--open` option.

## Import a technique into Rudder

### As normal techniques

To add a YAML technique as a "normal" technique, which gives access to the full power of
parameter types, you need to run these commands on your server:

```shell
rudderc build
cd /var/rudder/configuration-repository/techniques/
mkdir -p CATEGORY/MY_TECHNIQUE/1.0/
cp -r /path/to/technique/target/* CATEGORY/MY_TECHNIQUE/1.0/
git add CATEGORY/MY_TECHNIQUE/
git commit -m "Add my technique"
rudder server reload-techniques
```

*Warning*: `rudder server reload-techniques` is an asynchronous command.
It returns immediately with a success, and
you need to check web application logs for errors (`/var/log/rudder/webapp/`) afterwards.

Once imported, the technique will be available like built-in ones, in the directives' page.
To update the technique, repeat the import steps.

### In the technique editor

The technique editor is able to directly use the YAML format (but does not support technique parameter types
for now, and does not display tags).

```shell
cd /var/rudder/configuration-repository/
mkdir -p CATEGORY/MY_TECHNIQUE/1.0/
cp /path/to/technique.yml  CATEGORY/MY_TECHNIQUE/1.0/
git add CATEGORY/MY_TECHNIQUE/
git commit -m "Add my technique"
rudder server reload-techniques
```