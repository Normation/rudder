# Technique syntax

## General organization

A technique is made of:

* General metadata: its name, version, documentation, etc.
* Parameters it can take
* Resource file that can be attached
* A method tree (made of block and leaf methods)

## Technique

* `id`: Unique identifier of the technique. Must only contain alphanumeric or underscore characters.
* `name`: Human-readable name of the technique
* `version`: Version in the X.Y format.
* `description` (optional): Single line description of what the technique does.
* `documentation` (optional): Documentation in Markdown format.
* `tags` (optional): Optional key-value tags.
* `category` (optional): Rudder category to put the technique in.
* `parameters` (optional): A list of parameters. See below for details.
* `items`: A list of items (block or method call). Cannot be empty. See below for details.

Example:

```yaml
---
id: "ntp"
name: "NTP configuration"
version: "1.0"
description: "This technique configures the local ntp service"
documentation: "**Warning**: it installs
                the [chrony](https://chrony.tuxfamily.org/) service."
```

## Parameters

Each parameter contains the following fields:

* `id`: UUID identifying the parameter.
* `name`: Name of the parameter. Must only contain alphanumeric or underscore characters.
* `description` (optional): Single line description of what the parameter does.
* `type` (optional): The type of the parameter
  * `String` (default): A normal string.
  * `HereString`
* `may_be_empty` (optional): Whether an empty value is acceptable for this parameter.

Example:

```yaml
parameters:
  - id: 3439bbb0-d8f1-4c43-95a9-0c56bfb8c27e
    name: dns_server
    description: "The DNS server hostname"
    may_be_empty: true
```

## Blocks

Blocks contains:

* `id`: UUID identifying the block.
* `name`
* `tags` (optional): Optional key-value tags.
* `items`
* `condition` (optional)
* `reporting` (optional)
  * `worst-case-weighted-sum`
  * `worst-case-weighted-one`
  * `weighted`
  * `focus` + `id`
  * `disabled`

## Methods

Methods contains:

* `method`: Method technical name (also called "Technique ID").
* `id`: UUID identifying the method.
* `name`: Name used in reporting, identifying what the method does.
* `tags` (optional): Optional key-value tags.
* `params`: Key-Value dictionary of parameters for the method.
* `condition` (optional)
* `reporting` (optional)
  * `enabled` (default)
  * `disabled`

Example:

```yaml
items:
  - name: "Ensure telnet-server absence"
    id: d86ce2e5-d5b6-45cc-87e8-c11cca71d907
    tags:
      cve: CVE-2022-3456
    condition: "debian"
    method: package_absent
    params:
      name: "telnet-server"
```