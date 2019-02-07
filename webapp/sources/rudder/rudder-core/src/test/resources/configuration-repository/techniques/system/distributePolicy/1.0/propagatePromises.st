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
# Fetch the promises from the server to be available for others machines
# Must not do it on the RootServer though...


bundle agent propagatePromises
{

  vars:
    any::
      "server_data" string => "${server_info.policy_files}/share"; #actual directory with data to propagate
      "client_data" string => "${g.rudder_var}/share/";  #where to put the files on the client when downloaded

    pass2.root_server::
      "ncf_common_sha_sum" string => execresult("${paths.path[find]} ${g.rudder_ncf_origin_common} -not -name ${g.rudder_ncf_hash_file} -type f -print0  | ${paths.path[sort]} -z | /usr/bin/xargs -0 /usr/bin/sha1sum | /usr/bin/sha1sum", "useshell");
      "ncf_local_sha_sum"  string => execresult("${paths.path[find]} ${g.rudder_ncf_origin_local} -not -name ${g.rudder_ncf_hash_file} -type f -print0  | ${paths.path[sort]} -z | /usr/bin/xargs -0 /usr/bin/sha1sum | /usr/bin/sha1sum", "useshell");

    rudder_tools_updated_exists::
      "tools_timestamp"   string => readfile("${g.rudder_tools_updated_origin}", 2048);

  classes:
    # Ensure the validity of the timestamp file for tools
    rudder_tools_updated_exists::
      "tools_timestamp_invalid" not => regcmp("[0-9]+", "${tools_timestamp}");

    root_server::
      "rudder_tools_updated_exists" expression => fileexists("${g.rudder_tools_updated_origin}");

    any::
      "pass3" expression => "pass2";
      "pass2" expression => "pass1";
      "pass1" expression => "any";

    # Class definition for no propagate promises transfer, but correct promises transfer -> folder for promises for relay is empty on the server, or folder inexistent
    pass3::
     "update_successful"                expression => "root_server|((rudder_ncf_hash_update_ok|(rudder_ncf_hash_update_repaired.rudder_ncf_common_updated_ok.rudder_ncf_local_updated_ok)).(rudder_promises_generated_tmp_file_kept|(rudder_promises_generated_tmp_file_repaired.config_ok)).!(rudder_promises_generated_tmp_file_error|rudder_tools_updated_error|rudder_tools_update_error|rudder_ncf_common_update_error|rudder_ncf_local_update_error|no_update|rudder_ncf_hash_update_error))";

     "empty_promises_to_propagate"      expression => "update_successful.!promises_propagated";
     "promises_to_propagate_not_copied" expression => "!update_successful.!promises_propagated";

     "sharedfiles_not_existent" expression => "update_successful.!shared_files_propagated"; # If shared-files doesn't exist, error class is defined, if it is empty, no class is defined
     "sharedfiles_not_copied"   expression => "!update_successful.could_not_propagate_shared_files";


  files:

    root_server::

      "${g.rudder_tools}"
        copy_from    => copy("${g.rudder_base}/share/tools"),
        depth_search => recurse_visible("inf"),
        comment => "Fetching the tools for the promises execution",
        classes => classes_generic("propagate_tools");

      "${g.rudder_ncf_origin_local}/ncf.conf"
        copy_from    => copy_digest_without_perms("${sys.workdir}/inputs/distributePolicy/ncf/ncf.conf"),
        comment      => "Enforce the ncf configuration file",
        classes      => classes_generic("configure_ncf_config");

    (policy_server|role_rudder_relay_promises_only).!root_server::

      "${client_data}"  #that's a loop on each files in client_inputs
        copy_from    => remote("${server_info.cfserved}","${server_data}"),
        depth_search => recurse_visible("inf"),
        comment => "Fetching the promises to propagate",
        classes => if_else("promises_propagated", "could_not_propagate_promise");

      "${g.rudder_var}/shared-files/"
        copy_from    => remote("${server_info.cfserved}","${g.rudder_var}/shared-files/${g.uuid}/shared-files"),
        depth_search => recurse_visible("inf"),
        comment => "Fetching files shared between agents",
        classes => if_else("shared_files_propagated", "could_not_propagate_shared_files");

      "${g.rudder_ncf_origin_local}"
        copy_from    => remote("${server_info.cfserved}","${g.rudder_ncf_origin_local}"),
        depth_search => recurse_visible("inf"),
        comment      => "Fetching the ncf local to propagate",
        classes      => if_else("ncf_local_promises_propagated", "could_not_propagate_ncf_local_promise");

      "${g.rudder_ncf_origin_common}"
        copy_from    => remote_unsecured_without_perms("${server_info.cfserved}","${g.rudder_ncf_origin_common}"),
        depth_search => recurse_visible("inf"),
        comment      => "Fetching the ncf common to propagate",
        classes      => if_else("ncf_common_promises_propagated", "could_not_propagate_ncf_common_promise");

      "&SHARED_FILES_FOLDER&"
        copy_from    => remote("${server_info.cfserved}","&SHARED_FILES_FOLDER&"),
        depth_search => recurse_visible("inf"),
        comment => "Fetching the files shared for the promises execution",
        classes => if_else("files_propagated", "could_not_propagate_files");

      "${g.rudder_tools}"
        copy_from    => remote_unsecured("${server_info.cfserved}","${g.rudder_tools}"),
        depth_search => recurse_visible("inf"),
        comment => "Fetching the tools for the promises execution",
        classes => classes_generic("propagate_tools");

      "${sys.workdir}/masterfiles"
        copy_from    => remote("${server_info.cfserved}","${sys.workdir}/masterfiles"),
        depth_search => recurse_visible("inf"),
        file_select => no_license_dat, #We don't want to propagate a wrong license.dat
        comment => "Fetching the bootstrap promises",
        classes => if_else("masterfiles_propagated", "could_not_propagate_masterfiles");

&if(NOVA)&
      "${sys.workdir}/masterfiles/license.dat"
        copy_from    => local_cp("${sys.workdir}/inputs/license.dat"),
        comment      => "Putting the right license in the bootstrap",
        classes      => if_else("license_copied", "could_not_copy_license");
&endif&

    # If rudder_tools_updated doesn't exist, or if the tools have been updated,
    # set rudder_tools_updated content to current timestamp
    root_server.(!rudder_tools_updated_exists|propagate_tools_repaired|tools_timestamp_invalid)::
      "${g.rudder_tools_updated_origin}"
        create        => "true",
        edit_defaults => empty,
        edit_line     => insert_lines("${sys.systime}"),
        comment       => "Update the timestamp in the rudder tool updated file";

    # each run, update the hash files of ncf
    # these hashes are used by node to know if they should update their copy of ncf
    root_server.pass2::
      "${g.rudder_ncf_origin_common_hash}"
        create        => "true",
        edit_defaults => empty,
        edit_line     => insert_lines("${ncf_common_sha_sum}"),
        comment       => "Update the hash of ncf common";

      "${g.rudder_ncf_origin_local_hash}"
        create        => "true",
        edit_defaults => empty,
        edit_line     => insert_lines("${ncf_local_sha_sum}"),
        comment       => "Update the hash of ncf local";

    policy_server::

      "${g.rudder_base}/etc/nodeslist.json"
        copy_from    => local_cp("${this.promise_dirname}/nodeslist.json"),
        perms        => mog("640", "root", "rudder"),
        comment => "Copying list of nodes under this policy server",
        classes => if_else("nodeslist_copied", "could_not_copy_nodeslist");

  methods:
    root_server::
      "any" usebundle => rudder_common_reports_generic("DistributePolicy", "configure_ncf_config", "&TRACKINGKEY&", "Configure ncf", "None", "Configure configuration library");

    !root_server::
      "any" usebundle => rudder_common_report("DistributePolicy", "result_na", "&TRACKINGKEY&", "Configure ncf", "None", "Configuration of configuration library is not necessary on non root servers");

    # Success if files are updated or not changed (kept or repaired).
    # root server have only tools to be updated and others have tools,
    # promises, masterfiles folder to be updated.
    pass3.(((root_server.propagate_tools_ok)|(!root_server.propagate_tools_ok.(promises_propagated|empty_promises_to_propagate).(shared_files_propagated|sharedfiles_not_existent).masterfiles_propagated.ncf_local_promises_propagated.ncf_common_promises_propagated.nodeslist_copied)).!(propagate_tools_error|promises_to_propagate_not_copied|sharedfiles_not_copied|could_not_propagate_masterfiles|could_not_propagate_ncf_local_promise|could_not_propagate_ncf_common_promise|could_not_copy_nodeslist))::
      "any" usebundle => rudder_common_report("DistributePolicy", "result_success", "&TRACKINGKEY&", "Propagate promises", "None", "All files have been propagated");
    pass3.promises_to_propagate_not_copied::
      "any" usebundle => rudder_common_report("DistributePolicy", "result_error", "&TRACKINGKEY&", "Propagate promises", "None", "Cannot propagate policy");

    pass3.sharedfiles_not_copied::
      "any" usebundle => rudder_common_report("DistributePolicy", "result_error", "&TRACKINGKEY&", "Propagate promises", "None", "Cannot propagate shared files");

    pass3.could_not_propagate_files::
      "any" usebundle => rudder_common_report("DistributePolicy", "result_error", "&TRACKINGKEY&", "Propagate promises", "None", "Cannot propagate shared files");

    pass3.propagate_tools_error::
      "any" usebundle => rudder_common_report("DistributePolicy", "result_error", "&TRACKINGKEY&", "Propagate promises", "None", "Cannot propagate tools");

    pass3.could_not_propagate_ncf_local_promise::
      "any" usebundle => rudder_common_report("DistributePolicy", "result_error", "&TRACKINGKEY&", "Propagate promises", "None", "Cannot propagate local configuration library");

    pass3.could_not_propagate_ncf_common_promise::
      "any" usebundle => rudder_common_report("DistributePolicy", "result_error", "&TRACKINGKEY&", "Propagate promises", "None", "Cannot propagate common configuration library");

    pass3.could_not_propagate_masterfiles::
      "any" usebundle => rudder_common_report("DistributePolicy", "result_error", "&TRACKINGKEY&", "Propagate promises", "None", "Cannot propagate masterfiles");

    pass3.could_not_copy_license::
      "any" usebundle => rudder_common_report("DistributePolicy", "log_warn", "&TRACKINGKEY&", "Propagate promises", "None", "Cannot copy local license");

    pass3.could_not_copy_nodeslist::
      "any" usebundle => rudder_common_report("DistributePolicy", "log_warn", "&TRACKINGKEY&", "Propagate nodeslist", "None", "Cannot copy local nodes list");

}

