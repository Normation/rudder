# Inventory module

## Introduction

A Rudder-specific inventory, not a reimplementation of FusionInventory.

The output uses the element names of the FusionInventory format, because that is what the
Rudder server parses, and both can be compared side by side. That is where the resemblance
stops: **we produce only what the Rudder server actually reads, and nothing else.**.

Consequences to expect:

* Our inventory is a strict subset of a FusionInventory one, and a much smaller file.
* We may sometimes diverge from FusionInventory's behavior

### Goals

* Provide everything needed for Rudder to operate correctly
* Provide everything needed to satisfy use cases in cloud/virtualized contexts

### Non-goals

* Replace FusionInventory or any other _real_ inventory tool.
* Inventory the hardware components of a machine: no memory slot, controller, port, sound
  card, video card, battery or peripheral.
* Produce anything the Rudder server does not use.

## Compatibility

The inventory module should be minimally compatible with all Unix systems. **No command is
required**: a machine with none of the ones below installed is still inventoried, losing only
what each of them feeds. A run only fails on the three files that identify the node, listed
below.

### Commands

Every command the module runs, and nothing else:

| Command | Feeds | Without it |
| --- | --- | --- |
| `hostname --fqdn` | `OPERATINGSYSTEM/FQDN`, `RUDDER/HOSTNAME` | the hostname alone is used |
| `last` | `HARDWARE/{LASTLOGGEDUSER,DATELASTLOGGEDUSER}` | those elements are left out |
| `systemd-detect-virt` | `HARDWARE/VMSYSTEM` | the firmware is read instead, which cannot see a container |
| `dmidecode -t 4` | `CPUS/{ID,FAMILYNAME,EXTERNAL_CLOCK}` | those elements are left out |
| `dpkg-query`, then `rpm` | `SOFTWARES` | no installed software is reported |
| `apt-get`, then `zypper`, then `dnf` or `yum` | `SOFTWAREUPDATES` | no pending update is reported |
| the hooks themselves | `RUDDER/CUSTOM_PROPERTIES` | no custom property is reported |

All of them are looked up in `PATH`, or given a fallback value, so a command that is not
installed is a normal outcome rather than an error.

### Files

Not all of these are files: `uname` is a system call and `TZ` an environment variable, both
listed here as the rest of what the module reads without running a command.

| Path | Feeds | Without it |
| --- | --- | --- |
| `/opt/rudder/etc/uuid.hive` | `RUDDER/UUID` | the run fails |
| `/opt/rudder/etc/ssl/agent.cert` | `RUDDER/AGENT/AGENT_CERT` | the run fails |
| `/var/rudder/cfengine-community/rudder-server-uuid.txt` | `RUDDER/AGENT/POLICY_SERVER_UUID` | the run fails |
| `/opt/rudder/etc/agent-capabilities` | `RUDDER/AGENT_CAPABILITIES` | no capability is reported |
| `/opt/rudder/share/versions/rudder-agent-version` | `RUDDER/AGENT_VERSION` | the version of this module is reported instead, with a warning |
| `/var/rudder/hooks.d/` | `RUDDER/CUSTOM_PROPERTIES` | the element is left out |
| `/etc/os-release`, then `/usr/lib/os-release` | `OPERATINGSYSTEM/{NAME,VERSION,FULL_NAME}`, the deb publisher | the machine is reported as a generic `Linux`, with a warning |
| `TZ` env variable, then `/etc/localtime` | `OPERATINGSYSTEM/TIMEZONE`, and the local time of `ACCESSLOG/LOGDATE` and `PROCESSES/STARTED` | the element is left out, and the times fall back to UTC |
| `/sys/class/dmi/id/bios_{date,vendor,version}` | `BIOS/B*` | those three elements are left out |
| `/sys/devices/virtual/dmi/id/{product_name,product_serial,product_uuid,sys_vendor,board_vendor}` | the rest of `BIOS`, `HARDWARE/UUID` | no `BIOS` section, no machine UUID |
| `/sys/class/net/<interface>/{flags,type,speed,wireless,brif,bonding,device}` | `NETWORKS/{STATUS,TYPE,SPEED}` | those elements are left out |
| `/proc/net/route` | `NETWORKS/IPGATEWAY` | no gateway is reported |
| `/proc/cpuinfo` | `CPUS`: the socket topology, `CORE`, `THREAD`, `FAMILYNUMBER`, `MODEL`, `STEPPING` | one entry for the whole machine, with its total counts |
| `/proc/<pid>/stat` | `PROCESSES/TTY` | the element is left out |

