#####################################################################################
# Copyright 2011 Normation SAS
#####################################################################################
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, Version 3.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <http://www.gnu.org/licenses/>.
#
#####################################################################################

#
# This file launch a fusion-inventory agent
# inventory in local.
# If fusion agent is not installed,
# the installation is done
#
# Then the inventory file is improved with external informations (UUID, cf-key, VMs)

# Compute the inventory time
bundle agent computeInventoryTime
{
  vars:
      "run_interval"             int   => "&AGENT_RUN_INTERVAL&";

      "inventory_time_hours"     slist => { "Hr00", "Hr01", "Hr02", "Hr03", "Hr04", "Hr05" };
      "inventory_time_selection" slist => maplist("${this}_select", "inventory_time_hours");

  classes:
      "splaying"                   expression => splayclass("${sys.host}${sys.ipv4}","hourly");

      "inventory_run_selection" select_class => { "@{computeInventoryTime.inventory_time_selection}"};

      # If this is the default 5 minute schedule, this is the standart case
      "default_schedule" expression => isgreaterthan("6", "${run_interval}");

      # If the interval is between 6 and 59 minutes, then we "splay" by the select_class, so between Midnight and 5 am
      "less_than_one_hour_interval" expression => isgreaterthan("59", "${run_interval}");

      # Compute if we are currently in the hour where the inventory should be done
      "inventory_hour_selection" expression => "${inventory_time_hours}.${inventory_time_hours}_select";

       # Inventory will be during the night, at the hour selected, with a splay is this is the default schedule, else at the first run during the selected hour
       # if the interval is less than one hour, else at the first run of the night
       "inventory_time" expression => "Night.((splaying.default_schedule.inventory_hour_selection)|(!default_schedule.less_than_one_hour_interval.inventory_hour_selection)|(!less_than_one_hour_interval))",
                             scope => "namespace";
}

bundle agent doInventory
{
  classes:
    "disable_inventory" expression => fileexists("${g.rudder_disable_inventory_file}");
  methods:
    ignore_disable_inventory|!disable_inventory::
      "any" usebundle => doInventory_always;
    !(ignore_disable_inventory|!disable_inventory)::
      "any" usebundle => rudder_common_report("Inventory", "log_warn", "&TRACKINGKEY&", "inventory", "None", "The file ${g.rudder_disable_inventory_file} is present, so Rudder will not send an inventory. Use 'rudder agent inventory -f' to override this temporarily");
}
bundle agent doInventory_always
{

  vars:

    uuid_succesfully_downloaded::
      "policy_server_uuid" string => readfile("${sys.workdir}/rudder-server-uuid.txt", 50);

  classes:
    # The force_inventory class may be specified by the user at runtime, or defined here
      "force_inventory" expression => fileexists("${g.rudder_base}/etc/force_inventory"),
                             scope => "namespace";

    uuid_succesfully_downloaded::
      "uuid_valid" expression => regcmp("[a-z0-9-]+","${policy_server_uuid}");
      "uuid_validity_checked"
        expression => "any",
        comment    => "This dummy class is just used for ordering the report using !uuid_valid below";

    any::
      "pass3" expression => "pass2";
      "pass2" expression => "pass1";
      "pass1" expression => "any";
&if(INITIAL)&
      "initial_promises" expression => "any",
                              scope => "namespace";
&endif&

  files:
    # Clean policy server uuid file defined in 2.11 or earlier version, as location in 3.0+ is ${sys.workdir}/rudder-server-uuid.txt
    "${g.rudder_var_tmp}/uuid.txt"
      delete => tidy;

  methods:
      # Compute the inventory time
      "any" usebundle => computeInventoryTime;

    force_inventory|((inventory_time|initial_promises).!inventory_sent)::
      "any" usebundle => download_server_uuid;

    uuid_valid.(force_inventory|((inventory_time|initial_promises).!inventory_sent))::
      "any" usebundle => fusionAgent;
      "any" usebundle => listInstalledVM;
      "any" usebundle => generateExtraInformations;
      "any" usebundle => turnUsersToUnicode;
      "any" usebundle => addInformationsToInventory;
      "any" usebundle => checkInventoryFile;

    uuid_valid.(force_inventory|((inventory_time|initial_promises).!inventory_sent)).inventory_valid::
      "any" usebundle => moveInventoryToFinalDestination;
      "any" usebundle => signInventories;
      "any" usebundle => compressInventories;
      "any" usebundle => sendInventory;
      "any" usebundle => cleanForceInventoryFlagFile;

    pass3.uuid_succesfully_downloaded.uuid_validity_checked.!uuid_valid::
      "any" usebundle => rudder_common_report("Inventory", "result_error", "&TRACKINGKEY&", "inventory", "None", "Could not retrieve a valid UUID from the policy server");

    # Send reports to confirm this technique success even if we don't need to send an inventory
    pass3.inventory_sent::
      "any" usebundle => rudder_common_report("Inventory", "log_info", "&TRACKINGKEY&", "inventory", "None", "An inventory was already sent less than 8 hours ago");

    pass3.!(Night.splaying).!force_inventory::
      "any" usebundle => rudder_common_report("Inventory", "result_success", "&TRACKINGKEY&", "inventory", "None", "Next inventory scheduled between 00:00 and 06:00");

}

