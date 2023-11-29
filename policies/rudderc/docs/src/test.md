# Testing

## Write technique tests

**NOTE**: Not all methods can run successfully with this testing method.
In particular all methods exchanging files with the Rudder server won't work. 

The tests are placed in a `tests` folder in the technique directory.
Each test case is defined by a YAML file in this folder.

The format of the test case file is:

* `target` (optional): Target platform, `unix` or `windows`. Defaults to `unix`.
* `params` (optional): Named parameters passed to the technique in as key-values.
* `conditions` (optional): Conditions to define before running the technique.
* `policy_mode` (optional): The mode to use for running the technique. `audit` or `enforce`, defaults to `enforce`.
* `setup` (optional): Steps to run sequentially to prepare the environment for the test.
* `check`: Test steps, run sequentially. If one of them fails the test will be considered as a failure.
* `cleanup` (optional): Steps to run sequentially after the test to clean the environment.

The setup and check sections contain a list of commands to run. The only supported step type
for now is `sh`, which runs commands in a shell (`/usr/bin/sh` on Linux target, PowerShell on Windows target).
The outcome is based on the returned code, 0 is a success and other codes are failures.

Example:

```yaml
target: windows
params:
  param1: value1
  param2: true
conditions:
  - condition1
  - condition2
policy_mode: audit
setup:
  - sh: "touch /my/file"
check:
  # Linux target
  - sh: "test -f /my/file"
  # Windows target
  - sh: "Test-Path -Path C:\\my\\file"
cleanup:
  - sh: "rm -f /my/file"
```

The detailed test process is, for each `*.yml` file in the `tests` directory:

* Build the technique in *standalone* mode (i.e. equivalent to passing `--standalone` to the build command). This adds a prelude to the technique allowing it to run without a Rudder server.
* Run the setup commands sequentially. The commands are called in a shell.
* Run an agent locally, which will:
    * Load all methods from the library
    * Read the parameters values from the YAML test case
    * Define the conditions from the YAML test case
    * Run the technique with the given parameters
* Run the check commands sequentially. The commands are called in a shell.
* Run the cleanup commands sequentially, regardless of the result of the check commands.

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
  * It can be the `tree` directory in a clone of the [ncf repository](https://github.com/Normation/ncf/).
  * On Windows it can be the path to an agent installed locally or a decompressed `msi`.
* the optional argument allows filtering the tests, only run those containing the given string.

### Test outputs

The test runner will parse the agent output and place it in JSON format into `target/<case_name>.json`.
It is written before running check steps, so you can use it to assess reporting output
(for example, using `jq` in a shell script).

For convenience, *check* steps have an environment variable `REPORTS_FILE` pointing to the current reports
JSON.

The JSON file contains an array of entries: 

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
