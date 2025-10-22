# Rudder Module - rudder

This repository contains a Rudder module runner.

## Running the Module

To run a compiled module the same way the agent does, you must use the `--cfengine` parameter:

```bash
/opt/rudder/bin/rudder-module-commands --cfengine
````

## Protocol Overview

The communication protocol is a **mix of raw text and JSON messages**.

1. The first message must always be a raw text line indicating the agent version:

   ```
   cf-agent 3.24.3 v1
   ```

2. The following messages must be JSON objects. They start with a `validate_promise` operation, followed by `evaluate_promise` operations (see [CFEngine custom promises protocol documentation](https://docs.cfengine.com/docs/3.24/reference-promise-types-custom.html#protocol)).

### Example Session

```bash
~commands% /opt/rudder/bin/rudder-module-commands --cfengine
cf-agent 3.24.3 v1

Commands 0.0.1 v1 json_based action_policy

{"filename":"/tmp/oui.fda","line_number":42,"operation":"validate_promise","log_level":"info","promise_type":"ruddercommand","promiser":"fda_test","attributes":{"node_id":"root","agent_frequency_minutes":"5","rudder_module_protocol":0,"data":{"args":"","chdir":"","command":"/bin/true /tmp/.tmpcMCLwA/target.txt","compliant_codes":"","env_vars":"","gid":"1000","in_shell":false,"output_to_file":"","repaired_codes":"0","run_in_audit_mode":false,"shell_path":"/bin/sh","show_content":true,"stdin":"","stdin_add_newline":true,"strip_output":false,"timeout":"30","uid":"1000","umask":"0022"}}}

{"operation":"validate_promise","promiser":"fda_test","attributes":{"temporary_dir":"/var/rudder/tmp/","backup_dir":"/var/rudder/modified-files/","state_dir":"/var/rudder/cfengine-community/state/","node_id":"root","agent_frequency_minutes":5,"rudder_module_protocol":0,"report_id":null,"data":{"args":"","chdir":"","command":"/bin/true /tmp/.tmpcMCLwA/target.txt","compliant_codes":"","env_vars":"","gid":"1000","in_shell":false,"output_to_file":"","repaired_codes":"0","run_in_audit_mode":false,"shell_path":"/bin/sh","show_content":true,"stdin":"","stdin_add_newline":true,"strip_output":false,"timeout":"30","uid":"1000","umask":"0022"},"action_policy":"fix"},"result":"valid"}
```

## Notes

* Everything under the `"data"` subkey must be adapted depending on the module you want to execute.
* The `"promise_type"` value also changes depending on the module.

When sending JSON messages (`validate_promise`, `evaluate_promise`), the following top-level keys are required:

| Key          | Description                                                                 |
|--------------|-----------------------------------------------------------------------------|
| `filename`   | Path to the file containing the promise.                                    |
| `line_number`| Line number in the policy file.                                             |
| `operation`  | The requested operation (`validate_promise`, `evaluate_promise`, etc.).     |
| `log_level`  | Log verbosity (e.g. `info`).                                                |
| `promise_type` | Type of promise (must match the module, e.g. `ruddercommand`).            |
| `promiser`   | Identifier of the promise being validated/evaluated.                        |
| `attributes` | Object containing metadata and module-specific parameters (see below).      |

Inside `attributes`, some subkeys are also mandatory:

| Subkey            | Description                                                             |
|-------------------|-------------------------------------------------------------------------|
| `node_id`         | Node uuid.                                                              |
| `agent_frequency_minutes` | Agent run frequency in minutes.                                         |
| `rudder_module_protocol` | Protocol version (typically `0`).                                       |
| `data`            | Object containing module-specific data (e.g. command, args, env, etc.). |

