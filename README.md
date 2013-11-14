# NCF

This repository contains the NCF framework.
This framework aims at easing the development and use of the configuration management tool CFEngine 3.

## Concepts

There are several levels in this framework, from 10 to 60, representing a layer, with each layer being a fundation for higher levels. The higher the lever, the higher the abstraction level.

  - __10_ncf_internals__: this directory contains the mechanics and glue to make the framework work. You should be very rarely modified, and is generic.
  - __20_cfe_basics__: this directory contains libraries that can be reused; most notably the CFEngine Standard Library (cfengine_stdlib.cf)
  - __30_generic_methods__: this directory contains reusable bundles, that performs unit tasks, and are completely generic.
  - __40_it_ops_knowledge__: this directory contains default values for services, like packages name of a specific service accross diferent distribution, path to binaries, default configuration values of services, etc
  - __50_techniques__: this directory contains Techniques, which are the know-how of configuring a services
  - __60_services__: this directory contains the configurations of services, with parameters and conditions. This level is specific for each organisation.

Each level contains at least 3 subfolders:
  1. __ncf__: the files shipped with the framework
  2. __local__: local imlementation, organisation specific. This folder is always ignored in git
  3. __d-c__: extension point for the framework, for the Design-Center

Each level uses items from the lower level (lower number) or from its own level.
