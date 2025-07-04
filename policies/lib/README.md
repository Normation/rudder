# Policies library

## Usage

This project is part of Rudder, and as such is bundled with every Rudder server.

### Decoupled layers

There are several layers in this framework, from 10 to 60, where each layer is a foundation for higher levels. The higher the lever, the higher the abstraction level.

  - __10_ncf_internals__: This directory contains the mechanics and glue to make the framework work. This should be very rarely modified, and is generic.
  - __20_cfe_basics__: This directory contains libraries that can be reused; most notably the CFEngine Standard Library.
  - __30_generic_methods__: This directory contains reusable bundles, that perform unit tasks, and are completely generic (for example "file_create_symlink"). All generic methods are documented on the [reference page](https://docs.rudder.io/reference/current/reference/generic_methods.html).

Each level uses items from lower levels (lower numbers) or, in some cases, from its own level.