bundle agent download_server_uuid
{
  vars:

    # If curl is available, use it
    !windows.curl_installed::
      "download_command"   string => "${g.rudder_curl} -L -k -1 -s -f --proxy '' -o \"${sys.workdir}/rudder-server-uuid.txt\" ${g.inventory_upload_protocol}://${server_info.cfserved}/uuid";

    # If not, use minicurl instead
    !windows.!curl_installed::
      "download_command"   string => "${g.minicurl} --no-verify --get --file \"${sys.workdir}/rudder-server-uuid.txt\" --url ${g.inventory_upload_protocol}://${server_info.cfserved}/uuid";

&if(NOVA)&
    # On windows, always use curl
    windows::
      "download_command"   string => "\"${g.rudder_base_sbin}\curl\curl.exe\" -L -k -1 -s -f --noproxy '${server_info.cfserved}' -o \"${sys.workdir}\rudder-server-uuid.txt\" ${g.inventory_upload_protocol}://${server_info.cfserved}/uuid";
&endif&

  methods:
    could_not_download_uuid::
      "any" usebundle => rudder_common_report("Inventory", "result_error", "&TRACKINGKEY&", "inventory", "None", "Could not retrieve the UUID of the policy server");

  commands:

      "${download_command}"
        comment => "Getting the uuid from the server",
        classes => if_else("uuid_succesfully_downloaded","could_not_download_uuid");

}

bundle common inventory
{
  vars:
    windows::
    # Files names
      "UserListFile_cp" string => "\"${g.rudder_var_tmp}\UserList.tmp\"";
      "UserListFile"    string => "\"${g.rudder_var_tmp}\UserList.list\"";
      "cpuid_tool"      string => "\"${g.rudder_tools}\cpuid-windows-V1.0.vbs\"";

    android::
      "cpuid_tool"      string => "${g.rudder_tools}/cpuid-android-V1.0.sh";

    !aix.!windows.!android.!solaris::
      "cpuid_tool"      string => "${g.rudder_tools}/cpuid-linux-V1.0.sh";

    any::
      "CPUIDFile" string => "${g.rudder_var_tmp}/cpuid.arc";

  classes:

    windows::
      "rudder_inventory_userlist_tool_present" expression => fileexists("${g.rudder_tools}\userlist.bat");

    any::
      "rudder_inventory_cpuid_tool_present"    expression => fileexists("${cpuid_tool}");

}

