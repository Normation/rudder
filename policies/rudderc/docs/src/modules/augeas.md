# Augeas module

## Language

The module's DSL is an extension of the [Augeas DSL](https://augeas.net/docs/augeas.html) with some Rudder-specific
features.
The specific features are audit-oriented, and implemented with a new `check` keyword.

### Comparisons

The supported comparison types are:

### Type checks

The known types are:

* `ip`
* `ipv4`
* `ipv6`
* `ip_range`
* `iv4_range`
* `ipv6_range`
* `bytes`
* `int`
* `uint`
* `float`
* `bool`

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
