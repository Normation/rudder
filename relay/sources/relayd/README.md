# relayd

## Install

### Dependencies

Runtime dependencies are:

* openssl
* libpq
* zlib
* lzma

To install build dependencies on Debian/Ubuntu:

```bash
sudo make apt-dependencies
```

To install build dependencies on RHEL/Fedora:

```bash
sudo make yum-dependencies
```

### Installation

To install:

```bash
make DESTDIR=/target/directory install
```

This project requires at least Rust 1.34.

## Development database

Schema for the database is in: `webapp/sources/rudder/rudder-core/src/main/resources/reportsSchema.sql`
