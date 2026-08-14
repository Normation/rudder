# Inventory module

A Rudder-specific inventory, not a reimplementation of FusionInventory. The output uses the
element names of the FusionInventory format, because that is what the Rudder server parses and it
makes both outputs comparable side by side. That is where the resemblance stops: **we produce only
what the server actually reads**, so our inventory is a strict subset of a FusionInventory one, and
we diverge from its behavior where that is the better answer.

Non-goals: replacing a _real_ inventory tool, inventorying hardware components (memory slots,
controllers, ports, sound or video cards, batteries, peripherals), or producing anything the server
does not use.

## What it reads

**No command is required**: one that is missing costs only what it feeds. A run fails only on the
files that identify the node.

| Command | Feeds | Without it |
| --- | --- | --- |
| `hostname --fqdn` | `OPERATINGSYSTEM/FQDN`, `RUDDER/HOSTNAME` | the short hostname is used |
| `last` | `HARDWARE/{LASTLOGGEDUSER,DATELASTLOGGEDUSER}` | left out |

| Path | Feeds | Without it |
| --- | --- | --- |
| `/opt/rudder/etc/uuid.hive` | `RUDDER/UUID` | **the run fails** |
| `/opt/rudder/etc/ssl/agent.cert` | `RUDDER/AGENT/AGENT_CERT` | **the run fails** |
| `/var/rudder/cfengine-community/rudder-server-uuid.txt` | `RUDDER/AGENT/POLICY_SERVER_UUID` | **the run fails** |
| `/var/rudder/cfengine-community/policy_server.dat` | `RUDDER/AGENT/POLICY_SERVER_HOSTNAME` | **the run fails** |
| `/etc/os-release`, then `/usr/lib/os-release` | `OPERATINGSYSTEM/{NAME,VERSION,FULL_NAME}` | a generic `Linux`, with a warning |
| `TZ`, then `/etc/localtime` | `OPERATINGSYSTEM/TIMEZONE`, the local time of `ACCESSLOG/LOGDATE` | left out, times fall back to UTC |
| `/sys/devices/virtual/dmi/id/product_uuid` | `HARDWARE/UUID` | left out |

The rest comes from `sysinfo` (`/proc/cpuinfo` and `/proc/stat` for `CPUS`, `/proc/meminfo` for
`HARDWARE/{MEMORY,SWAP}`, `/etc/passwd` for `LOCAL_USERS`), and `uname`, `gethostname` and the
`geteuid` behind `RUDDER/AGENT/OWNER` through `nix`.

Unix only, and only Linux is exercised. There is no Windows inventory yet, so nothing here carries a
Windows branch: whoever adds one adds it deliberately rather than inheriting a path nothing has run.

## What it does not produce

* **Out of scope**, being hardware components: `BATTERIES`, `CONTROLLERS`, `INPUTS`, `MEMORIES`,
  `PORTS`, `SLOTS`, `SOUNDS`, `STORAGES`, `USBDEVICES`, `VIDEOS`. Plus `VERSIONPROVIDER`, which
  describes the Perl interpreter running the agent.
* **Read by nothing on the server**: `REQUEST/QUERY`; `LOCAL_GROUPS`; `HARDWARE/{DEFAULTGATEWAY,DNS,IPADDR,OSNAME,PROCESSORN,PROCESSORT}`;
  `LOCAL_USERS/{HOME,ID,SHELL}`; `OPERATINGSYSTEM/{BOOT_TIME,DNS_DOMAIN,HOSTID,SSH_KEY}`;
  `RUDDER/{AGENT/CFENGINE_KEY,SERVER_ROLES}`.
* **Redundant**, the server reading them only as a fallback for something we always produce:
  `HARDWARE/OSVERSION` (see `OPERATINGSYSTEM/KERNEL_VERSION`), `HARDWARE/ARCHNAME`
  (`OPERATINGSYSTEM/ARCH`), `LOCAL_USERS/NAME` (`LOCAL_USERS/LOGIN`).
* **Windows-only**, with nothing to report on Unix: `HARDWARE/{USERDOMAIN,WINCOMPANY,WINPRODID,WINPRODKEY}`.
* **Not produced yet**, the server reading both: the `PROCESSES` section, and `HARDWARE/VMSYSTEM`,
  which says how the machine is virtualized and which it reads as a physical machine when absent.

## Differences from FusionInventory

### Dates and timezone

