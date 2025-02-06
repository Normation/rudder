# Platform support for Rust modules

Last update: 2025-02-06

## Summary

| OS                     | Rust support | Rust build | Augeas module  | System updates module |
|------------------------|--------------|------------|----------------|-----------------------|
| RHEL < 7               | ❌            | ❌          | ❌              | ❌                     |
| RHEL 7                 | ✅            | ✅          | ❔  _(bindgen)_ | ✅                     |
| RHEL 8                 | ✅            | ✅          | ✅              | ✅                     |
| RHEL 9                 | ✅            | ✅          | ✅              | ✅                     |
| Amazon Linux 2         | ✅            | ✅          | ❔  _(bindgen)_ | ✅                     |
| Amazon Linux 2023      | ✅            | ✅          | ✅              | ✅                     |
| SLES < 12              | ❌            | ❌          | ❌              | ❌                     |
| SLES 12 < SP5          | ❌            | ❌          | ❌              | ❌                     |
| SLES 12 SP5            | ✅            | ✅          | ❔ _(bindgen)_  | ✅                     |
| SLES 15 GA             | ✅            | ✅          | ✅              | ✅                     |
| SLES 15 SP1            | ✅            | ✅          | ✅              | ✅                     |
| SLES 15 SP2            | ✅            | ✅          | ✅              | ✅                     |
| SLES 15 SP3            | ✅            | ✅          | ✅              | ✅                     |
| SLES 15 SP4            | ✅            | ✅          | ✅              | ✅                     |
| SLES 15 SP5            | ✅            | ✅          | ✅              | ✅                     |
| SLES 15 SP6            | ✅            | ✅          | ✅              | ✅                     |
| Debian < 8             | ❌            | ❌          | ❌              | ❌                     |
| Debian 8               | ✅            | ❌          | ❔ _(bindgen)_  | ❔ _(fallback-apt)_    |
| Debian 9               | ✅            | ❌          | ❔ _(bindgen)_  | ❔ _(fallback-apt)_    |
| Debian 10              | ✅            | ✅          | ✅              | ✅                     |
| Debian 11              | ✅            | ✅          | ✅              | ✅                     |
| Debian 12              | ✅            | ✅          | ✅              | ✅                     |
| Ubuntu < 14.04         | ❌            | ❌          | ❌              | ❌                     |
| Ubuntu 14.04           | ✅            | ❌          | ❔ _(bindgen)_  | ❔ _(fallback-apt)_    |
| Ubuntu 16.04           | ✅            | ❌          | ❔ _(bindgen)_  | ❔ _(fallback-apt)_    |
| Ubuntu 18.04           | ✅            | ✅          | ✅              | ✅                     |
| Ubuntu 20.04           | ✅            | ✅          | ✅              | ✅                     |
| Ubuntu 22.04           | ✅            | ✅          | ✅              | ✅                     |
| Ubuntu 24.04           | ✅            | ✅          | ✅              | ✅                     |
| Slackware < 14.1       | ❌            | ❌          | ❌              | ❌                     |
| Slackware >= 14.1      | ✅            | ❌          | ❔ _(bindgen)_  | ❔ _(unimplemented)_   |
| Windows < 10           | ❌            | ❌          | ❌              | ❌                     |
| Windows >= 10          | ✅            | ❌          | ❌              | ❔ _(unimplemented)_   |
| Windows Server < 2016  | ❌            | ❌          | ❌              | ❌                     |
| Windows Server >= 2016 | ✅            | ❌          | ❌              | ❔ _(unimplemented)_   |

Legend:

* ✅: Supported.
* ❔: Not supported yet, but could with some variable level of effort.
    * _(bindgen)_: Requires a newer version of LLVM on the builder.
    * _(unimplemented)_: Requires work on the module itself to add the feature.
    * _(fallback-apt)_: Requires a fallback through the APT CLI as we can't
      build the Rust
      bindings.
* ❌: Not supported, and will not.

## Requirements

As we have split modules, the support is not necessarily homogeneous.

### Rust support

Since 2022, the requirements for Rust support
are ([platform docs](https://doc.rust-lang.org/nightly/rustc/platform-support.html),
[announcement](https://blog.rust-lang.org/2022/08/01/Increasing-glibc-kernel-requirements.html)):

* Linux
    * Kernel 3.2+
    * glibc 2.17+
* Windows
    * 10+
    * Server 2016+

More specifically for Linux, this means:

* RHEL 7+
* SLES 12 SP5+
* Debian 8+
* Ubuntu 14.04 LTS+
* Slackware 14.1+

Systems not matching these requirements will not be considered in the rest of this document.

### Bindgen - LLVM

The `bindgen` crate, used to generate bindings for C and C++ libraries, relies on LLVM (`libclang` actually).
It is used both for the `augeas` and `system-updates/apt` modules.

The bindgen version used in `raugeas` is 0.70.1, which requires LLVM 6.0 or newer.
As it only a build dependency, we could install a newer LLVM version on unsupported builders.

### Systemd

We only support Linux systems with systemd (for everything related to services and system reboot management). This is
not blocking as the Rust requirements already rule out pre-systemd systems, except for things like Devuan, which _could_
work if a fallback was implemented.

The modules requiring systemd, for now only system-updates, are not available on Slackware.

### Apt

We use the `rust-apt` crate, which provides binding for the C++ `apt-pkg` library, which is used by APT itself, and by
the Python bindings used, for example, by `unnatended-upgrade` or Ansible.

There are two limits for older systems:

* The version of the `apt-pkg` C API. This is not the version of the APT program but the one of the underlying C++
  library.
    * We currently support 5.0.0 and newer versions ([MR](https://gitlab.com/volian/rust-apt/-/merge_requests/58))
* The C++ version used, which in turns requires a specific GCC version. The `cxx` crate requires C++11 and gcc < 5 does
  not fully support it.
    * We can only build on systems with GCC >= 5
    * The `apt-pkg` library also has requirements on the C++ version. Latest versions requires C++17.

| OS                | `apt-pkg` |
|-------------------|-----------|
| Ubuntu 16.04 LTS  | 5.0.0     |
| Ubuntu 18.04 LTS  | 5.0.2     |
| Debian 10         | 5.0.2     |
| Debian 11+        | 6.0.0     |
| Ubuntu 20.04 LTS+ | 6.0.0     |

| OS               | `gcc`         |
|------------------|---------------|
| Ubuntu 14.04 LTS | 4.8 (C++03)   |
| Debian 8         | 4.9 (C++03)   |
| Ubuntu 16.04 LTS | 5.4.0 (C++14) |
| Debian 9         | 6.3 (C++14)   |

Older systems could rely on a CLI-based fallback (which would work like the other package managers).

### Augeas

`raugeas` currently only support augeas 1.13.0 or newer ([issue](https://github.com/Normation/raugeas/issues/13)). On
systems where it is not present, we embed augeas into the agent.

We could imagine building augeas statically into the module
directly ([WIP](https://github.com/Normation/raugeas/pull/1)),
but this would require us to handle the modules distribution ourselves too,

The Windows support has apparently never been tested. Even if possible, it would probably require massive work. As
configuration files on Windows tend
to be simpler (often INI-like), it is not a priority.