bundle agent fusionAgent
{
  vars:
    SuSE.xen_dom0::
      "xen_tools_package" string => "xen-tools";
    SuSE.xen_domu_pv::
      "xen_tools_package" string => "xen-tools-domU";
    debian::
      "xen_tools_package" string => "xenstore-utils";
    !debian.!SuSE::
      "xen_tools_package" string => "xen";

    # guarding execresult to save a lot of time during policy validation
    !windows.(force_inventory|inventory_time|initial_promises)::
      "fusion_inventory_version" string => execresult("${g.rudder_base}/bin/run-inventory --version | ${paths.path[grep]} '[0-9]\.[0-9]' | ${paths.path[sed]} 's%.*(\([0-9]\+\)\.\([0-9]\+\).*).*%\1\2%'", "useshell");
      "inventory_path"           string => "${g.rudder_var_tmp}/inventory", policy => "overridable"; # Default value
      "inventory_path_edition"   string => "${g.rudder_var_tmp}/inventory/.*.ocs", policy => "overridable"; # Default value for editing the inventory files


    !windows.fusion_inventory_version_23_or_later::
      # FusionInventory 2.3+ permits to specify the exact inventory file name we want
      "inventory_path"           string => "${g.rudder_var_tmp}/inventory/${sys.uqhost}-${g.uuid}.ocs";
      "inventory_path_edition"   string => "${g.rudder_var_tmp}/inventory/${sys.uqhost}-${g.uuid}.ocs";

    windows:: # We assume we always have FusionInventory 2.3+ on Windows
      "inventory_path"           string => "${g.rudder_var_tmp}\inventory\${sys.uqhost}-${g.uuid}.ocs";
      "inventory_path_edition"   string => "${g.rudder_var_tmp}\inventory\${sys.uqhost}-${g.uuid}.ocs";

  classes:
    !windows::
      # CFEngine doesn't have a ">=" operator, so I'm using "! <" instead
      "fusion_inventory_version_23_or_later" not => islessthan("${fusion_inventory_version}", "23");

    any::
      "pass3" expression => "pass2";
      "pass2" expression => "pass1";
      "pass1" expression => "any";

  files:
    !windows|cygwin::
      "${g.rudder_var_tmp}/inventory/."
        create => "true",
        comment => "Creating inventory directory",
        classes => if_ok("inventoryfoldercreated");

      "${g.rudder_var_reports}/."
        create => "true";

&if(NOVA)&
    windows.!cygwin::
      "${g.rudder_var_tmp}\inventory\."
        create => "true",
        comment => "Creating inventory directory",
        classes => if_ok("inventoryfoldercreated");

      "${g.rudder_var_reports}\."
        create => "true";
&endif&

  packages:
    xen.!redhat::
      "${xen_tools_package}"
        package_policy => "add",
        package_method => generic,
        classes        => cf2_if_else("xen_installed", "cant_install_xen"),
        comment        => "Installing xen package for extended data";

    xen.redhat::
      "${xen_tools_package}"
        package_policy => "add",
        package_method => rudder_yum,
        classes        => cf2_if_else("xen_installed", "cant_install_xen"),
        comment        => "Installing xen package for extended data";

  methods:
    pass3.run_inventory::
      "any" usebundle => rudder_common_report("Inventory", "log_debug", "&TRACKINGKEY&", "inventory", "None", "Running inventory");

    pass3.inventory_failed::
      "any" usebundle => rudder_common_report("Inventory", "result_error", "&TRACKINGKEY&", "inventory", "None", "Could not execute the inventory");

    pass3.cant_install_curl::
      "any" usebundle => rudder_common_report("Inventory", "result_error", "&TRACKINGKEY&", "inventory", "None", "Could not install curl");

    pass3.cant_install_ocs::
      "any" usebundle => rudder_common_report("Inventory", "result_error", "&TRACKINGKEY&", "inventory", "None", "Could not install ocs");

    pass3.cant_install_xen::
      "any" usebundle => rudder_common_report("Inventory", "result_error", "&TRACKINGKEY&", "inventory", "None", "Could not install Xen utils on Xen system");

    pass3.xen_installed::
      "any" usebundle => rudder_common_report("Inventory", "log_info", "&TRACKINGKEY&", "inventory", "None", "Xen utils installed");

    pass3.curl_installed::
      "any" usebundle => rudder_common_report("Inventory", "log_info", "&TRACKINGKEY&", "inventory", "None", "Curl installed");

  commands:
    !windows.inventoryfoldercreated::
      "${g.rudder_base}/bin/run-inventory --local=${inventory_path}"
        classes => cf2_if_else("run_inventory", "inventory_failed"),
        comment => "Generating inventory, in the temporary folder";

&if(NOVA)&
    windows::
      "\"C:\Program Files\Rudder\sbin\run-inventory.bat\""
        args => "--local=\"${inventory_path}\" 2>nul",
        contain => in_shell,
        classes => cf2_if_else("run_inventory", "inventory_failed"),
        comment => "Generating inventory";
&endif&

}

# List all installed VM on the machine (based on VirtualBox)
# CAUTION : Issue with path containing a whitespace, it's not working with windows
bundle agent listInstalledVM
{
  classes:
    any::
      "pass3" expression => "pass2";
      "pass2" expression => "pass1";
      "pass1" expression => "any";

  files:
    VirtualBoxInstalled::
      "${virtualMachines.VBoxListFile}"
        create => "true",
        edit_line => xmlify(${virtualMachines.VBoxAttr}),
        comment => "Converting file into pseudo XML";

    VMWareInstalled::
      "${virtualMachines.VMWareListFile}"
        create => "true",
        edit_line => xmlify(${virtualMachines.VMWareAttr}),
        comment => "Converting file into pseudo XML";


  methods:
    pass3.cant_list_vm::
      "any" usebundle => rudder_common_report("Inventory", "log_warn", "&TRACKINGKEY&", "inventory", "None", "Could not list installed VMWare machines");

    pass3.cant_list_vb::
      "any" usebundle => rudder_common_report("Inventory", "log_warn", "&TRACKINGKEY&", "inventory", "None", "Could not list installed VirtualBox machines");

  commands:
    !windows.VirtualBoxInstalled::
      "/usr/bin/VBoxManage"
        args => "-q list vms > ${virtualMachines.VBoxListFile}",
        contain => outputable,
        classes => cf2_if_else("vb_listed", "cant_list_vb"),
        comment => "Generating file with list of VM";

&if(NOVA)&
    windows.VirtualBoxInstalled::
      "\"${virtualMachines.virtual_box_install_path}VBoxManage.exe\""
        args => "-q list vms > \"${virtualMachines.VBoxListFile}\"",
        contain => outputable,
        classes => cf2_if_else("vb_listed", "cant_list_vb"),
        comment => "Generating file with list of VM";
&endif&

    !windows.VMWareInstalled::
      "${virtualMachines.VMWareScript}"
        contain => outputable,
        args => " > \"${virtualMachines.VMWareListFile}\"",
        classes => cf2_if_else("vm_listed", "cant_list_vm"),
        comment => "Generating file with list of VM";

&if(NOVA)&
    windows.VMWareInstalled::
      "${virtualMachines.VMWareScript}"
        contain => outputable,
        args => " > ${virtualMachines.VMWareListFile}",
        classes => cf2_if_else("vm_listed", "cant_list_vm"),
        comment => "Generating file with list of VM";
&endif&

}

