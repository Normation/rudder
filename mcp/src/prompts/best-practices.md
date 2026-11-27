# Rudder technique Best Practices

Requirements and guidelines for writing Rudder techniques in YAML. Use it as a checklist when writing a new technique or reviewing an existing one.

## Required

### Format

#### Use no deprecated methods

- Do not use any method marked **⚠️ Deprecated** in the method reference. Use its current equivalent instead.
- Do not set deprecated method parameters either. For example, `hash_type` on `file_from_shared_folder` is deprecated and ignored.
- Deprecated methods in Rudder 9.1:
  - **Directory:** `directory_check_exists`, `directory_create`
  - **DSC:** `dsc_apply`, `dsc_built_in_resource`, `dsc_from_configuration`, `dsc_mof_file_apply`
  - **File:** `file_check_exists`, `file_check_symlinkto`, `file_copy_from_local_source`, `file_copy_from_local_source_recursion`, `file_copy_from_local_source_with_check`, `file_copy_from_remote_source`, `file_copy_from_remote_source_recursion`, `file_create`, `file_create_symlink`, `file_create_symlink_enforce`, `file_create_symlink_force`, `file_download`, `file_enforce_content`, `file_ensure_block_in_section`, `file_ensure_block_present`, `file_ensure_key_value`, `file_ensure_key_value_option`, `file_ensure_key_value_parameter_in_list`, `file_ensure_key_value_parameter_not_in_list`, `file_ensure_key_value_present_in_ini_section`, `file_ensure_keys_values`, `file_ensure_line_present_in_ini_section`, `file_ensure_line_present_in_xml_tag`, `file_ensure_lines_absent`, `file_ensure_lines_present`, `file_from_remote_source`, `file_from_remote_source_recursion`, `file_from_remote_template`, `file_from_template`, `file_remove`, `file_template_expand`
  - **Package:** `package_check_installed`, `package_install`, `package_install_version`, `package_install_version_cmp`, `package_install_version_cmp_update`, `package_remove`, `package_verify`, `package_verify_version`
  - **Permissions:** `permissions_dirs_recurse`, `permissions_recurse`
  - **Service:** `service_check_disabled_at_boot`, `service_check_running`, `service_check_started_at_boot`, `service_ensure_disabled_at_boot`, `service_ensure_running`, `service_ensure_running_path`, `service_ensure_started_at_boot`, `service_ensure_stopped`, `service_restart_if`, `service_start`, `service_stop`
  - **User:** `user_create`
- Common replacements (check the parameters in the method reference, as they can differ):

| Deprecated | Use instead |
|---|---|
| `file_ensure_lines_present` / `file_ensure_lines_absent` | `file_lines_present` / `file_lines_absent` |
| `file_ensure_key_value`, `file_ensure_keys_values` | `file_key_value_present`, `file_keys_values_present` |
| `file_ensure_block_present`, `file_ensure_block_in_section` | `file_block_present`, `file_block_present_in_section` |
| `file_enforce_content` | `file_content` |
| `file_create` / `file_remove` | `file_present` / `file_absent` |
| `file_create_symlink*` | `file_symlink_present*` |
| `file_copy_from_*` | `file_from_*` |
| `file_from_template`, `file_template_expand` | `file_from_template_options` |
| `directory_create` | `directory_present` |
| `package_install*` / `package_remove` | `package_present` / `package_absent` (or `package_state`) |
| `service_ensure_running` / `service_ensure_stopped` | `service_started` / `service_stopped` |
| `service_ensure_started_at_boot` / `service_ensure_disabled_at_boot` | `service_enabled` / `service_disabled` |
| `service_restart_if` | `service_restart` with a `condition` (see "Condition methods that perform an action") |
| `permissions_recurse`, `permissions_dirs_recurse` | `permissions_recursive`, `permissions_dirs_recursive` |
| `user_create` | `user_present` |

#### Make the technique ID match the technique name

- The `id` must be derived from the `name`, so that one can be guessed from the other. For example, `name: "NTP configuration"` gives `id: "ntp_configuration"`.
- The `id` may only contain alphanumeric and underscore characters.
- The `version` uses the `X.Y` format.