The three files a run fails on are refused when they are **empty** as well as when they are
missing, and the failure names the file. 

### Through `sysinfo`

The rest comes from `sysinfo`, which on Linux reads `/proc/cpuinfo` and `/proc/stat` for
`CPUS`, `/proc/meminfo` for `HARDWARE/{MEMORY,SWAP}`, `/proc/<pid>/` for `PROCESSES`,
`/etc/passwd` for `LOCAL_USERS`, `/proc/mounts` plus a `statvfs` per mount for `DRIVES`, and
`/sys/class/net/` for the interface addresses.

`uname`, `gethostname` and the `getuid` of the hook checks are called through `nix`, as is the
password database lookup that names the user we run as.

### Platforms

`NETWORKS` and the three `BIOS/B*` elements are Linux only, as they come from `/sys` and
`/proc` directly. Inventory hooks are Unix only. Everything else is written to work on any
platform `sysinfo` supports, though only Linux is exercised today.

Software and software update inventory covers the dpkg, RPM and SUSE families: `SOFTWARES`
from `dpkg-query` or `rpm`, `SOFTWAREUPDATES` from `apt-get`, `zypper`, `dnf` or `yum`. Which
one runs is decided by what the machine has installed rather than by what `/etc/os-release`
says it is, so a distribution we have never heard of is inventoried as long as it uses one of
them. On a machine with none, both sections are left out.

## Content not produced

Windows-only content is not
represented below.

The sections we produce in full, as far as the server reads them, are `ACCESSLOG`, `BIOS`,
`CPUS`, `DRIVES`, `ENVS`, `LOCAL_USERS`, `NETWORKS`, `PROCESSES`, `RUDDER`, `SOFTWARES`,
`SOFTWAREUPDATES` and `VERSIONCLIENT`.

### Out of scope

Inventorying hardware components is an explicit non-goal: `BATTERIES`, `CONTROLLERS`, `INPUTS`,
`MEMORIES`, `PORTS`, `SLOTS`, `SOUNDS`, `STORAGES`, `USBDEVICES` and `VIDEOS`.

`VERSIONPROVIDER` describes the Perl interpreter running the agent, so it has no meaning here.

### Read by nothing on the server

| Section | Elements |
| --- | --- |
| `REQUEST` | `QUERY` (`DEVICEID` is mandatory and is produced) |
| `BIOS` | `ASSETTAG`, `MMODEL`, `MSN`, `SKUNUMBER` |
| `CPUS` | `CACHE`, `CORECOUNT`, `DESCRIPTION`, `SERIAL` |
| `DRIVES` | `SERIAL` |
| `FIREWALL` | the whole section |
| `HARDWARE` | `DEFAULTGATEWAY`, `DNS`, `IPADDR`, `OSNAME`, `PROCESSORN`, `PROCESSORT` |
| `LOCAL_GROUPS` | the whole section |
| `LOCAL_USERS` | `HOME`, `ID`, `SHELL` |
| `NETWORKS` | `DRIVER`, `PCISLOT`, `VIRTUALDEV`, `WIFI_*` |
| `OPERATINGSYSTEM` | `BOOT_TIME`, `DNS_DOMAIN`, `HOSTID`, `SSH_KEY` |
| `RUDDER` | `AGENT/CFENGINE_KEY`, `AGENT/POLICY_SERVER_HOSTNAME`, `SERVER_ROLES` |
| `SOFTWARES` | `FILESIZE`, `FROM`, `INSTALLDATE`, `SYSTEM_CATEGORY` |

### Kept although the server does not read it

`SOFTWARES/ARCH`. The architecture is what tells two builds of the same package version apart,
which matters too much to drop on the grounds that no code reads it today.

### Read but not produced yet

These the server does read, and we do not produce them yet.

One whole section, `VIRTUALMACHINES`: the virtual machines hosted *on* the node, which the
server keeps in `node.vms`. It is also a group criterion, so a group built on it matches nothing
today.

Elements of sections we do produce:

| Section | Elements |
| --- | --- |
| `SOFTWAREUPDATES` | `DATE` |

`SOFTWAREUPDATES/DATE` is the day an advisory was published, which only `yum updateinfo info`
says, and that is not read: the `list` we do read names the advisory, its kind and its severity,
but not its date.

`SOFTWAREUPDATES/{KIND,SEVERITY,ID}` are advisory notions too, and only the yum source fills
them in. An apt update carries none of them, and a zypper one carries none for the reason
below.

### Read, and decided against

The server reads these, and we have decided not to produce them. They are listed so that the
decision is not made again from scratch.

| Element | Why not |
| --- | --- |
| `DRIVES/NUMFILES` | the number of inodes of a filesystem, which FusionInventory does not report on Linux either |
| `NETWORKS/IPDHCP` | would mean finding and parsing the lease files of whichever DHCP client is installed |
| `NETWORKS/IPGATEWAY6` | would mean reading `/proc/net/ipv6_route`; FusionInventory does not report it on Linux either |
| `NETWORKS/TYPEMIB` | only means something to an SNMP inventory |

### Read, but redundant with what we do produce

The server reads these only as a fallback for an element we always produce, so producing them
would change nothing.

| Element | Made redundant by |
| --- | --- |
| `HARDWARE/OSVERSION` | `OPERATINGSYSTEM/KERNEL_VERSION` |
| `HARDWARE/ARCHNAME` | `OPERATINGSYSTEM/ARCH` |
| `LOCAL_USERS/NAME` | `LOCAL_USERS/LOGIN` |

### Only meaningful on Windows

`DRIVES/LETTER` and `HARDWARE/USERDOMAIN`, `WINCOMPANY`, `WINPRODID`, `WINPRODKEY`. The server
reads them, and there is nothing to report for them on Unix.

## Differences from FusionInventory

The sections we do produce use the same element and field names as the patched
FusionInventory agent, so both outputs can be compared directly. The behavior differs on
the following points.

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

### Inventory hooks are held to stricter conditions

Hooks in `/var/rudder/hooks.d` run as root, so a hook we are not certain about is skipped
instead of executed. On top of the conditions FusionInventory checks (executable, owned by
root or by the current user, not group or world writable), we also:

* refuse symlinks, whose target can be replaced between the moment we check it and the
  moment we run it,
* check the file we run rather than what a symlink points at (`lstat` rather than `stat`),
* bound the output we read from a hook.

A hook that is refused, fails, times out or does not return JSON only loses its own
properties: the other hooks are still reported.

Hooks are not supported on Windows yet, where `CUSTOM_PROPERTIES` is left out of the
inventory. FusionInventory runs them through `powershell.exe` and assumes they are safe; we
have no equivalent of the checks above there yet.

### Filesystems come from `sysinfo`, not from `df`

`sysinfo` gives us the mount point, device, filesystem, total and free space of every
filesystem, and leaves out most of the pseudo filesystems, so `DRIVES` needs no
platform-specific code. Two smaller consequences:

* We do not report `SERIAL`, the filesystem identifier, as nothing on the server reads it.
  That is the only reason FusionInventory calls `blkid`, `dumpe2fs`, `xfs_db` and
  `dosfslabel`, none of which we need.
* We report `efivarfs`, which is mounted under `/sys`, no more than FusionInventory does,
  which drops it for holding a quarter of a megabyte.

### The whole `DRIVES` section is all or nothing

The enumeration is given 10 seconds, the same budget FusionInventory gives its `df` call.
**On expiry we report no filesystem at all, not even the local ones**, so a single
unresponsive network mount costs the whole section, and the node then looks as though it had
no filesystem rather than fewer. A killed `df` may still have printed some of its output, so
FusionInventory degrades better here.

The reason is that `sysinfo` offers no way to ask for the filesystems one at a time: the
`DiskRefreshKind` of the version we use only has `nothing()` and `everything()`, with no way
to list the mount points first and then time-bound each size lookup on its own. Getting
partial results would mean reading `/proc/mounts` ourselves and using `sysinfo` for sizes
only.

Note also that FusionInventory computes an incorrect IPv6 mask and subnet for a `/128`
address, reporting `fff0::` and `::` for the loopback. We report the correct
`ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff` and `::1`.