bundle agent generateExtraInformations
{
  classes:
    any::
      "pass3" expression => "pass2";
      "pass2" expression => "pass1";
      "pass1" expression => "any";

  methods:
    pass3.userlist::
      "any" usebundle => rudder_common_report("Inventory", "log_debug", "&TRACKINGKEY&", "inventory", "None", "Generated the userlist");
    pass3.cpuid::
      "any" usebundle => rudder_common_report("Inventory", "log_debug", "&TRACKINGKEY&", "inventory", "None", "Generated the CPUID");


    pass3.userlist_fail::
      "any" usebundle => rudder_common_report("Inventory", "result_error", "&TRACKINGKEY&", "inventory", "None", "Could not generate the user list");

    pass3.cpuid_fail::
      "any" usebundle => rudder_common_report("Inventory", "result_error", "&TRACKINGKEY&", "inventory", "None", "Could not generate the CPUID");

    pass3.!rudder_inventory_userlist_tool_present::

      "any" usebundle => rudder_common_report("Inventory", "log_debug", "&TRACKINGKEY&", "inventory", "None", "User list generation tool is not present yet. Skipping...");

    pass3.!rudder_inventory_cpuid_tool_present::

      "any" usebundle => rudder_common_report("Inventory", "log_debug", "&TRACKINGKEY&", "inventory", "None", "CPUID generation tool not available for this platform, or not present yet. Skipping...");

  commands:

&if(NOVA)&
    windows.rudder_inventory_userlist_tool_present::
      "\"${g.rudder_tools}\userlist.bat\""
        args => " > ${inventory.UserListFile_cp} ",
        contain => outputable,
        classes => cf2_if_else("userlist", "userlist_fail"),
        comment => "Generating file with list of users";

    windows.rudder_inventory_cpuid_tool_present::

      "${sys.winsysdir}\cscript.exe"
        args => "/Nologo \"${g.rudder_tools}/cpuid-windows-V1.0.vbs\" > \"${inventory.CPUIDFile}\"",
        contain => outputable,
        classes => cf2_if_else("cpuid", "cpuid_fail"),
        comment => "Generating file with CPUID information";
&endif&

    !windows.rudder_inventory_cpuid_tool_present::
      "${inventory.cpuid_tool}"
        args => " > ${inventory.CPUIDFile}",
        contain => outputable,
        classes => cf2_if_else("cpuid", "cpuid_fail"),
        comment => "Generating file with CPUID information";

}

bundle agent turnUsersToUnicode
{

&if(NOVA)&
  classes:
    any::
      "pass3" expression => "pass2";
      "pass2" expression => "pass1";
      "pass1" expression => "any";

  methods:

    pass3.windows::
      "any" usebundle => rudder_common_report("Inventory", "log_debug", "&TRACKINGKEY&", "inventory", "None", "This is a windows machine. User list has been converted to Unicode");

  commands:
    windows::
      "\"${g.rudder_tools}\iconv.exe\""
        args => " -f CP850 -t UTF-8 ${inventory.UserListFile_cp} > ${inventory.UserListFile} ",
        contain => outputable_dir("${g.rudder_tools}"),
        classes => cf2_if_else("userlist", "userlist_fail"),
        comment => "Generating file with list of users in UTF";

&endif&

}

