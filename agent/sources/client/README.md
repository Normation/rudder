# Rudder Client

Small binary acting as client for the Rudder communication protocol.

On Windows it is implemented as a Perl script.

We want to keep things simple and avoid complex C constructs.

Some conventions are inspired by Rust when possible.

## Docs

Style/Best practices guides:

* [How to C in 2016](https://matt.sh/howto-c)
* [C Style, Malcolm Inglis](https://github.com/mcinglis/c-style)

Online book to learn more about (modern) C:

* [Modern C, Jens Gustedt](https://modernc.gforge.inria.fr/)

## Dependencies

* `libcurl` built with tls support
* Tests builds require recent gcc, libasan, libubsan, and the llvm toolchain

## License

* `log.c` and `log.h` come from [rxi/log.c](https://github.com/rxi/log.c) (under MIT license)
* `argtable3.c` and `argtable3.h` come from [argtable/argtable3](https://github.com/argtable/argtable3) (under BSD license)

This program is available under GPLv3+.
