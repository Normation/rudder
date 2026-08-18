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

**No command is required**: the commands are optional and allow adding more information.

| Command | Feeds | Without it |
| --- | --- | --- |
| `hostname --fqdn` | `OPERATINGSYSTEM/FQDN`, `RUDDER/HOSTNAME` | the short hostname is used |
| `last` | `HARDWARE/{LASTLOGGEDUSER,DATELASTLOGGEDUSER}` | left out |
| `dmidecode -t 4`, which needs root | `CPUS/{ID,FAMILYNAME}` | those two elements are left out |

| Path | Feeds | Without it |
| --- | --- | --- |
| `/opt/rudder/etc/uuid.hive` | `RUDDER/UUID` | **the run fails** |
| `/opt/rudder/etc/ssl/agent.cert` | `RUDDER/AGENT/AGENT_CERT` | **the run fails** |
| `/var/rudder/cfengine-community/rudder-server-uuid.txt` | `RUDDER/AGENT/POLICY_SERVER_UUID` | **the run fails** |
| `/opt/rudder/etc/agent-capabilities` | `RUDDER/AGENT_CAPABILITIES` | no capability is reported |
| `/opt/rudder/share/versions/rudder-agent-version` | `RUDDER/AGENT_VERSION` | the version of this module is reported instead, with a warning |
| `/var/rudder/hooks.d/`, and the hooks in it | `RUDDER/CUSTOM_PROPERTIES` | the element is left out |
| `/etc/os-release`, then `/usr/lib/os-release` | `OPERATINGSYSTEM/{NAME,VERSION,FULL_NAME}` | a generic `Linux`, with a warning |
| `TZ`, then `/etc/localtime` | `OPERATINGSYSTEM/TIMEZONE`, the local time of `ACCESSLOG/LOGDATE` | left out, times fall back to UTC |
| `/proc/cpuinfo` | `CPUS`: the socket topology, `CORE`, `THREAD`, `FAMILYNUMBER`, `MODEL`, `STEPPING` | one entry for the whole machine, with its total counts |
| `/sys/class/dmi/id/bios_{date,vendor,version}` | `BIOS/{BDATE,BMANUFACTURER,BVERSION}` | those three elements are left out |
| `/sys/devices/virtual/dmi/id/{product_name,product_serial,sys_vendor,board_vendor}` | the rest of `BIOS` | no `BIOS` section without `product_name` |
| `/sys/devices/virtual/dmi/id/product_uuid` | `HARDWARE/UUID` | left out |

The rest comes from `sysinfo` (`/proc/cpuinfo` and `/proc/stat` for
`CPUS/{NAME,MANUFACTURER}`, `/proc/meminfo` for `HARDWARE/{MEMORY,SWAP}`, `/etc/passwd` for
`LOCAL_USERS`, `/proc/mounts` plus a `statvfs` per mount for `DRIVES`), and `uname`, `gethostname`
and the `geteuid` behind `RUDDER/AGENT/OWNER` through `nix`.

Unix only, and only Linux is exercised. There is no Windows inventory yet, so nothing here carries a
Windows branch: whoever adds one adds it deliberately rather than inheriting a path nothing has run.

## What it does not produce

* **Out of scope**, being hardware components: `BATTERIES`, `CONTROLLERS`, `INPUTS`, `MEMORIES`,
  `PORTS`, `SLOTS`, `SOUNDS`, `STORAGES`, `USBDEVICES`, `VIDEOS`. Plus `VERSIONPROVIDER`, which
  describes the Perl interpreter running the agent.
* **Read by nothing on the server**: `REQUEST/QUERY`; `LOCAL_GROUPS`; `BIOS/{ASSETTAG,MMODEL,MSN,SKUNUMBER}`;
  `CPUS/{CACHE,CORECOUNT,DESCRIPTION,SERIAL}`; `DRIVES/SERIAL`; `HARDWARE/{DEFAULTGATEWAY,DNS,IPADDR,OSNAME,PROCESSORN,PROCESSORT}`;
  `LOCAL_USERS/{HOME,ID,SHELL}`; `OPERATINGSYSTEM/{BOOT_TIME,DNS_DOMAIN,HOSTID,SSH_KEY}`;
  `RUDDER/{AGENT/CFENGINE_KEY,AGENT/POLICY_SERVER_HOSTNAME,SERVER_ROLES}`.
* **Read, but deliberately left out**: `DRIVES/NUMFILES`, the number of inodes of a filesystem,
  which FusionInventory does not report on Linux either; and `CPUS/EXTERNAL_CLOCK`, the clock of
  the bus, which the server stores and returns in the node API but displays nowhere and offers no
  group criterion on, and which the firmware of a virtual machine does not know anyway.
* **Redundant**, the server reading them only as a fallback for something we always produce:
  `HARDWARE/OSVERSION` (see `OPERATINGSYSTEM/KERNEL_VERSION`), `HARDWARE/ARCHNAME`
  (`OPERATINGSYSTEM/ARCH`), `LOCAL_USERS/NAME` (`LOCAL_USERS/LOGIN`).
* **Windows-only**, with nothing to report on Unix: `DRIVES/LETTER`, `HARDWARE/{USERDOMAIN,WINCOMPANY,WINPRODID,WINPRODKEY}`.
* **Not produced yet**, the server reading all three: the `PROCESSES` section, `HARDWARE/VMSYSTEM`,
  which says how the machine is virtualized and which it reads as a physical machine when absent,
  and `CPUS/SPEED` (see below).

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

### A `BIOS` section is reported only when DMI names the model

`BIOS` is not a hardware catalogue: it is what identifies the machine, and the server keeps the
manufacturer and the serial number of it in a record of its own. That record is keyed on the
model, so the server drops the whole entry without `SMODEL` — and with it the manufacturer and the
serial number. We therefore report no `BIOS` section at all rather than one the server discards.