# adding data to the inventory :
# UUID and CFKey in <DEVICEID>,
# list of VM in <CONTENT>
bundle agent addInformationsToInventory
{
  vars:
    # define CFKEY
    windows.!cygwin::
      "CFKEY_cmd"      string => "${sys.winsysdir}\cmd.exe /c \"type \"${sys.workdir}\ppkeys\localhost.pub\"\"";
    windows.cygwin::
      "CFKEY_cmd"      string => "/usr/bin/cat ${sys.workdir}/ppkeys/localhost.pub";
    android::
      "CFKEY_cmd"      string => "/system/bin/cat ${sys.workdir}/ppkeys/localhost.pub";
    !windows.!android::
      "CFKEY_cmd"      string => "/bin/cat ${sys.workdir}/ppkeys/localhost.pub";
    any::
      "CFKEY"          string => execresult("${CFKEY_cmd}", "noshell");

    # define USER
    windows.!cygwin::
      "USER"           string => getenv("USERNAME", 40);
    windows.cygwin::
      "USER"           string => execresult("/usr/bin/whoami", "noshell");
    android::
      "USER"           string => execresult("/system/xbin/whoami", "noshell");
    solaris::
      "USER"           string => execresult("/usr/bin/id | /usr/bin/cut -d\( -f2 | /usr/bin/cut -d\) -f1", "noshell");
    !windows.!android.!solaris::
      "USER"           string => execresult("/usr/bin/whoami", "noshell");

    # define RUDDERUUID
    windows.!cygwin::  
      "RUDDERUUID"     string => execresult("${sys.winsysdir}\cscript.exe /Nologo \"${g.rudder_tools}/uuid.vbs\"", "noshell");
    windows.cygwin::
      "RUDDERUUID"     string => execresult("${sys.winsysdir}\cscript.exe /Nologo ${g.rudder_tools}/uuid.vbs", "noshell");
    android::
      "RUDDERUUID"     string => execresult("/system/xbin/sqlite3 /data/data/com.android.providers.settings/databases/settings.db \"select value from secure where name = 'android_id'\"", "noshell");
    aix::
      "RUDDERUUID"     string => execresult("/usr/bin/uname -f", "noshell");
    solaris::
      "RUDDERUUID"     string => execresult("smbios | grep UUID | sed 's/  UUID: //'", "useshell");
    freebsd::
      "RUDDERUUID"     string => execresult("/usr/local/sbin/dmidecode -s system-uuid", "noshell");
    linux.!xen::
      "RUDDERUUID"     string => execresult("/usr/sbin/dmidecode -s system-uuid", "noshell");
    linux.xen.xenrudderuuid:: # xenrudderuuid is defined at second pass
      "RUDDERUUID"     string => "${vmarray[1]}";
    # all cases should have been covered, so no default


    # define VMRUDDERUUID when we have xen
    xen.SuSE.xen_domu_pv::
      "VMRUDDERUUID_cmd" string => "/bin/xenstore-read vm";
    xen.((SuSE.xen_dom0)|centos|redhat)::
      "VMRUDDERUUID_cmd" string => "/usr/bin/xenstore-read vm";
    xen.!SuSE.!centos.!redhat::
      "VMRUDDERUUID_cmd" string => "/usr/sbin/xenstore-read vm";
    xen::
      "VMRUDDERUUID"     string => execresult("${VMRUDDERUUID_cmd}", "noshell");

    # define users
    windows.!cygwin::  
      # Somehow, using the variable rather than the path fails with readstringlist
      "users"          slist => { readstringlist("C:\Program Files\Rudder\var\tmp\UserList.list","#.*","[\n| |\r]",50,4000) };
    windows.cygwin::
      "users"          slist => { readstringlist("${inventory.UserListFile}","#.*","[\n| |\r]",10,4000) };
    android::
      "users"          slist => {"root"};
    !windows.!android::
      "usersnumber"    int =>  readstringarray("userslist","/etc/passwd","#[^\n]*",":",50,16000);
      "users"          slist => getindices("userslist");


    # define specific variables
    android::
      "android_kernelname"    string => "linux";
      "android_kernelversion" string => execresult("/system/xbin/uname -r", "noshell");
      "android_name"          string => "Android";
      "android_version"       string => execresult("/system/bin/getprop ro.build.version.release", "noshell");
      "android_fullname"      string => "Android ${android_version}";
      "logdate"               string => execresult("/system/bin/date '+%Y-%m-%d %H:%M:%S'", "noshell");

    linux.(!xen.!armv6l.!armv7l)::
      "RUDDERUUID_cmd" string => "/usr/sbin/dmidecode -s system-uuid";

    aix::
      "RUDDERUUID_cmd" string => "/usr/bin/uname -f";

    solaris::
      "RUDDERUUID" string => execresult("smbios | grep UUID | sed 's/  UUID: //'", "useshell");

    freebsd::
      "RUDDERUUID_cmd" string => "/usr/local/sbin/dmidecode -s system-uuid";

    !windows.!android::
      "CFKEY_cmd"      string => "/bin/cat ${sys.workdir}/ppkeys/localhost.pub";
      "usersnumber"    int =>  readstringarray("userslist","/etc/passwd","#[^\n]*",":",50,16000);
      "users"          slist => getindices("userslist");

    !windows.!android.!solaris::
      "USER" string => execresult("/usr/bin/whoami", "noshell");

    solaris::
      "USER" string => execresult("id | cut -d\( -f2 | cut -d\) -f1", "useshell");

    any::
      "CFKEY"      string => execresult("${CFKEY_cmd}", "noshell");

      "USER"       string => execresult("${USER_cmd}", "noshell");

    rudder_server_roles_dir_exists::
      # Roles of the system, fetch from the server_roles_path folder
      # Don't extract file starting by . (especially . and .., but also hidden files)
      "rudder_roles" slist => lsdir("${g.server_roles_path}", "^[^.].*", "false");

    linux.(armv6l|armv7l)::
      "ARMCPUINFO" string => readfile( "/proc/cpuinfo", "0" );

    linux.(armv6l|armv7l).armv6lorv7lrudderuuid::
      "RUDDERUUID" string => "${serialarray[1]}";

    !xen.!armv6l.!armv7l::
      "RUDDERUUID" string => execresult("${RUDDERUUID_cmd}", "noshell");


  classes:
    xen::
      "xenrudderuuid" expression => regextract("/vm/(.*)", "${VMRUDDERUUID}", "vmarray");

    linux.(armv6l|armv7l)::
      "armv6lorv7lrudderuuid" expression => regextract("Serial[\s]+:[\s](.*)\N", "${ARMCPUINFO}", "serialarray");

    any::
      "uuiddefined" expression => isvariable("RUDDERUUID");

  files:
    uuiddefined::
      "${fusionAgent.inventory_path_edition}"
        edit_line => add_information_to_inventory(${RUDDERUUID}, ${CFKEY}, ${USER}, ${doInventory_always.policy_server_uuid}),
        comment => "Adding basic informations to Rudder Inventory",
        edit_defaults => def_no_backup_size("25M");

      "${fusionAgent.inventory_path_edition}"
        edit_line => add_users_information_to_inventory(@{addInformationsToInventory.users}),
        comment => "Adding user informations to Rudder Inventory",
        edit_defaults => def_no_backup_size("25M");

    uuiddefined.rudder_server_roles_dir_exists::
      "${fusionAgent.inventory_path_edition}"
        edit_line  => add_server_roles_information_to_inventory("@{addInformationsToInventory.rudder_roles}"),
        comment => "Adding server roles informations to Rudder Inventory",
        edit_defaults => def_no_backup_size("25M");

    android::
      "${g.rudder_var_tmp}/inventory/.*.ocs"
        edit_line => add_information_to_android_inventory(${android_fullname}, ${android_kernelname}, ${android_kernelversion}, ${android_name}, ${android_version}),
        edit_defaults => def_no_backup_size("25M");

      "${g.rudder_var_tmp}/inventory/.*.ocs"
        edit_line => add_accesslog_to_android_inventory(${logdate}),
        edit_defaults => def_no_backup_size("25M");
}

