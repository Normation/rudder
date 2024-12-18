# Technique syntax

## General organization

A technique is made of:

* General metadata: its name, version, documentation, etc.
* Parameters it can take
* Resource file that can be attached
* A method tree (made of blocks and leaf methods)

## Technique

* `id`: Unique identifier of the technique. Must only contain alphanumeric or underscore characters.
* `name`: Human-readable name of the technique
* `version`: Version in the X.Y format.
* `description` (optional): Single line description of what the technique does.
* `documentation` (optional): Documentation in Markdown format.
* `tags` (optional): Optional key-value tags.
* `category` (optional): Rudder category to put the technique in.
* `params` (optional): A list of parameters. See below for details.
* `items`: A list of items (block or method call). Cannot be empty. See below for details.

Example:

```yaml
id: "ntp"
name: "NTP configuration"
version: "1.0"
description: "This technique configures the local ntp service"
documentation: "**Warning**: it installs
                the [chrony](https://chrony.tuxfamily.org/) service."
```

## Parameters

The parameters of the technique will be rendered in the directive form.
Each parameter contains the following fields:

* `id` (optional): UUID identifying the parameter.
* `name`: Name of the parameter. Must only contain alphanumeric or underscore characters.
* `description` (optional): Single line description of what the parameter does.
* `documentation` (optional): Documentation (plain text, *not* in Markdown format).
* `type` (optional): The type of the parameter, can be:
  * `string`: A simple string.
  * `multiline-string` (default): A multiline string (displayed in a `textarea`).
  * `json`: A JSON value.
  * `yaml`: A YAML value.
  * `boolean`: A boolean value.
  * `mail`: A valid email address.
  * `ip`: A valid IP address.
  * `ipv4`: A valid IPv4 address.
  * `ipv6`: A valid IPv6 address.
  * `integer`: An integer.
  * `size-b`, `size-kb`, `size-mb`, `size-gb`, `size-tb`: A size in a given unit (B, kB, MB, GB, TB).
  * `permissions`: Permissions applicable to a file.
  * `shared-file`: A file in the server's `shared-files` folder.
  * `password`: A password value for a Unix system (in `/etc/shadow` format), which provides specific behavior. See the `password_hashes` constraint.