# Sending the inventory to cmdb (or syncing with the server if we are a simple relay)
# We cannot use list of paths (i.e. {"incoming", "accepted-nodes-updates"}) to send the inventories because it cause the transformer to fail
# for some unknown reason, see https://www.rudder-project.org/redmine/issues/4094
bundle agent sendInventoryToCmdb
{

  vars:
    any::
      "incoming_inventory_files" slist => findfiles("${g.rudder_inventories}/incoming/*.ocs", "${g.rudder_inventories}/incoming/*.ocs.gz");
      "updates_inventory_files" slist => findfiles("${g.rudder_inventories}/accepted-nodes-updates/*.ocs", "${g.rudder_inventories}/accepted-nodes-updates/*.ocs.gz");
&if(CMDBENDPOINT)&
      "rudder_inventory_endpoint" string => "&CMDBENDPOINT&";
&else&
    !role_rudder_inventory_endpoint_server_defined::
      "rudder_inventory_endpoint" string => "http://localhost:8080/endpoint/upload/";
    role_rudder_inventory_endpoint_server_defined::
      "rudder_inventory_endpoint" string => "http://${rudder_servers_by_role.roles[rudder-inventory-endpoint]}:8080/endpoint/upload/";
&endif&

  classes:
    any::
      "pass3" expression => "pass2";
      "pass2" expression => "pass1";
      "pass1" expression => "any";

  files:
    # Root server and relay-top send to the endpoint, every other send as usual to their policy server
    root_server|role_rudder_relay_top::

      "${incoming_inventory_files}"
        transformer => "${g.rudder_tools}/send-clean.sh ${rudder_inventory_endpoint} ${this.promiser} ${g.rudder_inventories}/received/ ${g.rudder_inventories}/failed/",
        classes => rudder_common_classes("rudder_inventory_processing"),
        ifvarclass => "!rudder_inventory_processing_error",
        comment => "Processing a local inventory";

      "${updates_inventory_files}"
        transformer => "${g.rudder_tools}/send-clean.sh ${rudder_inventory_endpoint} ${this.promiser} ${g.rudder_inventories}/received/ ${g.rudder_inventories}/failed/",
        classes => rudder_common_classes("rudder_inventory_processing"),
        ifvarclass => "!rudder_inventory_processing_error",
        comment => "Processing a local inventory";

    policy_server.!(root_server|role_rudder_relay_top|role_rudder_relay_promises_only)::

      "${g.rudder_inventories}/incoming"
        transformer => "/usr/bin/curl -k -1 -f -s --proxy '' --user rudder:rudder -T ${this.promiser}  ${g.inventory_upload_protocol}://${server_info.cfserved}/inventories/",
        depth_search => recurse_visible(1),
        file_select => all_inventory_files,
        classes => rudder_common_classes("rudder_inventory_relay"),
        comment => "Sending an inventory to the root server";

      "${g.rudder_inventories}/accepted-nodes-updates"
        transformer => "/usr/bin/curl -k -1 -f -s --proxy '' --user ${g.davuser}:${g.davpw} -T ${this.promiser}  ${g.inventory_upload_protocol}://${server_info.cfserved}/inventory-updates/",
        depth_search => recurse_visible(1),
        file_select => all_inventory_files,
        classes => rudder_common_classes("rudder_inventory_relay"),
        comment => "Sending an inventory to the root server";

    policy_server.!(root_server|role_rudder_relay_top|role_rudder_relay_promises_only).!rudder_inventory_relay_error::

      "${g.rudder_inventories}/incoming"
        transformer => "/bin/rm -f ${this.promiser}",
        depth_search => recurse_visible(1),
        file_select => all_inventory_files,
        classes => rudder_common_classes("rudder_inventory_cleanup"),
        comment => "Purging the received inventory files after processing";

      "${g.rudder_inventories}/accepted-nodes-updates"
        transformer => "/bin/rm -f ${this.promiser}",
        depth_search => recurse_visible(1),
        file_select => all_inventory_files,
        classes => rudder_common_classes("rudder_inventory_cleanup"),
        comment => "Purging the received inventory files after processing";

  methods:

    pass3.(rudder_inventory_processing_repaired.!rudder_inventory_processing_error)::
      "any" usebundle => rudder_common_report("DistributePolicy", "result_success", "&TRACKINGKEY&", "Send inventories to CMDB", "None", "Incoming inventories were successfully sent to the inventory API");

    pass3.rudder_inventory_processing_error::
      "any" usebundle => rudder_common_report("DistributePolicy", "result_error", "&TRACKINGKEY&", "Send inventories to CMDB", "None", "Some incoming inventories could not be sent to the inventory API");

    pass3.((root_server|role_rudder_relay_top|role_rudder_relay_promises_only).!rudder_inventory_processing_repaired.!rudder_inventory_processing_error)::
      "any" usebundle => rudder_common_report("DistributePolicy", "result_success", "&TRACKINGKEY&", "Send inventories to CMDB", "None", "No inventory to send");

    pass3.(rudder_inventory_relay_repaired.!rudder_inventory_relay_error)::
      "any" usebundle => rudder_common_report("DistributePolicy", "result_success", "&TRACKINGKEY&", "Send inventories to CMDB", "None", "Incoming inventories were successfully relayed to the root server");

    pass3.rudder_inventory_relay_error::
      "any" usebundle => rudder_common_report("DistributePolicy", "result_error", "&TRACKINGKEY&", "Send inventories to CMDB", "None", "Cannot relay some inventories to the root server");

    pass3.(policy_server.!(root_server|role_rudder_relay_top|role_rudder_relay_promises_only).!rudder_inventory_relay_repaired.!rudder_inventory_relay_error)::
      "any" usebundle => rudder_common_report("DistributePolicy", "result_success", "&TRACKINGKEY&", "Send inventories to CMDB", "None", "No inventory to relay");

    pass3.(rudder_inventory_cleanup_repaired.!rudder_inventory_cleanup_error)::
      "any" usebundle => rudder_common_report("DistributePolicy", "log_info", "&TRACKINGKEY&", "Send inventories to CMDB", "None", "Sent incoming inventories were removed from local cache");

    pass3.rudder_inventory_cleanup_error::
      "any" usebundle => rudder_common_report("DistributePolicy", "result_error", "&TRACKINGKEY&", "Send inventories to CMDB", "None", "Cannot remove incoming inventories from local cache");

}

body file_select no_license_dat
{
        leaf_name => { "license\.dat" };
        file_result => "!leaf_name";
}

body file_select all_inventory_files
{
        leaf_name => { "uuid\.hive" };
        file_types => { "plain" };
        file_result => "file_types.!leaf_name";
}

