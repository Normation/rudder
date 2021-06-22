@format=0

# TODO we should only keep variables that we intend to implement fo every target agent and not only cfengine
# Otherwise they should be put in a cfengine namespace

# This file holds every cfengine special variable. These special variables are stored in a global context
# So we need to make sure our global context doesn't contain any variable having the same name + namespace
# As of now the solution we found is to be proactive by explicitly preventing the user from adding any variable included in the list
# This could also be useful in case a user wants to use any of these variables

# Latest variable list update: CFEngine 3.13
# Please keep track of the version when updating the list

# `sys` global variables
let sys.arch
let sys.bindir
let sys.cdate
let sys.cf_promises
let sys.cf_version
let sys.cf_version_major
let sys.cf_version_minor
let sys.cf_version_patch
let sys.class
let sys.cpus
let sys.crontab
let sys.date
let sys.doc_root
let sys.domain
let sys.enterprise_version
let sys.expires
let sys.exports
let sys.failsafe_policy_path
let sys.flavor
let sys.flavour
let sys.fqhost
let sys.fstab
let sys.hardware_addresses
let sys.hardware_mac
let sys.host
let sys.inet
let sys.inet6
let sys.inputdir
let sys.interface
let sys.interfaces
let sys.interfaces_data
let sys.interface_flags
let sys.ip_addresses
let sys.ip2iface
let sys.ipv4
let sys.ipv4_1
let sys.ipv4_2
let sys.ipv4_3
let sys.key_digest
let sys.last_policy_update
let sys.libdir
let sys.local_libdir
let sys.logdir
let sys.license_owner
let sys.licenses_granted
let sys.long_arch
let sys.maildir
let sys.masterdir
let sys.nova_version
let sys.os
let sys.os_release
let sys.ostype
let sys.piddir
let sys.policy_entry_basename
let sys.policy_hub
let sys.policy_entry_dirname
let sys.policy_entry_filename
let sys.policy_hub_port
let sys.release
let sys.resolv
let sys.statedir
let sys.sysday
let sys.systime
let sys.update_policy_path
let sys.uptime
let sys.user_data
let sys.uqhost
let sys.version
let sys.windir
let sys.winprogdir
let sys.winprogdir86
let sys.winsysdir
let sys.workdir

# `this` global variables
let this.bundle
let this.handle
let this.namespace
let this.promise_filename
let this.promise_dirname
let this.promise_linenumber
let this.promiser
let this.promiser_uid
let this.promiser_gid
let this.promiser_pid
let this.promiser_ppid
let this.service_policy
let this.this

# `def` global variables
let def.jq

# `edit` global variables
let edit.filename

# `const` global variables
let const.dollar
let const.dirsep
let const.endl
let const.n
let const.r
let const.t

# `connection` global variables
let connection.key
let connection.ip
let connection.hostname

# `match` global variables
# this kind of declaration doesn't work at the moment
#let match
