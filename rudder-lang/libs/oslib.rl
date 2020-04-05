@format=0

# @cfengine_name in list format only are useful for translate
# remove them once we don't need it anymore

global enum system {
  windows
  linux,
  aix,
  bsd,
  hp_ux,
  solaris,
  *
}

# == Windows
items in windows {
  windows_desktop,
  windows_server,
  *
}

items in windows_desktop {
  windows_xp,
  windows_vista,
  windows_7,
  windows_8,
  windows_10,
  *
}

items in windows_server {
  windows_2000,
  windows_2003,
  windows_2008,
  windows_2012,
  windows_2016,
  windows_2019,
  *
}
# == Windows

# == AIX
items in aix {
  aix_5,
  aix_6,
  aix_7,
  *
}

items in aix_5 {
  aix_5_1,
  aix_5_2,
  aix_5_3,
}

items in aix_6 {
  aix_6_1,
}

items in aix_7 {
  aix_7_1,
  aix_7_2,
  *
}
# == AIX: cfengine+wikipedia checked

items in bsd {
  dragonfly,
  freebsd,
  netbsd,
  openbsd,
  *
}

items in solaris {
  solaris_10,
  solaris_11,
  *
}

items in solaris_11 {
  solaris_11_0,
  solaris_11_1,
  solaris_11_2,
  solaris_11_3,
  solaris_11_4,
  *
}

items in linux {
  alpinelinux,
  archlinux,
  @cfengine_name="debian"
  debian_family,
  gentoo,
  @cfengine_name="Mandrake"
  mandrake,
  @cfengine_name="Mandriva"
  mandriva,
  @cfengine_name="redhat"
  redhat_family,
  slackware,
  # on sles 15 none of those are defined, rudder lib must define at least "suse"
  @cfengine_name=["suse","SuSE","SUSE"]
  suse,
  *
}

items in redhat_family {
  @cfengine_name="(redhat.!centos.!fedora.!oracle_linux.!scientific_linux.!amazon_linux)"
  rhel,
  centos,
  fedora,
  oracle_linux,
  scientific_linux,
  amazon_linux,
  # Warning: update rhel if you make change here
  *
}

items in debian_family {
  @cfengine_name="(debian.!ubuntu)"
  debian,
  ubuntu,
  # Warning: update debian if you make change here
  *
}

# == slackware
items in slackware {
  slackware_13,
  slackware_14,
  *
}

items in slackware_14 {
  slackware_14_1,
  slackware_14_2,
  *
}
# == slackware: cfengine checked

# == fedora
items in fedora {
  fedora_18,
  fedora_19,
  fedora_20,
  fedora_21,
  fedora_22,
  fedora_23,
  fedora_24,
  fedora_25,
  fedora_26,
  fedora_27,
  fedora_28,
  fedora_29,
  fedora_30,
  fedora_31,
  fedora_32,
  fedora_33,
  *
}
# == fedora: cfengine+wikipedia checked

# == suse
items in suse {
  opensuse,
  sles,
  *
}

# opensuse (versions <=13 not tested under cfengine)
items in opensuse {
  opensuse_10,
  opensuse_11,
  opensuse_12,
  opensuse_13,
  @cfengine_name=["opensuse_42", "opensuse42", "SUSE_42", "SuSE_42"]
  opensuse_42,
  @cfengine_name=["opensuse15", "opensuse_leap_15"]
  opensuse_15,
  *
}


items in opensuse_42 {
  @cfengine_name=["opensuse_42_1", "SUSE_42_1", "SuSE_42_1"]
  opensuse_42_1,
  @cfengine_name=["opensuse_42_2", "SUSE_42_2", "SuSE_42_2"]
  opensuse_42_2,
  @cfengine_name=["opensuse_42_3", "SUSE_42_3", "SuSE_42_3"]
  opensuse_42_3
}

