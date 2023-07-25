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
* `documentation` (optional): Documentation in Markdown format.
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
    * `md5-crypt-aix`: md5-crypt hash for AIX.
    * `sha256-crypt-aix`: sha256-crypt hash for AIX.
    * `sha512-crypt-aix`: sha512-crypt hash for AIX.

Example:

```yaml
parameters:
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
* `condition` (optional): A condition expression for the whole block
* `reporting` (optional)
  * `mode`
    * `weighted` (default)
    * `worst-case-weighted-sum`: Take the worst outcome from the block and 
    * `worst-case-weighted-one`: Take the worst outcome from as the block as if it was a singe method
    * `focus`: Apply the outcome of one of the included methods to the whole block, requires passing the `id` value
    * `disabled`: No reporting
  * `id` (required with `focus` mode): id of the method to focus reporting on.

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
* `name` (optional): Name used in reporting, identifying what the method does. Uses the method name by default.
* `tags` (optional): Optional key-value tags.
* `params`: Key-Value dictionary of parameters for the method.
* `condition` (optional): A condition expression for the method
* `reporting` (optional)
  * `mode` 
    * `enabled` (default): Normal reporting
    * `disabled`: No reporting

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