#### Give everything a proper name, description and documentation

- **Technique:** a clear `name`, a single-line `description` of what it does, and `documentation` (Markdown) for usage details and warnings.
- **Blocks and methods:** a `name` that says what the item ensures, for example `"Ensure telnet-server absence"`. This name is what appears in compliance reports. Without it, a method falls back to its technical name, which tells the reader nothing.
- **Parameters:** a `description` (single line) and, if needed, `documentation` (plain text, not Markdown). These are shown in the directive form.

### Structure

#### Avoid dependencies between techniques

- A technique must not use variables or conditions defined outside the technique itself, for example by another directive or another technique's `variable_*` or `condition_*` methods.
- Keep different techniques strictly independent of each other: no shared conditions, variables, and so on.
- Anything that must come from outside goes through a technique parameter (see "Take external values from technique parameters").

#### Use blocks when a technique becomes too big

- Limit complexity.
- A technique with more than about 10 methods becomes hard to maintain. Group methods into blocks (`items` inside a block), each with a meaningful `name`.
- When several methods differ only by their parameters, use `foreach` on a method or a block instead of repeating it. Set `foreach_name` when nesting loops, to avoid name clashes.

```yaml
- name: "Install the '${item.name}' package"
  method: package_present
  params:
    name: "${item.name}"
    version: "latest"
  foreach:
    - name: "vim"
    - name: "htop"
```

#### Prefer native methods over command execution

- **Really** avoid `command_execution` (and its variants) as much as possible:
  - It is not idempotent: the command runs at every agent run.
  - Compliance is imprecise: on Linux it reports *Repaired* whenever the exit code is `0`, so it is never *compliant*. On Windows, it reports an *Error* in audit mode because the command is not run.
- Use Rudder's native methods wherever possible (`file_*`, `package_*`, `service_*`, `user_*`, `permissions_*`, `registry_*`, etc.).
- If a command is unavoidable:
  - to check something, use an audit method such as `audit_from_command` (or `audit_from_powershell_execution` on Windows);
  - to change something, condition the command so it runs only when needed (see "Condition methods that perform an action").
- `condition_from_command` runs its command **even in audit mode**. Only use it with commands that do not change the system.

### Paths

#### Always use absolute paths, except for the shared folder

- All paths on the node must be absolute. Methods expect an "absolute path on the target node".
- Exceptions:
  - The `source` of `file_from_shared_folder` is relative to the shared folder (`/var/rudder/configuration-repository/shared-files` on the server).
  - Files shipped in the technique's `resources/` folder are referenced through `${resources_dir}`, e.g. `${resources_dir}/ntp.conf.j2`. Never hard-code their location.

#### Add a trailing slash to directory paths

When a path points to a directory, end it with `/` (e.g. `/etc/myapp/`). Some methods, like `directory_present`, accept both forms, but the trailing slash makes it obvious to the reader that the path is a directory.

### Security

#### Leave no secrets in techniques

- Never put infrastructure data (hostnames, IP addresses, URLs, account names, etc.) or secrets anywhere in a technique, its resources or its templates. Supply them through technique parameters.
- Use the `password` parameter type for Unix passwords, with `constraints.password_hashes` to restrict accepted hash formats.
- Keep secrets out of reports. Several methods include file content or diffs in compliance reports by default:
  - Set `show_content: "false"` on `file_from_template_options` and `file_augeas` when the file contains secrets (passwords, private keys, tokens, etc.).
  - Pass an explicit `data` object to template methods (see "Take external values from technique parameters"), so a template can never print values it was not meant to see.

### Parameters

#### Take external values from technique parameters

- Any value that comes from outside the technique must be passed in through a technique parameter. Do not read it directly from inside the technique, for example:
  - node properties or global properties (`node.properties[...]`);
  - variables or conditions set by other directives;
  - remote data such as HTTP endpoints.