items in opensuse_15 {
  @cfengine_name="opensuse_leap_15_0"
  opensuse_15_0,
  @cfengine_name="opensuse_leap_15_1"
  opensuse_15_1,
  @cfengine_name="opensuse_leap_15_2"
  opensuse_15_2,
  *
}

items in sles {
  # not all tested for sles10, but the first one is
  @cfengine_name=["SuSE_10", "sles_10", "SLES10", "SLES_10", "SUSE_10", "SuSE_10"]
  sles_10,
  @cfengine_name=["sles_11", "SLES11", "SLES_11", "SUSE_11", "SuSE_11"]
  sles_11,
  @cfengine_name=["sles_12", "SLES12", "SLES_12", "SUSE_12", "SuSE_12"]
  sles_12,
  # yes only sles_15 as cfengin name
  sles_15,
  *
}

items in sles_11 {
  @cfengine_name="(sles_11.!(sles_11_1|sles_11_2|sles_11_3|sles_11_4))"
  sles_11_0,
  @cfengine_name="SLES_11_1"
  sles_11_1,
  @cfengine_name="SLES_11_2"
  sles_11_2,
  @cfengine_name="SLES_11_3"
  sles_11_3,
}

items in sles_12 {
  @cfengine_name="(sles_12.!(sles_12_1|sles_12_2|sles_12_3|sles_12_4))"
  sles_12_0,
  @cfengine_name=["sles_12_1", "SLES_12_1"]
  sles_12_1,
  @cfengine_name=["sles_12_2", "SLES_12_2"]
  sles_12_2,
  @cfengine_name=["sles_12_3", "SLES_12_3"]
  sles_12_3,
  @cfengine_name=["sles_12_4", "SLES_12_4"]
  sles_12_4,
# Warning: update sles_12_0 if you make change here
  *
}

items in sles_15 {
  @cfengine_name="(sles_15.!(sles_15_1))"
  sles_15_0,
  sles_15_1,
# Warning: update sles_15_0 if you make change here
  *
}

# == sles: cfengine + wikipedia checked (except sles 10)

# == centos
items in centos {
  centos_3,
  centos_4,
  centos_5,
  centos_6,
  centos_7,
  centos_8,
  *
}

items in centos_3 {
  centos_3_0,
  centos_3_1,
  centos_3_2,
  centos_3_3,
  centos_3_4,
  centos_3_5,
  centos_3_6,
  centos_3_7,
  centos_3_8,
  centos_3_9,
}

items in centos_4 {
  centos_4_0,
  centos_4_1,
  centos_4_2,
  centos_4_3,
  centos_4_4,
  centos_4_5,
  centos_4_6,
  centos_4_7,
  centos_4_8,
  centos_4_9,
}

items in centos_5 {
  centos_5_0,
  centos_5_1,
  centos_5_2,
  centos_5_3,
  centos_5_4,
  centos_5_5,
  centos_5_6,
  centos_5_7,
  centos_5_8,
  centos_5_9,
  centos_5_10,
  centos_5_11,
}

items in centos_6 {
  centos_6_0,
  centos_6_1,
  centos_6_2,
  centos_6_3,
  centos_6_4,
  centos_6_5,
  centos_6_6,
  centos_6_7,
  centos_6_8,
  centos_6_9,
  centos_6_10,
  *
}

items in centos_7 {
  centos_7_0,
  centos_7_1,
  centos_7_2,
  centos_7_3,
  centos_7_4,
  centos_7_5,
  centos_7_6,
  centos_7_7,
  *
}

items in centos_8 {
  centos_8_0,
  centos_8_1,
  *
}
# == centos: cfengine+wikipedia checked

# == rhel
items in rhel {
  @cfengine_name="redhat_3"
  rhel_3,
  @cfengine_name="redhat_4"
  rhel_4,
  @cfengine_name="redhat_5"
  rhel_5,
  @cfengine_name="redhat_6"
  rhel_6,
  @cfengine_name=["redhat_7", "rhel_7"]
  redhat_7,
  @cfengine_name=["redhat_8", "rhel_8"]
  redhat_8,
  *
}