# Check that the inventory file is valid
bundle agent checkInventoryFile
{
  vars:
      "inventory_file"     slist => findfiles("${g.rudder_var_tmp}/inventory/*.ocs");
      "inventory_file_count" int => length("inventory_file");
    !windows.has_rudder_perl::
      "perl_command" string => "/opt/rudder/bin/perl -I /opt/rudder/lib/perl5";
    !windows.!has_rudder_perl::
      "perl_command" string => "/usr/bin/perl";
    windows::
      "perl_command" string => "C:\Program Files\FusionInventory-Agent\perl\bin\perl.exe";

  classes:
      # To check if inventory file is there, we need to count the file (filexists is not enough if inventory_file is an empty list))
      "inventory_file_exist" expression => isgreaterthan("${inventory_file_count}", "0");
      "has_rudder_perl" expression => fileexists("/opt/rudder/bin/perl");
    pass1.inventory_file_exist.!windows::
      "inventory_valid" expression => returnszero("${perl_command} ${this.promise_dirname}/test-inventory.pl ${inventory_file}", "noshell"),
                        scope => "namespace";

    pass1.inventory_file_exist.windows::
      "inventory_valid" expression => returnszero("\"${perl_command}\" \"${this.promise_dirname}/test-inventory.pl\" \"${inventory_file}\"", "noshell"),
                        scope => "namespace";

    any::
      "pass2" expression => "pass1";
      "pass1" expression => "any";

  methods:
    pass2.inventory_file_exist.!inventory_valid::
      "any" usebundle => rudder_common_report("Inventory", "result_error", "&TRACKINGKEY&", "inventory", "None", "Generated inventory has been detected as invalid");

  reports:
    pass2.inventory_file_exist.!inventory_valid::
      # Print information to the user in addition to the reporting
      "********************************************************************************
* WARNING Generated inventory is not valid, as it is missing mandatory fields. *
* Not sending it to the Rudder Server                                          *
* You can get a full error message by running: ${perl_command} ${this.promise_dirname}/test-inventory.pl ${inventory_file} 
********************************************************************************";
}

# Move the inventory file in the shared directory
bundle agent moveInventoryToFinalDestination
{
  files:
      "${g.rudder_inventories}"
        copy_from    => copy("${g.rudder_var_tmp}/inventory"),
        depth_search => recurse_visible(1),
        file_select  => by_name("@{g.inventory_file_types}"),
        comment      => "Moving inventory files to the final location";
}

# sign inventories
bundle agent signInventories
{
  vars:

    !windows::
      # Keep "/opt/rudder/bin/signature.sh" as a fallback until we don't support agents < 4.0 anymore
      "sign_script" string => ifelse(fileexists("${g.rudder_base}/bin/rudder-sign"), "${g.rudder_base}/bin/rudder-sign", "${g.rudder_base}/bin/signature.sh");
    windows::
      "sign_script" string => "C:\Windows\System32\WindowsPowershell\v1.0\powershell.exe -ExecutionPolicy Bypass -File \"C:\Program Files\Rudder\sbin\signature.ps1\" -file";

  classes:
      "sign_script_exists" expression => fileexists("${sign_script}");

  files:

    !windows.sign_script_exists::
      "${g.rudder_inventories}"
        transformer  => "${sign_script} \"${this.promiser}\"",
        depth_search => recurse_visible(1),
        file_select  => by_name("@{g.uncompressed_inventory_file_types}"),
        comment      => "Signing inventory files";
}