- If the value comes from a node or global property, the directive can set the parameter's value to that property. The technique itself stays independent of where the value comes from.
- For templates, always pass an explicit, minimal `data` object to `file_from_template_options`. When `data` is empty, the template receives the **entire agent datastate**: every variable and condition of the run, across all directives, including secrets.
- This keeps the technique self-contained (see "Avoid dependencies between techniques") and makes its inputs explicit.

#### Use the appropriate type for each parameter

- Set a `type` that matches the expected values. The default is `multiline-string`, which accepts anything. Available types:
  - text: `string`, `multiline-string`;
  - structured: `json`, `yaml`;
  - scalar: `boolean`, `integer`, `size-b`, `size-kb`, `size-mb`, `size-gb`, `size-tb`;
  - network: `ip`, `ipv4`, `ipv6`, `mail`;
  - system: `permissions`, `shared-file`, `password`.
- Use `constraints` to restrict values further:
  - `select`: a fixed list of allowed values (an enum), each with an optional display `name`;
  - `regex`: a regular expression, with an `error_message` for the user;
  - `allow_empty`: whether an empty value is accepted.
- Give a `default` where a sensible one exists.
- Parameter types are enforced only for techniques deployed as built-in techniques. The technique editor accepts YAML techniques but does not support parameter types yet.

```yaml
params:
  - name: ntp_server
    description: "NTP server to synchronize with"
    type: string
    constraints:
      select:
        - value: "192.123.23.21"
          name: "DC1"
        - value: "192.123.22.21"
          name: "DC2"
  - name: max_log_size
    description: "Maximum size of the log file"
    type: size-mb
    default: "100"
```

---

## Recommended

### Conditions

Every method defines **result conditions** (also called *outcome conditions*) named `<method>_<canonified parameter>_<outcome>`, with the outcomes `ok`, `kept`, `repaired` and `error`. *Canonified* means every character other than `[a-zA-Z0-9_]` is replaced by `_`. For example, `file_from_template_options` with `destination: /etc/ntp.conf` defines `file_from_template_options__etc_ntp_conf_repaired` when it changes the file. The exact name is listed under **Outcome conditions** in each method's reference.

#### Condition methods that perform an action

Methods that perform an action should always be conditioned, so that they run only when needed. Typical examples:

- service restart or reload (`service_restart`, `service_reload`, `service_action`);
- command execution (`command_execution*`);
- PowerShell execution (`powershell_execution`).

The usual pattern is to restart a service only when its configuration was repaired:

```yaml
- name: "NTP configuration file"
  method: file_from_template_options
  params:
    destination: "/etc/ntp.conf"
    template_path: "${resources_dir}/ntp.conf.j2"
    engine: minijinja
    data: '{"server": "${ntp_configuration.ntp_server}"}'
- name: "Restart NTP when its configuration changed"
  method: service_restart
  condition: "file_from_template_options__etc_ntp_conf_repaired"
  params:
    name: "ntp"
```

#### Do not condition methods that define a state

Methods that define a state (`*_present`, `*_absent`, `service_started`, `service_enabled`, etc.) are idempotent. They usually do not need a condition, unless the state applies only to a specific system (e.g. `condition: "debian"`).

#### Keep it simple and avoid unnecessary conditions

It is usually fine for a method to fail when the conditions it needs are not met: the error is visible in compliance. You do not need extra conditions to prevent that.

#### Avoid chaining conditions unless necessary

Each link in a chain of conditions (method B runs on the result of method A, method C on the result of method B, etc.) depends on exact condition names. This makes the chain fragile (see "Check result conditions after changing a method parameter") and hard to read.

#### Use result conditions only where strictly necessary

Keep result conditions to a minimum, but still condition every method that performs an action.

#### Group methods that depend on the same condition in a block

If several methods depend on the same condition or conditions:

- put them in a block;
- set the `condition` on the block itself, not on each method.

```yaml
- name: "Debian-specific configuration"
  condition: "debian"
  items:
    - name: "..."
      method: ...
    - name: "..."
      method: ...
```

#### Check result conditions after changing a method parameter