items in rhel_3 {
  @cfengine_name="redhat_3_0"
  rhel_3_0,
  @cfengine_name="redhat_3_1"
  rhel_3_1,
  @cfengine_name="redhat_3_2"
  rhel_3_2,
  @cfengine_name="redhat_3_3"
  rhel_3_3,
  @cfengine_name="redhat_3_4"
  rhel_3_4,
  @cfengine_name="redhat_3_5"
  rhel_3_5,
  @cfengine_name="redhat_3_6"
  rhel_3_6,
  @cfengine_name="redhat_3_7"
  rhel_3_7,
  @cfengine_name="redhat_3_8"
  rhel_3_8,
  @cfengine_name="redhat_3_9"
  rhel_3_9,
}

items in rhel_4 {
  @cfengine_name="redhat_4_0"
  rhel_4_0,
  @cfengine_name="redhat_4_1"
  rhel_4_1,
  @cfengine_name="redhat_4_2"
  rhel_4_2,
  @cfengine_name="redhat_4_3"
  rhel_4_3,
  @cfengine_name="redhat_4_4"
  rhel_4_4,
  @cfengine_name="redhat_4_5"
  rhel_4_5,
  @cfengine_name="redhat_4_6"
  rhel_4_6,
  @cfengine_name="redhat_4_7"
  rhel_4_7,
  @cfengine_name="redhat_4_8"
  rhel_4_8,
  @cfengine_name="redhat_4_9"
  rhel_4_9,
}

items in rhel_5 {
  @cfengine_name="redhat_5_0"
  rhel_5_0,
  @cfengine_name="redhat_5_1"
  rhel_5_1,
  @cfengine_name="redhat_5_2"
  rhel_5_2,
  @cfengine_name="redhat_5_3"
  rhel_5_3,
  @cfengine_name="redhat_5_4"
  rhel_5_4,
  @cfengine_name="redhat_5_5"
  rhel_5_5,
  @cfengine_name="redhat_5_6"
  rhel_5_6,
  @cfengine_name="redhat_5_7"
  rhel_5_7,
  @cfengine_name="redhat_5_8"
  rhel_5_8,
  @cfengine_name="redhat_5_9"
  rhel_5_9,
  @cfengine_name="redhat_5_10"
  rhel_5_10,
  @cfengine_name="redhat_5_11"
  rhel_5_11,
}

items in rhel_6 {
  @cfengine_name="redhat_6_0"
  rhel_6_0,
  @cfengine_name="redhat_6_1"
  rhel_6_1,
  @cfengine_name="redhat_6_2"
  rhel_6_2,
  @cfengine_name="redhat_6_3"
  rhel_6_3,
  @cfengine_name="redhat_6_4"
  rhel_6_4,
  @cfengine_name="redhat_6_5"
  rhel_6_5,
  @cfengine_name="redhat_6_6"
  rhel_6_6,
  @cfengine_name="redhat_6_7"
  rhel_6_7,
  @cfengine_name="redhat_6_8"
  rhel_6_8,
  @cfengine_name="redhat_6_9"
  rhel_6_9,
  @cfengine_name="redhat_6_10"
  rhel_6_10,
  *
}

items in rhel_7 {
  @cfengine_name=["redhat_7_0", "rhel_7_0"]
  rhel_7_0,
  @cfengine_name=["redhat_7_1", "rhel_7_1"]
  rhel_7_1,
  @cfengine_name=["redhat_7_2", "rhel_7_2"]
  rhel_7_2,
  @cfengine_name=["redhat_7_3", "rhel_7_3"]
  rhel_7_3,
  @cfengine_name=["redhat_7_4", "rhel_7_4"]
  rhel_7_4,
  @cfengine_name=["redhat_7_5", "rhel_7_5"]
  rhel_7_5,
  @cfengine_name=["redhat_7_6", "rhel_7_6"]
  rhel_7_6,
  @cfengine_name=["redhat_7_7", "rhel_7_7"]
  rhel_7_7,
  *
}

