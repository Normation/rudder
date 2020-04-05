@format=0

# do not extend system (or so ?)
global enum system {
  unix,
  linux,
  windows
}

items in windows {
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

items in unix {
  aix,
  bsd,
  hp_ux,
  solaris,
  *
}

items in bsd {
  dragonfly,
  freebsd,
  netbsd,
  openbsd,
  *
}

items in linux {
  alpine_linux,
  arch_linux,
  @cfengine_name="debian"
  debian_family,
  fedora,
  gentoo,
  mandrake,
  mandriva,
  oracle,
  oracle_vm_server,
  @cfengine_name="redhat"
  redhat_family,
  slackware,
  suse,
  *
}

items in redhat_family {
  centos,
  @cfengine_name="(redhat.!centos)"
  redhat,
  redhat_entreprise,
  scientific_linux,
  *
}

items in debian_family {
  @cfengine_name="(debian.!ubuntu)"
  debian,
  ubuntu,
  *
}

items in redhat_entreprise {
  redhat_es,
  redhat_as,
  redhat_wa,
  redhat_c,
  redhat_w,
  * # really ?
}

items in solaris {
  solaris_10,
  solaris_11,
  solaris_12,
  *
}

items in debian {
  debian_4,
  debian_5,
  debian_6,
  debian_7,
  debian_8,
  debian_9,
  debian_10,
  *
}