# compress inventories if possible
bundle agent compressInventories
{
  files:

    !windows.gzip_installed::

      "${g.rudder_inventories}"
        transformer  => "${g.gzip} -fq ${this.promiser}",
        depth_search => recurse_visible(1),
        file_select  => by_name("@{g.uncompressed_inventory_file_types}"),
        comment      => "gzip inventory files";
}

# Send the file to the promises server
bundle agent sendInventory
{

  vars:
&if(INITIAL)&
      "download_endpoint"         string => "${g.inventory_upload_protocol}://${server_info.cfserved}/inventories/";
&else&
      "download_endpoint"         string => "${g.inventory_upload_protocol}://${server_info.cfserved}/inventory-updates/";
&endif&

    # If curl is available, use it
    !windows.curl_installed::
      "download_command_prefix"   string => "${g.rudder_curl} -L -k -1 -f -s --proxy '' --user ${g.davuser}:${g.davpw} -T";
      "download_command_suffix"   string => "${download_endpoint}";

    # If not, use minicurl instead
    !windows.!curl_installed::
      "download_command_prefix"   string => "${g.minicurl} --no-verify --put --authentication ${g.davuser}:${g.davpw} --file";
      "download_command_suffix"   string => "--url ${download_endpoint}";

&if(NOVA)&
    # On windows, always use curl
    windows::
      "download_command_prefix"   string => "${g.rudder_curl} -L -k -1 -f -s --noproxy '${server_info.cfserved}' --user ${g.davuser}:${g.davpw} -T";
      "download_command_suffix"   string => "${download_endpoint}";
&endif&

  classes:
    any::
      "pass3" expression => "pass2";
      "pass2" expression => "pass1";
      "pass1" expression => "any";

  files:

    !windows::
      "${g.rudder_inventories}"
        transformer => "${download_command_prefix} ${this.promiser} ${download_command_suffix}",
        depth_search => recurse_visible(1),
        file_select  => by_name("@{g.signed_inventory_file_types}"),
        classes      => persistant_class("inventory_sent", "cant_send_inventory", 480),
        comment      => "Sending the inventory to the server";

&if(NOVA)&
    # On windows, the this.promiser variable is not evaluated the same way. We are forced to duplicate this block
    windows::
      "${g.rudder_inventories}"
        transformer => "${download_command_prefix} \"${this.promiser}\" ${download_command_suffix}",
        depth_search => recurse_visible(1),
        file_select => by_name("@{g.signed_inventory_file_types}"),
        classes => persistant_class("inventory_sent", "cant_send_inventory", 480),
        comment => "Sending the inventory to the server";
&endif&

  # Once we've successfully sent all inventories, remove them
    !windows.inventory_sent.!cant_send_inventory::
      "${g.rudder_inventories}"
        transformer  => "${g.rudder_rm} -f ${this.promiser}",
        depth_search => recurse_visible(1),
        file_select  => by_name("@{g.signed_inventory_file_types}"),
        classes      => if_else("inventory_file_deleted", "cant_delete_inventory_file"),
        comment      => "Cleaning up inventory files already sent to the server";

      "${g.rudder_var_tmp}/inventory_sent"
        create       => "true",
        touch        => "true",
        comment      => "Create local info about successful upload";

      "${g.rudder_var_tmp}/inventory"
        transformer  => "${g.rudder_rm} -f ${this.promiser}",
        depth_search => recurse_visible(1),
        file_select  => by_name("@{g.signed_inventory_file_types}"),
        classes      => if_else("inventory_file_deleted", "cant_delete_inventory_file"),
        comment      => "Cleaning up inventory files already sent to the server";

  methods:
    pass3.inventory_sent::
      "any" usebundle => rudder_common_report("Inventory", "result_success", "&TRACKINGKEY&", "inventory", "None", "The inventory has been successfully sent");

    pass3.cant_send_inventory::
      "any" usebundle => rudder_common_report("Inventory", "result_error", "&TRACKINGKEY&", "inventory", "None", "Could not send the inventory");

    pass3.cant_delete_inventory_file::
      "any" usebundle => rudder_common_report("Inventory", "log_warn", "&TRACKINGKEY&", "inventory", "None", "Could not delete inventory file after sending to server");

}

