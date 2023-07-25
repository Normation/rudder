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

* When running this command outside a Rudder server, you need to pass it a library path
(as it is required to run the agent).
  * It can be the `tree` directory in a clone of the [ncf repository](https://github.com/Normation/ncf/)
* the optional argument allows filtering the tests, only run those containing the given string.

### Test outputs

The test runner will parse the agent output and place it in JSON format into `target/tests/case1.json`.
It is written before running check steps, so you can use it to assess reporting output
(for example, using `jq` in a shell script).

```json
[
  {
    "component": "Ensure correct ntp configuration",
    "key_value": "/tmp/rudderc_test_one",
    "event_type": "result_repaired",
    "msg": "Insert content into /tmp/rudderc_test_one was repaired",
    "report_id": "d86ce2e5-d5b6-45cc-87e8-c11cca71d907",
    "logs": []
  }
]
```