### The values `ps` prints are reported as `ps` prints them

`PROCESSES/MEM` is the share of the memory of the machine the process holds, as a percentage,
and `VIRTUALMEMORY` is its virtual size in kilobytes: those are the units of the `pmem` and `vsz`
columns FusionInventory reads from `ps`, not the bytes `sysinfo` hands us. The share is truncated
to a tenth, as `ps` truncates it, which also avoids doubling the share of the smallest processes.

`PROCESSES/CPUUSAGE` is the `%CPU` of `ps`, which is not the instantaneous usage it reads like:
it is the CPU time a process has used over its whole life divided by how long it has been
running, so a process that computed for a second and then idled for an hour is reported near
zero. We compute it, rather than take the instantaneous usage `sysinfo` offers, because that one
is a delta between two refreshes and is therefore zero for every process on the single refresh a
run does. The arithmetic is the one of `pr_pcpu` in `procps`, in the order it performs it, so
that a value lands on the same tenth. Past 99.9%, which a process holding more than one
processor reaches, `ps` drops the decimal and reports `200` rather than `200.0`, and so do we.

`PROCESSES/CMD` is the command line of the process, and the name the kernel gives it between
brackets, as in `[kworker/u51:0]`, when it has none. That is how `ps` names a kernel thread, and
how a reader tells one from a process that happens to carry the same name. Expect most of the
section to look like that on an idle machine.

The control characters of a command line are replaced, as `ps` replaces them: a newline by a
space, since `ps` prints one process per line, and the rest by a question mark. This is not
cosmetic. A process may hold anything at all in its arguments, and any user of the machine can
start one; XML cannot carry those characters, and cannot escape them either, as a character
reference naming a forbidden character is just as forbidden. One such process is otherwise
enough to make the whole inventory unparsable, and a node whose inventory does not parse
reports nothing at all. FusionInventory never carries one because `ps` has already taken it out.

The section holds the processes `ps` lists, which the kernel threads are part of and the threads
of a process are not. `sysinfo` lists the tasks of a process alongside the processes themselves
unless it is told otherwise, so a process with ten threads would be reported eleven times.

`PROCESSES/USER` is the owner of the process. `sysinfo` only reads it when it is asked to, and
an owner it did not read is indistinguishable from a process we are not allowed to look at, so
the whole section came without an owner until we asked for one.

`PROCESSES/TTY` is the terminal a process is attached to, named as `ps` names it. `sysinfo` does
not expose it, so it is decoded from the device number in `/proc/<pid>/stat`, for the virtual
consoles, the serial lines and the pseudo terminals. A process attached to any other class of
device is reported without a terminal rather than under a name we would not be sure of.

`PROCESSES/STARTED` is a local time, as FusionInventory reports it, formatted the same way. The
server keeps it as it is written, so what matters is only that two agents mean the same thing by
the same date.

Every date comes from `jiff`, including the name and the offset of
`OPERATINGSYSTEM/TIMEZONE`, which are read from the same zone and so cannot disagree. The zone is
the one `TZ` names, and the one `/etc/localtime` points at when it does not, which is what
FusionInventory resolves as well.

The seventeen names the zone database links to `UTC`, `Etc/UTC` and `GMT` among them, are
reported as `UTC`. A machine set to `Etc/UTC` is really configured with `Etc/UTC`, and that is
what `jiff` answers, but FusionInventory asks `DateTime::TimeZone`, which resolves the links of
the database before naming a zone. The server keeps the name as it is written, so two agents
disagreeing would mean the same machine changing timezone in the interface depending on which
one inventoried it. A machine set to UTC being the common case, we follow FusionInventory here
rather than be right on our own.

We only resolve the links that lead to `UTC`, of the 249 the database holds. The rest are region
renames, where a node keeps whichever of `Asia/Calcutta` and `Asia/Kolkata` it is configured
with, and FusionInventory reports the new name for both. Note also that `DateTime::TimeZone`
carries its own copy of the database, where we read the one installed on the machine: a zone
whose file is missing, as the ones a distribution moves to a legacy package are, leaves the
element out for us and is still named by FusionInventory.

