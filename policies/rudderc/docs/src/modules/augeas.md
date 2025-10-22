# Augeas module

## Language

The module's DSL is an extension of the [Augeas DSL](https://augeas.net/docs/augeas.html) with some Rudder-specific
features.
The specific features are audit-oriented, and implemented with a new `check` keyword.

### Types

The language provides a set of basic types:

* `ip`: This is a general type that can be used for both IPv4 and IPv6 addresses.
* `ipv4`: This type is specifically for IPv4 addresses.
* `ipv6`: This type is specifically for IPv6 addresses.
* `ip_range`: This type is for IP ranges, which can be either IPv4 or IPv6.
* `ipv4_range`: This type is specifically for IPv4 ranges.
* `ipv6_range`: This type is specifically for IPv6 ranges.
* `bytes`: This type is for size values, such as file sizes or memory sizes, and can be suffixed with `B`, `KB`, `MB`,
  `GB`, `KiB`, `MiB`, `GiB`, etc.
* `int`: This type is for integer values, which can be either positive or negative.
* `uint`: This type is for unsigned integer values, which can only be positive.
* `float`: This type is for floating-point numbers, which can be either positive or negative.
* `bool`: This type is for boolean values, which can be either `true` or `false`.

### Comparisons

The language supports value comparisons through multiple operators.

numeric comparison operators: <a name="numeric-comparison-operators"></a>

* `==` :  Equal
* `!=` :  Not equal
* `<=` :  Less than or equal
* `>=` :  Greater than or equal
* `<` :   Less than
* `>` :   Greater than

regex comparison operators:

* `~` :   Matches the regex pattern
* `!~` :  Does not match the regex pattern
* `eq` :  Equal
* `neq` : Not equal

Example:

```augeas
check /files/etc/hosts/1/canonical ~ localhost
```

### Values comparison

The `values` family of directives is used for comparison against multiples nodes.

#### `values include`

The `values include` directive is used to check if the provided data is included by a node.

Example:

```augeas
check /files/etc/hosts/1/* values include "localhost"
```
#### `values not_include`

The `values not_include` directive is used to check if the provided data is not included by a node.

Example:

```augeas
check /files/etc/hosts/1/* values not_include "rudder.io"
```

#### `values equal`

The `values ==` directive is used to compare the provided array against multiple nodes.

Example:

```augeas
check /files/etc/hosts/1/* values == ["localhost", "127.0.0.1"]
```

#### `values equal ordered`

The `values ===` directive is used to compare the provided array, in the exact order, against multiple nodes.

Example:

```augeas
check /files/etc/hosts/1/* values === ["127.0.0.1", "localhost"]
```

#### `values in`

The `values in` directive is used to check if the nodes values are in the provided allow list.

Example:

```augeas
check /files/etc/hosts/1/canonical values in ["127.0.0.1", "localhost"]
```

#### `values len`

The `values len` directive is used to check the number of values using an operator and a specified number.

For supported operators, refer to the [numeric comparison operators list](#numeric-comparison-operators).

Example:

```augeas
check /files/etc/hosts/1/* values len == 2
```

```augeas
check /files/etc/hosts/1/canonical values len == 1
```

### Length comparison

The `len` directive is used to check the length of a value using an operator
and a specified length.

For supported operators, refer to the [numeric comparison operators list](#numeric-comparison-operators).

Example:

```augeas
check /files/etc/hosts/1/canonical len == 9
```

### as_type

The `is` directive checks the type of a value against a specified type.

For supported types, refer to the [types section](#types).

```augeas
check /files/etc/hosts/1/ipaddr is ipv4
```

### in_ip_range

The `in_ip_range` directive checks whether an IP address or an IP range falls
within one of the specified IP address ranges.

It supports both IPv4 and IPv6 addresses in CIDR format.

```augeas
check /files/etc/hosts/1/ipaddr in_ip_range ["127.0.0.0/8"]
```

### Password checks

The `password` family of directives allows checking for password strength and
compliance with policies. They never output the password itself.

#### `password tluds`

The `password tluds` directive is used to define a password policy in Rudder, based on a minimal number
of character classes.

* total length
* number of lowercase letters
* number of uppercase letters
* number of digits
* number of special characters

Example:

```augeas
check /files/etc/config/pass password tluds 8 1 1 1 1
```

#### `password score`

The `password score` directive is used to check the strength of a password against a provided minimum security score.

It uses the [zxcvbn](https://www.usenix.org/conference/usenixsecurity16/technical-sessions/presentation/wheeler)
algorithm to compute the score, and outputs a value between 0 and 4.

Example:

```augeas
check /files/etc/config/pass password score 4
```
