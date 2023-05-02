# Testing

## Write technique tests

**NOTE 1**: Techniques tests are only supported on Linux for now.

**NOTE 2**: Not all methods can run successfully with this testing method.
In particular all methods exchanging files with the Rudder server won't work. 

The tests are placed in a `tests` folder in the technique directory.
Each test case is defined by a YAML file in this folder.

The format of the test case file is:

```yaml
# The technique parameters
params:
  param1: value1
  param2: true
setup:
  - sh: "test command"
check:
  - sh: "test command"
  - sh: "test command2"
```

The setup and check sections contain a list of commands to run.
These commands run in the test directory, and are passed to `/usr/bin/sh`.

The detailed test process is, for each `*.yml` file in the `tests` directory:

* Build the technique in *standalone* mode (i.e. equivalent to passing `--standalone` to the build command). This adds a prelude to the technique allowing it to run without a Rudder server.
* Run the setup commands sequentially. The commands are called in the `/bin/sh` shell.
* Run an agent locally, which will:
    * Load all methods from the library
    * Read the parameters values from the YAML test case
    * Run the technique with the given parameters
* Run the check commands sequentially. The commands are called in the `/bin/sh` shell.

The build stops when it encounters a command returning a non-zero return code.

## Run technique tests

To run the test `case1` from:

```text
my_technique/
  ├── technique.yml
  ├── resources/
  └── tests/
        ├── case1.yml
        └── case1.conf.ref
```

Run the following:

```shell
# Run all defined tests
rudderc test --library /path/to/lib
# Run only the case1 test
rudderc test --library /path/to/lib case1
```

* the library parameter here **must** be a full library (containing `10_ncf_internals`, etc.), because it will also be used by the agent to run.
* the optional argument allows filtering the tests, only run those containing the given string.
