# Rudder: Continuous Auditing & Configuration

This project is part of Rudder, see [the main repository](https://github.com/Normation/rudder)
for more information.
 
## License

This sub-project is licensed under GPLv3 license, 
see the provided "LICENSE" file or 
http://www.gnu.org/licenses/gpl-3.0.txt

## Usage

This project is part of Rudder, and as such is bundled with every Rudder server.

## Synopsis

### A powerful and structured configuration management framework

ncf is designed with the following concepts throughout:

  - __DRY__: You should never have to duplicate promises, or even promise patterns. This is the best way to make unmaintanable code.
  - __KISS__: Keep everything Simple and Sweet :) This extends to having one bundle do one thing, but do it well. Avoid complexity.
  - __Minimal effort__: Reduce typing and syntax effort for everyday use as much as possible. Make the framework do the heavy lifting - code once, benefit forever! (aka "Lazy" :) )
  - __Intuitive__: Reading and writing configuration management rules with ncf should be self-evident. Clearly named bundles and conventions make this easy.
  - __Extensible__: You should be able to extend anything, add methods or support for new tools easily, without breaking anything.
  - __Open source__: We believe in sharing so that the world can build on each other's work, and continually improve. ncf is [distributed under the GPLv3 license on GitHub](https://github.com/normation/ncf/).

### Decoupled layers

There are several layers in this framework, from 10 to 60, where each layer is a foundation for higher levels. The higher the lever, the higher the abstraction level.

  - __10_ncf_internals__: This directory contains the mechanics and glue to make the framework work. This should be very rarely modified, and is generic.
  - __20_cfe_basics__: This directory contains libraries that can be reused; most notably the CFEngine Standard Library.
  - __30_generic_methods__: This directory contains reusable bundles, that perform unit tasks, and are completely generic (for example "file_create_symlink"). All generic methods are documented on the [reference page](https://docs.rudder.io/reference/current/reference/generic_methods.html).
  - __40_it_ops_knowledge__: This directory contains default values for services, like package names for a specific service across different distributions (aka "is it httpd or apache2?"), paths to binaries, default configuration values for services, etc.
  - __50_techniques__: This directory contains Techniques, which combine generic_methods and it_ops_knowledge to achieve system configurations you need. They may be generic ("Configure OpenSSH server") or specific ("Install and configure our in-house app"). The above example is a "technique".
  - __60_services__: This directory contains high-level service definitions, which it implements by calling individual techniques, with parameters and conditions. This level is specific for each organisation, and is intended to define services such as "Corporate web site" rather than "Apache config".

Each level uses items from lower levels (lower numbers) or, in some cases, from its own level.