* `default` (optional): The default value of the parameter.
* `constraints` (optional): Additional restrictions on the value.
  * `allow_empty` (_bool_, optional): Whether an empty value is acceptable for this parameter.
  * `regex` (optional): Restricts allowed value with a regular expression. Defined with:
    * `value`: The regular expression.
    * `error_message` (optional): A message to give the user in case the value does not match.
  * `select` (optional): Allows restricting possible values to a given set. Defined as:
    * An array of:
      * `value`: The associated value
      * `name` (optional): The displayed name (`value`'s value by default)
  * `password_hashes` (optional): A comma-separated list of password hashes types to accept in a `password` typed field. By default,
                                  only accepts pre-hashed values or sha2-crypt algorithms. Available values:
    * `pre-hashed`: A pre-hashed value in the `/etc/shadow` format.
    * `plain`: Plain text password, which will not be modified.
    * `unix-crypt-des`: DES crypt hash.
    * `md5`: Simple md5 hash.
    * `sha1` Simple sha1 hash.
    * `sha256` Simple sha256 hash.
    * `sha512` Simple sha512 hash.
    * `md5-crypt`: md5-crypt hash.
    * `sha256-crypt`: sha256-crypt hash.
    * `sha512-crypt`: sha512-crypt hash.

Example:

```yaml
params:
  - name: dns_server
    description: "The DNS server hostname"
    default: "1.1.1.1"
    constraints:
      allow_empty: true
  - name: ntp_server
    constraints:
      select:
        - value: "192.123.23.21"
          name: "DC1"
        - value: "192.123.22.21"
          name: "DC2"
```

## Blocks

Blocks contains:

* `id` (optional): UUID identifying the block.
* `name`: Human-readable name of the block
* `tags` (optional): Optional key-value tags.
* `items`: A list of items (block or method call). Cannot be empty.
* `condition` (optional): A condition expression for the whole block. `true` is an always defined (default), `false` is never defined.
* `policy_mode_override` (optional):
  * `none`(default): Use the policy mode from parent container (or directive if no override)
  * `enforce`: Force the policy mode of all items within the block to enforce mode.
  * `audit`: Force the policy mode of all items within the block to audit mode.
* `reporting` (optional):
  * `mode`:
    * A block's compliance is computed from the compliance of all its (first-level) components. The components (whether blocks, blocks with sub-clocks, methods, etc.) are seen as black boxes with their own compliance, and a weight (by default, each method carries a weight of 1). There are several options to compute the resulting compliance, i.e., percents plus weight, from these components.
        * `weighted` (default): weighted average, the best choice in most cases.
        * `focus`: Select a pre-defined component's compliance (including weight) and use it as compliance for the whole block. Requires passing the `id` value, which must the ID of a direct block subitem (block or method).
        * `focus-worst`: Select the worst component's compliance (including weight) and use it as compliance for the whole block.
        * `disabled`: Totally removes the block from compliance computation (i.e., attribute it a weight of 0).
    * There is another type of computations, which are rarely the most suited. In these cases, the block's compliance is computed from its leaf methods, regardless of the block's tree structure.
      * `worst-case-weighted-sum`: Select the worst status from all methods, and use it as the block compliance, with a weight equal to the total number of methods below the block.
      * `worst-case-weighted-one`: Select the worst status from all methods, and use it as the block compliance, with a weight of 1.
  * `id` (required with `focus` mode): ID of the method to focus reporting on.
* `foreach` (optional): List of dictionaries, repeats the block for each items in the list and each time, replace every occurrence of `${item.x}` in the subitems, condition by the corresponding value in the dictionary.
* `foreach_name` (optional): Name of the local iterator variable to use if a `foreach` loop is used. Defaults to `item`.

<div class="warning">
Setting <code class="hljs">policy_mode_override</code> to <code class="hljs">enforce</code> will <strong>bypass the audit mode</strong>, so it must only be used
for actions that <strong>do not modify the system</strong> and are required for proper audit mode operation (e.g.
writing a temporary file to compare its content with the system).
</div>

<div class="warning">Policy mode effective value will always be the closest override layer, meaning an overridden policy mode on a method call
will always prevail over directives and blocks values.</div>

```yaml
items:
  - name: "Ensure telnet-server absence"
    tags:
      cve: CVE-2022-3456
    condition: "debian"
    reporting:
      mode: worst-case-weighted-one
    items:
      - ...
      - ...
```

## Methods

Methods contains:

* `method`: Method technical name (also called "Technique ID").
* `id` (optional): UUID identifying the method.
* `name` (optional): Name used in reporting, identifying what the method does. It uses the method name by default.
* `tags` (optional): Optional key-value tags.
* `params`: Key-Value dictionary of parameters for the method.
* `condition` (optional): A condition expression for the method. `true` is an always defined (default), `false` is never defined.
* `policy_mode_override` (optional):
  * `none` (default): Inherit policy mode from parent container (ore directive if no override)
  * `enforce`: Force the policy mode to enforce mode.
  * `audit`: Force the policy mode to audit mode.
* `reporting` (optional)
  * `mode`
    * `enabled` (default): Normal reporting
    * `disabled`: No reporting

<div class="warning">
Setting <code class="hljs">policy_mode_override</code> to <code class="hljs">enforce</code> will <strong>bypass the audit mode</strong>, so it must only be used
for actions that <strong>do not modify the system</strong> and are required for proper audit mode operation (e.g.
writing a temporary file to compare its content with the system).
</div>

The methods are documented in the next section of this documentation, sorted by category.

Example:

```yaml
items:
  - name: "Ensure telnet-server absence"
    tags:
      cve: CVE-2022-3456
    condition: "debian"
    method: package_absent
    params:
      name: "telnet-server"
    reporting:
      mode: disabled
```

## Foreach

Blocks and methods can be executed multiple times using the `foreach` field. Each iteration can be parameterized using a simple templating to fill the `params`, `condition` or `items` fields of the block|method being executed.
The templating is done by using a local variable, replaced at technique compilation using the syntax `${<iterator name>.<key>}`.
The `<iterator name>` is by default named `item` and can be changed using the `foreach_name` field, which is useful when nesting looping blocks.

* The `foreach_name` must follow the pattern `^[a-zA-Z0-9_]+$`.

For example

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

and

```yaml
  - name: "Install the '${tools.pkg_name}' package"
    method: package_present
    params:
      name: "${tools.pkg_name}"
      version: "${tools.version}"
    foreach:
      - pkg_name: "vim"
        version: "latest"
      - pkg_name: "htop"
        version: "latest"
```

would both be strictly equivalent to

```yaml
  - name: "Install the vim package"
    method: package_present
    params:
      name: "vim"
      version: "latest"

  - name: "Install the 'htop' package"
    method: package_present
    params:
      name: htop"
      version: "latest"
```

Foreach loops can also be nested when used on blocks. If it is the case, the replacement is done from top to bottom.
Make sure to rename the foreach iterator variable to avoid any conflict.

Example:

```yaml
items:
  - name: "Deploy utility files for ${user.login}"
    foreach_name: "user"
    foreach:
      - login: "bob"
      - login: "alice"
    items:
      - name: "Deploy file ~/${file.path}"
        method: file_from_shared_folder
        params:
          hash_type: sha256
          source: "${file.path}"
          path: "/home/${user.login}/${file.path}"
        foreach_name: "file"
        foreach:
          - path: ".vimrc"
          - path: ".bashrc"
```

would resolve to

```yaml
items:
  - name: "Deploy utility files for bob"
    items:
      - name: "Deploy file ~/.vimrc"
        method: file_from_shared_folder
        params:
          hash_type: sha256
          source: ".vimrc"
          path: "/home/bob/.vimrc"
      - name: "Deploy file ~/.bashrc"
        method: file_from_shared_folder
        params:
          hash_type: sha256
          source: ".bashrc"
          path: "/home/bob/.bashrc"
  - name: "Deploy utility files for alice"
    items:
      - name: "Deploy file ~/.vimrc"
        method: file_from_shared_folder
        params:
          hash_type: sha256
          source: ".vimrc"
          path: "/home/alice/.vimrc"
      - name: "Deploy file ~/.bashrc"
        method: file_from_shared_folder
        params:
          hash_type: sha256
          source: ".bashrc"
          path: "/home/alice/.bashrc"
```

## Resources

Files can be attached to a technique, they will automatically be deployed in the policies when used on a node.
The absolute path of the folder containing the resource files is accessible from within a technique using the variable `${resources_dir}`.

To add resources to a YAML technique, put the files under a `resources` folder in the technique directory.
In the example below, the `file1.txt` will be available from within the technique using `${resources_dir}/file1.txt`.

```bash
my_technique
├── resources
│   └── file1.txt
├── technique.yml
└── tests
```
