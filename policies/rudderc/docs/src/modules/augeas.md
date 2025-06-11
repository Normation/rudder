# Augeas module

## Language

The module's DSL is an extension of the [Augeas DSL](https://augeas.net/docs/augeas.html) with some Rudder-specific
features.
The specific features are audit-oriented, and implemented with a new `check` keyword.

### Comparisons

The supported comparison types are:

### Type checks

The known types are:

* `ip`: This is a general type that can be used for both IPv4 and IPv6 addresses.
* `ipv4`: This type is specifically for IPv4 addresses.
* `ipv6`: This type is specifically for IPv6 addresses.
* `ip_range`: This type is for IP ranges, which can be either IPv4 or IPv6.
* `iv4_range`: This type is specifically for IPv4 ranges.
* `ipv6_range`: This type is specifically for IPv6 ranges.
* `bytes`: This type is for size values, such as file sizes or memory sizes, and can be suffixed with `B`, `KB`, `MB`,
  `GB`, `KiB`, `MiB`, `GiB`, etc.
* `int`: This type is for integer values, which can be either positive or negative.
* `uint`: This type is for unsigned integer values, which can only be positive.
* `float`: This type is for floating-point numbers, which can be either positive or negative.
* `bool`: This type is for boolean values, which can be either `true` or `false`.

### List comparisons

### Password checks

These checks allow checking for password strength and compliance with policies.
They never output the password itself.

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
check /files/etc/config/pass tluds 8 1 1 1 1
```

#### `password score`

The `password score` directive is used to check the strength of a password based on a scoring system.

It uses the [zxcvbn](https://www.usenix.org/conference/usenixsecurity16/technical-sessions/presentation/wheeler)
algorithm
to compute the score, and outputs a value between 0 and 4.
