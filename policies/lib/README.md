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

  - __DRY__: You should never have to duplicate promises, or even promise patterns. This is the best way to make unmaintainable code.
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

Each level uses items from lower levels (lower numbers) or, in some cases, from its own level.

## Tests

### Quick tests

Quick tests are run using the avocado framework, which can be installed using:

    pip3 install --user avocado-framework

To add a test, simply add an executable file to the `tests/quick` folder. A lib folder is available in `tests/testlib` but is not
automatically imported, you will to import it manually in your new test if needed.

To run the tests:

    avocado run tests/quick
    avocado run tests/quick/test_ncf_api.py
