@format=0

# do not extend system (or so ?)
enum system {
  unix,
  linux,
  windows
}

enum in windows {
  windows_XP,
  windows_Vista,
  windows_7,
  windows_8,
  windows_10,
  windows_2000,
  windows_2003,
  windows_2008,
  windows_2012,
  windows_2016,
  windows_2019,
  *
}

enum in unix {
  aix,
  bsd,
  hp_ux,
  solaris,
  *
}

enum in bsd {
  dragonfly,
  freebsd,
  netbsd,
  openbsd,
  *
}

enum in linux {
  alpine_linux,
  arch_linux,
  centos,
  debian_family,
  fedora,
  gentoo,
  mandrake,
  mandriva,
  oracle,
  oracle_vm_server,
  redhat_family,
  slackware,
  suse,
  *
}

enum in redhat_family {
  centos,
  redhat,
  redhat_entreprise,
  scientific_linux,
  *
}

enum in debian_family {
  debian,
  ubuntu,
  *
}

enum in redhat_entreprise {
  redhat_es,
  redhat_as,
  redhat_wa,
  redhat_c,
  redhat_w,
  * # really ?
}

enum in solaris {
  solaris_10,
  solaris_11,
  solaris_12,
  *
}

enum in debian {
  debian_4,
  debian_5,
  debian_6,
  debian_7,
  debian_8,
  debian_9,
  debian_10,
  *
}