items in rhel_8 {
  @cfengine_name=["redhat_8_0", "rhel_8_0"]
  rhel_8_0,
  @cfengine_name=["redhat_8_1", "rhel_8_1"]
  rhel_8_1,
  *
}

# == rhel: cfengine + wikipedia checked

# == ubuntu
items in ubuntu {
  ubuntu_10_04,
  ubuntu_10_10,
  ubuntu_11_04,
  ubuntu_11_10,
  ubuntu_12_04,
  ubuntu_12_10,
  ubuntu_13_04,
  ubuntu_13_10,
  ubuntu_14_04,
  ubuntu_14_10,
  ubuntu_15_04,
  ubuntu_15_10,
  ubuntu_16_04,
  ubuntu_16_10,
  ubuntu_17_04,
  ubuntu_17_10,
  ubuntu_18_04,
  ubuntu_18_10,
  ubuntu_19_04,
  ubuntu_19_10,
  ubuntu_20_04,
  ubuntu_20_10,
  *
}

enum alias lucid    = ubuntu_10_04
enum alias maverick = ubuntu_10_10
enum alias natty    = ubuntu_11_04
enum alias oneiric  = ubuntu_11_10
enum alias precise  = ubuntu_12_04
enum alias quantal  = ubuntu_12_10
enum alias raring   = ubuntu_13_04
enum alias saucy    = ubuntu_13_10
enum alias trusty   = ubuntu_14_04
enum alias utopic   = ubuntu_14_10
enum alias vivid    = ubuntu_15_04
enum alias wily     = ubuntu_15_10
enum alias xenial   = ubuntu_16_04
enum alias yakkety  = ubuntu_16_10
enum alias zesty    = ubuntu_17_04
enum alias artful   = ubuntu_17_10
enum alias bionic   = ubuntu_18_04
enum alias cosmic   = ubuntu_18_10
enum alias disco    = ubuntu_19_04
enum alias eoan     = ubuntu_19_10
enum alias focal    = ubuntu_20_04

# == ubuntu: cfengine + debian wiki checked

# == debian
items in debian {
  debian_5,  # lenny
  debian_6,  # squeeze
  debian_7,  # wheezy
  debian_8,  # jessie
  debian_9,  # stretch
  debian_10, # buster
  debian_11, # bullseye
  *
}

enum alias lenny    = debian_5
enum alias squeeze  = debian_6
enum alias wheezy   = debian_7
enum alias jessie   = debian_8
enum alias stretch  = debian_9
enum alias buster   = debian_10
enum alias bullseye = debian_11

# we don't define minor for debian 5 and 6 since cfengine doesn't support it
# because they are of the form 5.0.x and 6.0.x

items in debian_7 {
  debian_7_0,
  debian_7_1,
  debian_7_2,
  debian_7_3,
  debian_7_4,
  debian_7_5,
  debian_7_6,
  debian_7_7,
  debian_7_8,
  debian_7_9,
  debian_7_10,
  debian_7_11,
}

items in debian_8 {
  debian_8_0,
  debian_8_1,
  debian_8_2,
  debian_8_3,
  debian_8_4,
  debian_8_5,
  debian_8_6,
  debian_8_7,
  debian_8_8,
  debian_8_9,
  debian_8_10,
  debian_8_11,
}

items in debian_9 {
  debian_9_0,
  debian_9_1,
  debian_9_2,
  debian_9_3,
  debian_9_4,
  debian_9_5,
  debian_9_6,
  debian_9_7,
  debian_9_8,
  debian_9_9,
  debian_9_10,
  debian_9_11,
  debian_9_12,
  *
}

items in debian_10 {
  debian_10_0,
  debian_10_1,
  debian_10_2,
  *
}

# == debian: cfengine + debian wiki checked
    