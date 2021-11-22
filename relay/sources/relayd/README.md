# relayd

## Install

### Dependencies

Runtime dependencies are:

* libpq

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

## Development database

Schema for the database is in: `webapp/sources/rudder/rudder-core/src/main/resources/reportsSchema.sql`
