# NCF

This repository contains the NCF framework.
This framework aims at easing the development and use of the configuration management CFEngine 3.

## Concepts

There are several levels in this framework, from 10 to 60, representing a layer, with each layer being a fundation for higher levels. The higher the lever, the higher the abstraction level.
1. 10_ncf_internals: this directory contains the mechanics and glue to make the framework work. You should be very rarely modified, and is generic.
2. 20_cfe_basics: this directory contains libraries that can be reused; most notably the CFEngine Standart Library (cfengine_stdlib.cf)
3. 30_generic_methods: this directory contains reusable bundles, that performs unit tasks, and are completely generic.
4. 40_it_ops_knowledge: this directory contains default values for services, like packages name of a specific service accross diferent distribution, path to binaries, default configuration values of services, etc
5. 50_techniques: this directory contains Techniques, which are the know-how of configuring a services
6. 60_services: this directory contains the configurations of services, with parameters and conditions. This level is specific for each organisation.

Each level contains at least 3 subfolders:
1. ncf: the files shipped with the framework
2. local: local imlementation, organisation specific. This folder is always ignored in git
3. d-c: extension point for the framework, for the Design-Center

Each level uses items from the lower level (lower number) or from its own level.