`HARDWARE/LASTLOGGEDUSER` and `DATELASTLOGGEDUSER` come from `last`, whose most recent line is
the one we keep, leaving out the records of the machine starting and stopping. The date is the
four fields that start on a day of the week, as the columns before it vary, which gives the
`EEE MMM dd HH:mm` the server parses. We also leave out the footer naming the file, where
FusionInventory would report a user called `wtmp` on a machine nobody has logged into.

### The version is the number, and the SUSE service pack is split out of it

FusionInventory reports the version out of one of two modules, and which one runs decides what
a version is:

* `Distro::LSB` runs on any distribution that has `lsb_release`, which is nearly all of them,
  and reports the `Release:` it prints: the bare number, `26.04` rather than
  `26.04 LTS (Resolute Raccoon)`.
* `Distro::NonLSB` runs on SUSE, on Oracle, and where there is no `lsb_release`. Falling back
  to `/etc/os-release`, it reports `VERSION`, which is where SUSE carries its service pack as
  `15-SP5`, and splits the two apart.

We read `/etc/os-release` and nothing else, so we take `VERSION_ID`, which holds the number
`lsb_release -r` prints, and `VERSION` only when it names a service pack, that being the one
case where its extra content is what we are after. A distribution that names no `VERSION_ID`
leaves us with `VERSION`.

Both agents then report `26.04` for an Ubuntu 26.04 LTS node, and a `VERSION` of `15` with a
`SERVICE_PACK` of `5` for a SLES 15 SP5 one. No distribution outside SUSE has a service pack.

Two of our FusionInventory patches exist only to reach the SUSE behaviour, by forcing the
non-LSB module to run on SLES 15 so that its service pack logic applies. Reading
`/etc/os-release` directly, we have no equivalent of that dance. We also have no need for the
`PATCHLEVEL` of `/etc/SuSE-release`, which the patches read for the older releases: a machine
that needs it has no `/etc/os-release`, without which we do not run at all.

### One `CPUS` entry per physical processor

Like FusionInventory, we produce one entry per physical processor, whose `CORE` and `THREAD`
count the cores and logical CPUs of **that processor**, not of the machine. `sysinfo` knows
nothing of the socket a logical CPU belongs to, so the topology comes from the `physical id` of
each block of `/proc/cpuinfo`: the first block of a socket describes it, and the processors
`dmidecode` prints line up with those sockets, as both leave out the sockets a machine has but
holds nothing in.

A kernel that names no socket at all, as an ARM one does not, leaves a single entry for the whole
machine holding its total counts.

On ARM this is where we differ most from FusionInventory. Its ARM module reports only `ARCH` and
`NAME`, one entry per logical CPU, and its subtree is gated on a Perl `archname` matching `^arm`,
which `aarch64` does not: **patched FusionInventory produces no `CPUS` section at all on a 64 bit
ARM machine**. We report one there, with the name and the manufacturer `sysinfo` decodes from
`CPU implementer` and `CPU part`, the architecture, and the counts of the machine. The family
number, the model and the stepping stay out, as they are x86 notions.

A machine that names its processor in no way at all is reported under its manufacturer, then
under its architecture, with a warning. Only when there is nothing to call it by do we report no
processor: the server drops an entry without a name, and that would lose the counts as well.

Every element of the section the server reads is produced, from three sources:

| Source | Elements |
| --- | --- |
| `sysinfo` | `NAME`, `MANUFACTURER`, `SPEED`, `ARCH` |
| `/proc/cpuinfo` | `CORE`, `THREAD`, `FAMILYNUMBER`, `MODEL`, `STEPPING` |
| `dmidecode -t 4` | `ID`, `FAMILYNAME`, `EXTERNAL_CLOCK` |

The last three live in the SMBIOS table, and we read them by running `dmidecode`, as
FusionInventory does. The kernel does expose the raw table, but the name of a processor family
comes from a table of more than two hundred entries that only `dmidecode` carries, so it has to
be run anyway and there is nothing left to gain from parsing the table ourselves.

Three things follow:

* A machine without `dmidecode` reports none of those three, as it would with FusionInventory.
* `dmidecode` needs root, so they are also left out when the module runs as anyone else. The
  agent runs as root.
* `EXTERNAL_CLOCK` is left out when the firmware does not know it, which is what a virtual
  machine usually reports.

The family number, the model and the stepping come from `/proc/cpuinfo`, which the x86 kernels
only fill in. An ARM one describes its processors with an implementer, a part and a variant,
which have no place in what the server stores, so they are left out there.

