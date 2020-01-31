global enum os {
  aix,
  android,
  bsd,
  hpux,
  linux,
  solaris,
  windows,
  # bsd sub
  dragonfly,
  freebsd,
  netbsd,
  openbsd,
  # linux sub
  alpinelinux,
  archlinux,
  centos, # V.V
  debian, # v.v
  fedora,
  gentoo,
  Mandrake, # v.v
  Mandriva, # v.v
  oracle, # v.v
  oraclevmserver, # v
  redhat, # v.v
  redhat_es, # v.v
  redhat_as, # v.v
  redhat_ws, # v.v
  redhat_s, # v.v
  redhat_c, # v.v
  redhat_w, # v.v
  scientific, # v.v
  slackwave,
  SUSE, # v.v 
  ubuntu, # v.v
  # win sub
  Win2000,
  Windows_2000,
  WinXP,
  Windows_XP,
  WinServer2003,
  Windows_Server_2003,
  Windows_Server_2003_R2,
  Windows_Vista,
  Windows_Server_2008,
  Windows_Server_2008_R2,
  Windows_7,
  Windows_Server_2012,
  Windows_Server_2012_R2
}

enum os ~> family {
  aix -> aix,
  android -> android,
  bsd -> bsd,
  hpux -> hpux,
  linux -> linux,
  solaris -> solaris,
  windows -> windows,
  # bsd
  dragonfly -> bsd,
  freebsd -> bsd,
  netbsd -> bsd,
  openbsd -> bsd,
  # linux sub
  alpinelinux -> linux,
  archlinux -> linux,
  centos -> linux,
  debian -> linux,
  fedora -> linux,
  gentoo -> linux,
  Mandrake -> linux,
  Mandriva -> linux,
  oracle -> linux,
  oraclevmserver -> linux,
  redhat -> linux,
  redhat_es -> linux,
  redhat_as -> linux,
  redhat_ws -> linux,
  redhat_s -> linux,
  redhat_c -> linux,
  redhat_w -> linux,
  scientific -> linux,
  slackwave -> linux,
  SUSE -> linux,
  ubuntu -> linux,
  # windows sub
  Win2000 -> windows,
  Windows_2000 -> windows,
  WinXP -> windows,
  Windows_XP -> windows,
  WinServer2003 -> windows,
  Windows_Server_2003 -> windows,
  Windows_Server_2003_R2 -> windows,
  Windows_Vista -> windows,
  Windows_Server_2008 -> windows,
  Windows_Server_2008_R2 -> windows,
  Windows_7 -> windows,
  Windows_Server_2012 -> windows,
  Windows_Server_2012_R2 -> windows
}
