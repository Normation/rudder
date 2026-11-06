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
| `dpkg-query`, `rpm` | `SOFTWARES` | no installed software is reported |
| `apt-get`, `zypper`, `dnf`, `yum` | `SOFTWAREUPDATES` | no pending update is reported |

| Path | Feeds | Without it |
| --- | --- | --- |
| `/opt/rudder/etc/uuid.hive` | `RUDDER/UUID` | **the run fails** |
| `/opt/rudder/etc/ssl/agent.cert` | `RUDDER/AGENT/AGENT_CERT` | **the run fails** |
| `/var/rudder/cfengine-community/rudder-server-uuid.txt` | `RUDDER/AGENT/POLICY_SERVER_UUID` | **the run fails** |
| `/opt/rudder/etc/agent-capabilities` | `RUDDER/AGENT_CAPABILITIES` | no capability is reported |
| `/opt/rudder/share/versions/rudder-agent-version` | `RUDDER/AGENT_VERSION` | the version of this module is reported instead, with a warning |
| `/var/rudder/hooks.d/`, and the hooks in it | `RUDDER/CUSTOM_PROPERTIES` | the element is left out |
| `/etc/os-release`, then `/usr/lib/os-release` | `OPERATINGSYSTEM/{NAME,VERSION,FULL_NAME}`, the deb publisher | a generic `Linux`, with a warning |
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
* **Not produced yet**, the server reading both: the `PROCESSES` section, and `CPUS/SPEED`
  (see below).

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

### `HARDWARE/VMSYSTEM` is decided by the firmware alone

The value names the hypervisor the machine runs on, and is the one FusionInventory produces for the
same machine: the variants are its strings, `_getType` in `Virtualization/Vmsystem.pm` decides them
in the same order from the same four elements — `SMANUFACTURER`, then `BMANUFACTURER`, then
`SMODEL`, then `BVERSION` — and a machine whose firmware names no hypervisor is `Physical` for both.
The `BIOS` values are read once and serve both sections.

**What we leave out is everything `_getType` does after those four blocks**: reading `dmesg`, the
loaded modules, `/proc/scsi/scsi`, the Solaris zone, the BSD jail, `/proc/xen`, `/proc/1/environ`
and the Docker and OpenVZ files. So a guest that only gives itself away outside the firmware is
reported `Physical` where FusionInventory names it: a container (`Docker`, `lxc`, `SolarisZone`,
`BSDJail`, `Virtuozzo`) and a paravirtualized Xen guest, which has no firmware to describe it. A
hardware-virtualized guest — QEMU/KVM, VMware, VirtualBox, Hyper-V, Xen HVM — describes itself in
DMI and is named by both agents. FusionInventory also rewrites `BIOS` and `HARDWARE/UUID` for some
of those cases, which we do not: our `BIOS` says what the firmware says.

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

### New packages are reported as software updates (deb systems)

`apt-get --simulate dist-upgrade` lists the currently installed version of each package it
would install, except when there is none because the package is pulled in as a new
dependency:

```
Inst base-files [12.4+deb12u5] (12.4+deb12u15 Debian:12.15/oldstable [amd64])   # upgrade
Inst linux-image-6.1.0-28-amd64 (6.1.119-1 Debian:12.15/oldstable [amd64])      # new package
```

FusionInventory requires that bracket to be present and so skips the second form. This
systematically hides kernel updates, as a new kernel ABI comes as a differently named
package (`linux-image-6.1.0-28-amd64` instead of the installed
`linux-image-6.1.0-26-amd64`). We report them, as they are a genuine part of the pending
upgrade.

Note that such an entry has no installed counterpart, and that it makes the number of
pending updates, hence the system update score, slightly higher than what the Perl agent
reports for the same node.

### An RPM update is matched to its advisories by name, not by version

`SOFTWAREUPDATES/{KIND,SEVERITY,ID}` are things an advisory says, not things a package does, and
only the yum source has them. Getting them takes two commands: `check-update` lists the packages
and versions, which is what the section is, and `updateinfo list` says which advisories mention
each package. The second is a bonus, and a repository carrying no advisories costs only those
three elements.

`updateinfo info`, which would add the date and the description of each advisory, is not read.
The severity therefore comes from the second column of `updateinfo list`, which holds a kind for
an ordinary advisory and the severity itself for a security one, as `Moderate/Sec.`.

The two lists are joined **on the package name alone**. It is tempting to join on the version as
well, since both print one, and FusionInventory does exactly that, matching `name-version.arch`
against the advisory list. It does not work: `check-update` offers the newest version there is,
while an advisory names the version *it* shipped, which is older as soon as a later advisory has
superseded it.

Run over the same Rocky 9 machine, FusionInventory's own condition matched **28 of 107** updates
and found 17 security ones; joining on the name matches **95 of 107** and finds 49. The rest it
reports as `kind=none, severity=none`, which is not "no advisory says anything about this" but
"we did not find the advisory that does". This is the largest deliberate divergence in the
section, and it makes our count of security updates, and the system update score built on it,
higher than the one the Perl agent reports for the same machine.

A package accumulates advisories, so `ID` holds all of them, comma separated, which is how the
server reads it. The other elements can only hold one answer, and take it from the worst of
them: a security advisory outranks any other kind, severity decides between two of those, and
the date breaks a remaining tie. That is the advisory that decides whether the update is
urgent, which is what the elements are read for.

FusionInventory writes `SEVERITY` as the literal `none` for an update it found no advisory for.
The server has no `none` severity, only `low`, `moderate`, `high` and `critical`, so it keeps
that as an `other` severity of its own. We leave the element out instead, the absence of a
severity not being a severity.

The vocabularies differ and are translated to the four kinds the server knows: what `yum` calls
`bugfix` and `zypper` calls `recommended` are both `defect`, and `zypper`'s `important` is the
server's `high`. A word neither knows is passed through rather than flattened into `none`, which
would claim an update is routine when we only failed to recognize it.

`zypper` is asked for `--xmlout` rather than for the table it prints by default, whose columns
wrap to the width of the terminal, are padded with the spaces a package name may itself hold,
and are interleaved with the warnings about expired repositories that any real machine produces.
That option is not a recent one: it was checked on zypper 1.11 (openSUSE 13.2), 1.13 (Leap
42.3), 1.14 (Leap 15) and the current one, and is what YaST has always driven zypper with.

### An exit code is an answer, not always a failure

`yum check-update` exits **100** when there are updates to install and 0 when there are none.
Its output therefore has to be read whatever it exited with, which the shared command helper
does not do: it keeps the output of a command that succeeded and falls back otherwise, so
reading the updates through it reported every RPM machine with pending updates as having none.
`updateinfo` likewise exits non-zero on a repository carrying no advisories, which is not a
failure either.

`zypper list-updates` is the opposite: it exits zero whether or not it found anything, so a
non-zero exit from it is a real failure. It is not swallowed but warned about, because the
alternative is reporting no pending update, and a machine with nothing pending is a machine the
server believes to be fully patched.
Its patches are left out: a patch is an advisory grouping packages, not a package to install,
and the packages it names are listed on their own, so counting both would report each update
twice. That is also why `zypper` updates carry no severity today, where the yum ones do.

### The software publisher comes from `/etc/os-release`

dpkg has no per-package vendor field, and Rudder aggregates software by name and publisher,
so a stable distribution-wide value is needed. FusionInventory uses `lsb_release -i`; we
capitalize the `ID` of `/etc/os-release` instead, which gives the same value
(`debian` -> `Debian`) without depending on the LSB tooling.

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