`CPUS/MANUFACTURER` is the name of the manufacturer, not the vendor identifier the kernel
reports: `GenuineIntel` becomes `Intel` and `AuthenticAMD` becomes `AMD`. Every other identifier
is reported as the kernel names it, where FusionInventory also maps the vendors of the 2000s.
An ARM kernel names no vendor at all, and gets no element rather than an empty one.

`CPUS/ARCH` is the real architecture, `x86_64` for instance, where FusionInventory hardcodes
`i386` in its x86 module.

### `HARDWARE/UUID` is read from DMI rather than from `dmidecode`

The motherboard UUID comes from `sysinfo`, which reads it out of DMI directly, where
FusionInventory runs `dmidecode` for it. It is how a virtual machine is told apart from a clone
of itself, so it matters that it is reported. On Linux that is
`/sys/devices/virtual/dmi/id/product_uuid`, which is only readable by root, as the agent is;
running the module as anyone else reports no machine UUID rather than failing.

### `HARDWARE/VMSYSTEM` is asked of `systemd-detect-virt`, not guessed from DMI

FusionInventory works the kind of machine out itself, in `Virtualization/Vmsystem.pm`: a cascade
that matches the DMI manufacturer and model against a list of vendor strings, then looks for
`/.dockerenv`, `/proc/xen`, Solaris zones, BSD jails and loaded kernel modules, then greps
`dmesg` and `/proc/scsi/scsi` for the device names hypervisors leave behind. We run
`systemd-detect-virt` and report what it answers.

That single command reads more than DMI can say, and knows more of what it finds:

* **It sees containers, and DMI cannot.** DMI describes the hardware of the host, which a
  container inherits, so an LXC container on a QEMU host has `sys_vendor=QEMU` and would be
  reported as a virtual machine. `systemd-detect-virt` reads `/run/systemd/container`,
  `/proc/1/environ` and `/proc/vz`, names eleven kinds of container, and deliberately answers
  with the container rather than the machine under it. The server keeps `lxc` and `openvz` as
  types of their own, and a container is not a machine whose kernel can be patched or rebooted
  into.
* **It tells a Xen host from a Xen guest**, by reading `/proc/xen`, where the DMI of both says
  `Xen`. Guessing from DMI alone reports a hypervisor as one of its own guests.
* **It names thirty-one kinds of machine** where the DMI list covers about six, and separates
  `kvm` from `qemu`, `amazon` and `google` from the rest.
* **It is maintained upstream**, and hypervisors keep appearing. A list of vendor strings in
  this repository is a snapshot that ages, and a stale `VMSYSTEM` looks exactly like a correct
  one.
* **It does not need DMI to exist**, which is a UEFI and SMBIOS notion: absent on many ARM
  machines and on s390x, where it reads the device tree and `/proc/sysinfo` instead.

On a machine without `systemd-detect-virt`, FusionInventory's DMI rules are applied as a
fallback, to the values the `BIOS` section has already read, so it costs no file and no command.
It is the same list of vendor strings, in the same order, so a machine both agents fall back to
is reported the same by both. The values differ only in case: we write `qemu` and `hyper-v`
where FusionInventory writes `QEMU` and `Hyper-V`, which are literals it returns rather than
anything it read. The server lowercases the element before matching it, so the two are the same
answer.

**That fallback cannot see a container.** DMI describes the machine underneath one, so an LXC
guest on a QEMU host is reported `qemu`, and one on a physical host is reported as nothing at
all. This is the difference that decides the order: the command is asked first and believed, and
the firmware is only read when it has not answered. A node whose `VMSYSTEM` came from the
firmware, which `-d` says, is telling you what it runs on and not whether it is a container.

Nothing is reported when neither can name the machine, and the server reads that as a physical
one. DMI cannot tell a physical machine from a virtualization whose marks it does not carry, so
asserting `physical` on its silence would claim more than it says.

### An unknown kind of machine is reported under its own name

`HARDWARE/VMSYSTEM` is one of a closed list of values, the ones `FusionInventoryParser` matches
on: `physical`, `xen`, `virtualbox`, `vmware`, `qemu`, `hyper-v`, `virtuozzo`, `openvz`, `lxc`,
and `virtual machine` for one it has no better name for. Note these are not the identifiers the
server stores them under, its `VmType` entry names, which differ (`hyperv`, `vbox`): what has to
match is what it reads.