- A method's result conditions are named after one of its parameters (the canonified `path`, `destination`, `name`, etc.). Changing that parameter's value renames the conditions. Any `condition` that refers to the old names silently stops matching, so check them all after the change.
- The prefix is not always the method's name. For example, `file_content` defines `file_lines_present_${path}_*`. Always use the name given under **Outcome conditions** in the method reference.
- With `foreach`, the condition names change with each iteration's values.

### File management

#### Template files instead of editing them

- Prefer templating or copying a whole file to editing parts of it. This approach:
  - is easier to maintain;
  - avoids partial application and undefined variables;
  - defines an absolute state, rather than one relative to the file's previous state.
- To template a file, use `file_from_template_options` with `engine: minijinja`:
  - Keep the template in the technique's `resources/` folder and reference it with `template_path: "${resources_dir}/<file>"`. Use `template_string` for short inline templates.
  - Pass an explicit `data` object and set `show_content: "false"` for sensitive files (see the "Security" and "Parameters" sections).
- Template engine:
  - **Use MiniJinja.** It is the default and preferred engine: native, fast, sandboxed, and available on Linux and Windows.
  - Avoid Mustache as it is less powerful.
  - Avoid Jinja2 as it requires a local Python.
  - `file_from_template_type` does not support MiniJinja, and `file_from_template_jinja2` and `file_from_template_mustache` select legacy engines, so do not use them for new techniques.
- To copy a file as-is, use `file_from_shared_folder` (files shared by all nodes) or `file_from_local_source` with a file from `${resources_dir}`.

#### Prefer Augeas when a file must be edited

If you cannot avoid editing a file, choose a method in this order:

1. **Augeas:** `file_augeas` (Linux, Rudder 8.3 and later), which supports audit mode through `check` expressions and an optional `if_script` guard; `file_augeas_set` to set a single value.
2. **Key-value edits**, where they fit: `file_key_value_present`, `file_keys_values_present`, `file_key_value_present_in_ini_section`, etc.
3. **Block enforcement**, where possible: `file_block_present`, `file_block_present_in_section`.
4. **`file_content`**, **only** for complex edits. Keep `enforce: "true"` in most cases. With `enforce: "false"`, lines are managed one by one and their order is not guaranteed.

Augeas methods are only available on Linux. On Windows, start at step 2 or 3, after checking that the chosen method lists Windows among its compatible targets.

#### Give managed and distributed files a precise header

This applies to distributed files, managed files and templated files.

- The header should say exactly where the file is managed, for example: *"This file is managed in whole by Rudder, rule X, directive Y. Do not edit it here."*
- You can use the `rudder_header` global property. Pass it in through a technique parameter, so the technique stays independent of where the value comes from.

### Reporting

#### Configure reporting so compliance is meaningful

Configure the `reporting` field of methods and blocks so that the technique's compliance reflects what the user actually cares about, not every internal step. Every remaining report should make sense to someone reading compliance without knowing how the technique is built.

- **Hide technical steps** (helper variables, preparation steps, temporary files, etc.) with `reporting: { mode: disabled }` on the method. Method reporting modes are `enabled` (default) and `disabled`.
- **Choose how each block computes its compliance** with `reporting.mode`:
  - `weighted` (default): weighted average of its direct children. The best choice in most cases.
  - `focus`: use the compliance of a single direct child, given by `reporting.id`. Use it when one item represents the block's outcome and the others are only preparation.
  - `focus-worst`: use the compliance of the worst direct child.
  - `disabled`: exclude the block from compliance entirely (weight 0).
  - `worst-case-weighted-sum` / `worst-case-weighted-one`: the worst status of all methods below the block, weighted by their number or counted once. These are rarely the best fit.
- Give blocks and methods `name`s that read well in reports.

```yaml
- name: "NTP is configured"
  reporting:
    mode: focus
    id: "<id of the template method>"
  items:
    - name: "Compute NTP settings"
      method: variable_string
      reporting:
        mode: disabled
      params: ...
    - name: "NTP configuration file"
      id: "<id of the template method>"
      method: file_from_template_options
      params: ...
```
