# IO encoding in Rust modules

- Status: draft
- Deciders: AMO, FDA, MHA
- Date: 2026/03/09

## Context 

When reading from a file, all Rust modules expect the data to be valid UTF-8.
This requirement comes both from the language itself—since a Rust String is
expected to contain valid UTF-8—and from the serialization library used, which
also expects UTF-8 input. As a result, errors occur on Windows when the modules
encounter files encoded in UTF-16 with a
[BOM](https://en.wikipedia.org/wiki/Byte_order_mark). Additionaly Powershell
[requires](https://docs.microsoft.com/en-us/windows/desktop/intl/using-byte-order-marks)
a BOM added at the beginning of all files when using UTF-8 encoding.

## Decision

- Introduce the new `rudder_read_file` function.
  - This function behaves like `std::fs::read_to_string`, with encoding detection and conversion added.
  - It supports file encoded as UTF-8, UTF-8 with BOM and UTF-16 BOM LE.
  - Returns a valid UTF-8 string or an error if the file cannot be decoded.

- Introduce the new `rudder_write_file` function.
  - Takes as parameter the file path, the data string, and an enum specifying the target encoding.
  - All writes to disk should be atomic.
  - On Windows, files written by this function must include a BOM:
    - UTF-8 content, three-byte BOM: `EF BB BF`
    - UTF-16 LE content, two-byte BOM: `FF FE`

TODO: Should UTF-16 big-endian BOM (FE FF) also be supported?

## Consequences

TODO