`systemd-detect-virt` knows some thirty technologies against those ten, so it can answer
something that is on neither list. Such an answer is passed through as it named itself, `bhyve`
say, along with a warning. The server reads anything it does not know as an unknown virtual
machine, exactly as it reads `virtual machine`, so nothing is lost by being specific — and the
inventory then says which technology it was, for whoever adds it here, where flattening it to
`virtual machine` would have hidden that a name was missing.

### Where the two vocabularies of `VMSYSTEM` disagree

Only part of the difference is case. FusionInventory returns a fixed literal for each rule it
matches, where `systemd-detect-virt` has a vocabulary of its own that names the same
technologies differently: `oracle` for VirtualBox, `microsoft` for Hyper-V, `kvm` alongside
`qemu`. Those are translated to what the server matches on, so both agents end on the same
type. Three cases do not resolve that neatly.

**`bochs` is reported as an unknown virtual machine.** `systemd-detect-virt` names it in its
own right and we have no case for it, so it is passed through as `bochs` and read as an unknown
virtual machine. FusionInventory matches `Bochs` in the DMI manufacturer and reports `QEMU` —
and so does our own DMI fallback, which is that same rule. The command is asked first and
believed, so this is the one answer where the fallback would have been more precise than the
one we keep.

**OpenVZ is `openvz` for us and `Virtuozzo` for FusionInventory.** The server keeps both as
types of its own. FusionInventory reaches the name through `envID` in `/proc/self/status` and
calls the result `Virtuozzo`, where `systemd-detect-virt` answers `openvz` and we report it as
it is, so the same container lands on a different `VmType` depending on which agent inventoried
it. Nothing constructs `virtuozzo` on our side today.

**FusionInventory answers `Physical` where it has no rule.** Its cascade ends on
`return 'Physical'`, so a technology it carries no vendor string, kernel module or `dmesg`
pattern for is reported as a physical machine rather than as an unrecognized one. That covers
most of the thirty `systemd-detect-virt` knows — `parallels`, `bhyve`, `powervm`, `zvm`,
`amazon` — though not the containers, which it passes through from `container=` in
`/proc/1/environ` much as we pass through a name we have no case for. This is the difference
worth caring about: a wrong answer reads exactly like a right one, where an unknown name at
least says the machine is virtual.

### `OPERATINGSYSTEM/FQDN` and `RUDDER/HOSTNAME` hold the same value

Both are resolved with `hostname --fqdn`, falling back to the hostname alone when it cannot
be resolved. FusionInventory gets `RUDDER/HOSTNAME` that same way, but `OPERATINGSYSTEM/FQDN`
from Perl's `Net::Domain::hostfqdn()`, which follows its own resolution rules and can return
a different domain for the same machine. The server only uses `OPERATINGSYSTEM/FQDN` as a
fallback for `RUDDER/HOSTNAME`.

### The software publisher comes from `/etc/os-release`

dpkg has no per-package vendor field, and Rudder aggregates software by name and publisher,
so a stable distribution-wide value is needed. FusionInventory uses `lsb_release -i`; we
capitalize the `ID` of `/etc/os-release` instead, which gives the same value
(`debian` -> `Debian`) without depending on the LSB tooling.

## Logging

Logs go to standard error through `tracing`, using the shared `rudder_cli` setup so the output
looks like the one of the other Rudder command line tools.

| Invocation | Level |
| --- | --- |
| `--quiet` | warnings and errors |
| default | adds one line per run, saying what was written where |
| `-d` | adds what each section found, and why a section is empty |
| `-dd` | everything |

Each section builds its logs under a span named after it, so a line reads
`inventory:packages: Found 1506 installed packages with dpkg`. `RUST_LOG` can raise the level
of a single module, as in `RUST_LOG=rudder_module_inventory::drives=debug`, but the command
line flag takes precedence over it for the global level.

What is worth knowing at `-d` is mostly why something is missing: no supported package manager,
no agent capability file, a hook refused or a filesystem enumeration that timed out. Those that
are the administrator's business, a hook we refuse to run in particular, are warnings and show
without any flag.