Every date comes from `jiff`, in the local time FusionInventory reports it in, and the name and
the offset of `OPERATINGSYSTEM/TIMEZONE` are read from the one zone, so they cannot disagree.

The seventeen names the zone database links to `UTC` (`Etc/UTC`, `GMT`…) are reported as `UTC`.
`jiff` answers what the machine is really set to, but FusionInventory resolves the links through
`DateTime::TimeZone`, and the server stores the name verbatim: two agents disagreeing would move a
node's timezone in the interface depending on which one ran. The other 232 links are region renames
we leave alone, so `Asia/Calcutta` stays itself where FusionInventory says `Asia/Kolkata`. Note also
that we read the zone database installed on the machine, where `DateTime::TimeZone` carries its own:
a zone whose file a distribution has moved to a legacy package leaves the element out for us.

### `OPERATINGSYSTEM/VERSION` is the number, with the SUSE service pack split out

FusionInventory reports the version out of one of two modules: `Distro::LSB` gives the bare
`Release:` of `lsb_release` (`26.04`, not `26.04 LTS (Resolute Raccoon)`), and `Distro::NonLSB`,
which runs on SUSE and Oracle, gives the `VERSION` of `/etc/os-release`, where SUSE carries its
service pack as `15-SP5`, and splits the two apart.

We read `/etc/os-release` only, so we take `VERSION_ID` — the number `lsb_release -r` prints — and
`VERSION` when it names a service pack or when there is no `VERSION_ID`. Both agents then report
`26.04` for Ubuntu 26.04, and `15` with a `SERVICE_PACK` of `5` for SLES 15 SP5. Two of our
FusionInventory patches exist only to reach that SUSE behavior, and reading the file directly needs
no equivalent; neither do we need the `PATCHLEVEL` of `/etc/SuSE-release`, as a machine without
`/etc/os-release` is one we do not run on.

### `HARDWARE/UUID` is read from DMI, not from `dmidecode`

It is the motherboard UUID `sysinfo` reads straight out of DMI, where FusionInventory runs
`dmidecode`. It is how a virtual machine is told apart from a clone of itself. On Linux it is
`product_uuid`, readable by root only, as the agent is; anyone else gets no UUID rather than a
failure. The placeholders firmware writes instead of leaving a field out (`Not Specified`,
`To Be Filled By O.E.M.`…) are dropped, using FusionInventory's own list, the one
`getDmidecodeInfos` skips a value on in `Tools/Generic.pm`, so both agents stay silent about the
same fields.

### The last login is dated by parsing it, not by counting columns

`HARDWARE/{LASTLOGGEDUSER,DATELASTLOGGEDUSER}` come from the most recent line of `last`, leaving out
the records of the machine starting and stopping — and its footer naming the file, where
FusionInventory reports a user called `wtmp` on a machine nobody has logged into. The columns before
the date vary, so it is found by handing every four consecutive fields to `jiff` and keeping the
first that parses as `%a %b %e %H:%M`, rejoined with single spaces to give the `EEE MMM dd HH:mm`
the server reads. `last` prints no year, so there is no real date to build: a login whose date does
not parse is reported without one rather than under a date the server would refuse.

### `OPERATINGSYSTEM/FQDN` and `RUDDER/HOSTNAME` hold the same value

Both are `hostname --fqdn`, falling back to the short hostname. FusionInventory resolves
`RUDDER/HOSTNAME` that way too, but takes `OPERATINGSYSTEM/FQDN` from Perl's
`Net::Domain::hostfqdn()`, which can answer a different domain for the same machine. The server
only uses `OPERATINGSYSTEM/FQDN` as a fallback for `RUDDER/HOSTNAME` anyway.

## Logging

To standard error through `tracing`, with the shared `rudder_cli` setup, so the output matches the
other Rudder command line tools.

| Invocation | Level |
| --- | --- |
| `--quiet` | warnings and errors |
| default | one line per run, saying what was written where |
| `-d` | what each section found, and why one is empty |
| `-dd` | everything |

Each section is a module, so `RUST_LOG` can raise one on its own
(`RUST_LOG=rudder_module_inventory::hardware=debug`), though the command line flag wins for the
global level.

Which level a missing value lands on follows one rule: **something absent is `-d`, something present
that did not work is a warning.** No `last` installed, or no DMI to read the machine UUID from as
anyone but root, is how the machine is and shows only under `-d`. A `last` that is installed and
fails, output it prints that holds no date we can parse, a `uname` that errors, an unresolvable
fully qualified name — those are the administrator's business and show without any flag.