The values come from `sysinfo`, which reads DMI directly, where FusionInventory runs `dmidecode`.
The three `BIOS/B*` elements are not exposed by `sysinfo` and are read from `/sys/class/dmi/id`,
so they are the Linux-only part of the section.

Where the firmware said nothing, FusionInventory writes an empty element and we leave the element
out: `<SSN />` says the machine has no serial number, where the truth is that we cannot read it
without being root. The placeholders the firmware writes instead of leaving a value out (`Not
Specified`, `To Be Filled By O.E.M.`, and the rest of `getDmidecodeInfos`' list) are dropped the
same way.

### `CPUS` on ARM, and the architecture it reports

Two divergences. Everything else matches FusionInventory: one entry per physical processor, `CORE`
and `THREAD` counting that processor rather than the machine, the `ID` and `FAMILYNAME` of
`dmidecode -t 4`, and the vendor names of `getCanonicalManufacturer`.

**We report a section on 64 bit ARM, where FusionInventory reports none** — its ARM subtree is gated
on a Perl `archname` matching `^arm`, which `aarch64` does not match. Ours holds the name and
manufacturer `sysinfo` decodes from `CPU implementer` and `CPU part`, the architecture, and the
counts of the machine, an ARM kernel naming no socket. `FAMILYNUMBER`, `MODEL` and `STEPPING` stay
out, being x86 notions.

**`ARCH` is the real architecture**, `x86_64` where FusionInventory hardcodes `i386`. The server
stores it verbatim, without the `normalizeArch` the node architecture goes through, and only shows
it: the `Architecture` column of the node details Processors table, and `processors[].arch` of the
node API. No `OC_PROCESSOR` criterion covers it, so nothing can select on it and no group can be
invalidated by it changing, and `processOsDetails` reads the queryable node architecture inside the
`OPERATINGSYSTEM` element, out of reach of a `CPUS/ARCH`. The effect is one corrected column, `i386`
having been reported whatever the machine was.

A processor the kernel names in no way at all is reported under its manufacturer, then under its
architecture, with a warning, where FusionInventory numbers it. With nothing to call it by we report
no processor at all, the server dropping a nameless entry and the counts with it.

`SPEED` is left out for now. `sysinfo` answers the **current** frequency, which a machine that
scales its clock changes between two runs, so reporting it would rewrite the value in the interface
on every inventory and say a laptop is a 400 MHz machine when it happens to run idle.
FusionInventory reports the nominal frequency instead, parsing it out of the model name
(`@ 2.60GHz`) and falling back to the `Version` and `Current Speed` of `dmidecode` — the first two
being stable, the third having the same problem. Producing the nominal value that way is what this
element needs, so it waits for that rather than shipping a number that churns.

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

### Filesystems come from `sysinfo`, not from `df`

`sysinfo` gives us the mount point, device, filesystem, total and free space of every filesystem,
and leaves out most of the pseudo filesystems, so `DRIVES` needs no platform-specific code. It
drops the ones it knows and the ones mounted under `/sys`, `/proc` or `/run`, and we drop what it
lets through on its size, reporting no filesystem of no size at all — which is what `df` does,
since it lists none of them without `-a`, and FusionInventory does not pass it. That is what makes
both agents agree on which filesystems are real, and it also stops the next pseudo filesystem
FusionInventory's list of names has never mentioned, `nsfs` today. `efivarfs`, mounted under
`/sys`, we leave out as FusionInventory does, which drops it for holding a quarter of a megabyte.

We do not report `SERIAL`, the filesystem identifier, as nothing on the server reads it: that is
the only reason FusionInventory calls `blkid`, `dumpe2fs`, `xfs_db` and `dosfslabel`, none of which
we need.

### The whole `DRIVES` section is all or nothing

The enumeration is given 10 seconds, the same budget FusionInventory gives its `df` call. **On
expiry we report no filesystem at all, not even the local ones**, so a single unresponsive network
mount costs the whole section, and the node then looks as though it had no filesystem rather than
fewer. A killed `df` may still have printed some of its output, so FusionInventory degrades better
here.

The reason is that `sysinfo` offers no way to ask for the filesystems one at a time: the
`DiskRefreshKind` of the version we use only has `nothing()` and `everything()`, with no way to
list the mount points first and then time-bound each size lookup on its own. Getting partial
results would mean reading `/proc/mounts` ourselves and using `sysinfo` for sizes only. Since the
size lookup is a blocking call nothing can interrupt, the enumeration runs on a thread we abandon
instead of waiting for, which only the run exiting cleans up.

### Inventory hooks are held to stricter conditions

Each executable in `/var/rudder/hooks.d` prints a JSON object we collect into the array the server
reads from `CUSTOM_PROPERTIES`. They run as root, so a hook we are not certain about is skipped
instead of executed. On top of the conditions FusionInventory checks — executable, owned by root or
by the user we run as, not group or world writable — we also:

* refuse symlinks, whose target can be replaced between the moment we check it and the moment we
  run it, by reading the metadata of the file we run rather than of what it points at (`lstat`
  rather than `stat`),
* bound the output we read from a hook, and the time we give it.

A hook that is refused, fails, times out or does not return JSON only loses its own properties: the
other hooks are still reported. No hook directory at all leaves the element out, as FusionInventory
does, where a directory holding nothing gives an empty array.

### `RUDDER/AGENT_VERSION` is always reported

The version comes from the `rudder_version` of `/opt/rudder/share/versions/rudder-agent-version`,
as in Fusion, but the version of this module stands in when that file is missing or names no version. 

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
fully qualified name, a filesystem enumeration that timed out — those are the administrator's business and show without any flag.
