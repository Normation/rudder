# Augeas

Augeas module type.

## Description

This module provides a type to manage configuration files using Augeas.
It uses the C API through Rust bindings.

## Usage

There are different ways to use this module:

* To set the contents of a file, by passing a `path` (and optionally a `lens`) and `changes`.

* To audit the contents of a file, by passing a `path` (and optionally a `lens`) and `checks`.

* By passing `commands`. This is the most flexible way to use the module, but it also bypasses all safeguards
  and makes reporting less precise. It exposes the full power of Augeas (but also the full danger).
  Use with caution.
