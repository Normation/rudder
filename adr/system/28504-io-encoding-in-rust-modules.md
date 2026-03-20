# Disk I/O encoding in Rust modules

- Status: accepted
- Deciders: AMO, FDA, MHA
- Date: 2026/03/09

## Context

When reading from a file, all Rust modules expect the data to be valid UTF-8.
This requirement comes from the language itself, as a Rust String is expected
to contain valid UTF-8, and from the serialization library used, which
also expects UTF-8 input.

As a result, errors occur on Windows when the modules encounter files encoded
in UTF-16 with a [BOM](https://en.wikipedia.org/wiki/Byte_order_mark).
Additionaly Powershell
[requires](https://docs.microsoft.com/en-us/windows/desktop/intl/using-byte-order-marks)
a BOM added at the beginning of all files when using UTF-8 encoding.

## Decision

Introduce two new functions for interacting with files.

- `rudder_read_file`
  - This function behaves like `std::fs::read_to_string`, with encoding detection and conversion added.
  - It supports file encoded as UTF-8, UTF-8 with BOM and UTF-16 BOM LE.
  - It returns a valid UTF-8 string or an error if the file cannot be decoded.

- `rudder_write_file`
  - Takes as parameter the file path, the data string in UTF-8, and an enum specifying the target encoding.
  - It supports writing files as UTF-8, UTF-8 with BOM and UTF-16 BOM LE.
  - All writes to disk are atomic.
  - On Windows, files written by this function include a BOM:
    - UTF-8 content, a three-byte BOM: `EF BB BF`
    - UTF-16 LE content, a two-byte BOM: `FF FE`

## Consequences

The new functions must be used when performing I/O operations on files.