#####################################################
#Adding the list of Virtual Machines into the report
#Adding the ids in the report
#####################################################
bundle edit_line add_information_to_inventory(RUDDERUUID, CFKEY, USER, POLSRVUUID)
{
  insert_lines:
      "<UUID>${g.uuid}</UUID>${const.n}<USER>${USER}</USER>${const.n}<AGENTSNAME>${const.n}</AGENTSNAME>${const.n}<MACHINEID>${RUDDERUUID}</MACHINEID>${const.n}<CFKEY>${CFKEY}</CFKEY>${const.n}<POLICY_SERVER>${POLSRVUUID}</POLICY_SERVER>${const.n}<SERVER_ROLES>${const.n}</SERVER_ROLES>${const.n}"
        location    => after_deviceid,
        insert_type => "preserve_block",
        comment     => "Add the UUID and CFKEY tags in the inventory file";

    any::
      "<VMS>${const.n}</VMS>${const.n}<USERSLIST>${const.n}</USERSLIST>"
        insert_type => "preserve_block",
        location    => after_content;

    rudder_inventory_cpuid_tool_present.!aix::

      "${inventory.CPUIDFile}"
        insert_type => "file",
        location => after_location("<POLICY_SERVER>"),
        comment => "Adding the CPUID data in the inventory file";

    nova_edition::
      "<AGENTNAME>Nova</AGENTNAME>"
        location => after_location("<AGENTSNAME>"),
        comment => "Adding the agent data in the inventory file";

    community_edition::
      "<AGENTNAME>Community</AGENTNAME>"
        location => after_location("<AGENTSNAME>"),
        comment => "Adding the agent data in the inventory file";

    VirtualBoxInstalled::
      "${virtualMachines.VBoxListFile}"
        insert_type => "file",
        location => after_location("<VMS>"),
        comment => "Adding the list of VM in the inventory file";

    VMWareInstalled::
      "${virtualMachines.VMWareListFile}"
        insert_type => "file",
        location => after_vm,
        comment => "Adding the list of VM in the inventory file";
}

bundle edit_line add_information_to_android_inventory(fullname, kernelname, kernelversion, name, version)
{
  insert_lines:

    android::
      "<OPERATINGSYSTEM>${const.n}<FULL_NAME>${fullname}</FULL_NAME>${const.n}<KERNEL_NAME>${kernelname}</KERNEL_NAME>${const.n}<KERNEL_VERSION>${kernelversion}</KERNEL_VERSION>${const.n}<NAME>${name}</NAME>${const.n}<VERSION>${version}</VERSION>${const.n}</OPERATINGSYSTEM>"
        location    => after_location("<CONTENT>"),
        insert_type => "preserve_block",
        comment     => "Adding the list of OPERATINGSYSTEM part";
}

#this bundle is necessary for OCSInventory (but not for fusioninventory)
bundle edit_line add_accesslog_to_android_inventory(logdate)
{
  insert_lines:
    android::
      "<ACCESSLOG><LOGDATE>${logdate}</LOGDATE></ACCESSLOG>"
        location => after_location("</OPERATINGSYSTEM>"),
        comment => "Adding log date";
}

bundle edit_line add_users_information_to_inventory(userlist)
{
  insert_lines:

    (windows.rudder_inventory_userlist_tool_present)|!windows::

      "<USER>${userlist}</USER>${const.n}" location => after_users,
        comment => "Add the UUID and CFKEY tags in the inventory file";
}

bundle edit_line add_server_roles_information_to_inventory(server_roles)
{
  insert_lines:
      "<SERVER_ROLE>${server_roles}</SERVER_ROLE>"
        location => after_location("<SERVER_ROLES>"),
        comment  => "Adding server role to inventory";
}

#Locators
body location after_location(pos)
{
        select_line_matching => ".*${pos}.*";
        before_after => "after";
}

body location after_deviceid
{
        select_line_matching => ".*<DEVICEID>.*";
        before_after => "after";
}

body location after_content
{
        select_line_matching => ".*<CONTENT>.*";
        before_after => "after";
}
body location after_vm
{
        select_line_matching => ".*<VMS>.*";
        before_after => "after";
}
body location after_users
{
        select_line_matching => ".*<USERSLIST>.*";
        before_after => "after";
}

###############
# Editors
##############
# Convert a simple list in <VM TYPe="vmtype"><NAME>machine_name</NAME><UUID>value<UUID></VM>
bundle edit_line xmlify(ATTR)
{
  replace_patterns:
      "\"(.*)\" \{(.*)\}"
      replace_with=> xmled(${ATTR});

}

body replace_with xmled(attribute)
{
        replace_value => "<VM TYPE=\"${attribute}\"><NAME>${match.1}</NAME><UUID>${match.2}</UUID></VM>";
}


body perms inventory_perms
{
        mode => "0700";
}

body file_select one_day_age
#
# we can build old "include", "exclude", and "ignore"
# from these as standard patterns - these bodies can
# form a library of standard patterns
#

{
        mtime     => irange(ago(1,0,0,0,0,0),ago(0,0,1,0,0,0));
        file_result => "mtime";
}

bundle agent cleanForceInventoryFlagFile
{

  files:
      "${g.rudder_base}/etc/force_inventory"
        delete     => tidy,
        ifvarclass => "inventory_sent"; # if the force inventory file was present, and we successfully sent an inventory, clean up the flag file

}
