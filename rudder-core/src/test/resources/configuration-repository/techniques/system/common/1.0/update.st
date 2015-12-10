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
# Define what an agent has to do when it updates
# its promises
#

#simple copy method
body copy_from remote(server, path)
{

        servers    => {
          "${server}"
        };
        encrypt    => "true";
        trustkey   => "true";
        source     => "${path}";
        compare    => "digest";
        preserve   => "false"; #preserver permissions
        verify     => "true";
        purge      => "true";
    community_edition::
        portnumber => "&COMMUNITYPORT&";

}

#simple copy method, unencrypted
body copy_from remote_unsecured(server, path)
{

        servers    => {
          "${server}"
        };
        encrypt    => "false";
        trustkey   => "true";
        source     => "${path}";
        compare    => "mtime";
        preserve   => "true"; #preserver permissions
        verify     => "true";
        purge      => "true";
    community_edition::
        portnumber => "&COMMUNITYPORT&";

}

body copy_from copy_digest_without_perms(from)
{
        source      => "${from}";
        copy_backup => "false";
        preserve    => "false";
        compare     => "digest";
        purge       => "true";
    community_edition::
        portnumber => "&COMMUNITYPORT&";
}

body copy_from remote_unsecured_without_perms(server, path)
{

        servers    => {
          "${server}"
        };
        encrypt    => "false";
        trustkey   => "true";
        source     => "${path}";
        compare    => "digest";
        preserve   => "false";
        verify     => "true";
        purge      => "true";
    community_edition::
        portnumber => "&COMMUNITYPORT&";

}

bundle common server_info
{
  vars:
    any::
      "policy_files" string => "/var/rudder/share/&UUID&";   #directory where to look for promises in the server for that client

      "policy_server_file"
        string  => translatepath("${sys.workdir}/policy_server.dat"),
        comment => "Path to file containing address to policy server";

   # Use localhost as default policy_server if no policy_server.dat exists
    policy_server_set::
      "cfserved" string => readfile("${policy_server_file}", 2048);
    !policy_server_set::
      "cfserved" string => "rudder";

  classes:
      "policy_server_set" expression => fileexists("${server_info.policy_server_file}");
}

# The update is now split in two parts
# - the action part, only launched during failsafe
#   it copies files, restarts deamons, defines persistent classes
# - the report part, not done during failsafe but during regular run
#   note that if in verbose_mode, then the reporting will be done
#   as well during failsafe
#
# Since the defined class are persistent, the classes are still
# available during the "normal" agent execution, for reporting
bundle agent update
{
  methods:
    failsafe::
      "update" usebundle => update_action;
    (!failsafe|verbose_mode)::
      "report" usebundle => update_reports;

  reports:
    # We want to have always reports if something goes bad
    rudder_promises_generated_error|no_update::
      "*********************************************************************************
* rudder-agent could not get an updated configuration from the policy server.   *
* This can be caused by a network issue, an unavailable server, or if this      *
* node was deleted from the Rudder root server.                                 *
* Any existing configuration policy will continue to be applied without change. *
*********************************************************************************"
      action => immediate;
}

bundle agent update_action
{
  vars:

      "client_inputs"        string => "${sys.workdir}/inputs";  #where to put the files on the client when downloaded

      # Tools to copy - all defined in bundle g + the timestamp file (for our two phase update needs, see below)
      "rudder_tools_files_to_copy"      slist => { "${g.rudder_tools_updated_filename}", @{g.rudder_tools_files} }, policy => "ifdefined";
      "rudder_tools_files_to_select"    slist => maplist("${g.rudder_tools_origin}/${this}", @{rudder_tools_files_to_copy}); # file_select needs full path

       # The check is in two phase:
       # First copy rudder_promises_timestamp_tmp from the policy server, and check if it's newer
       # (based on the timestamp written in it) than rudder_promises_timestamp.
       # If so, copy all the promises
       "rudder_promises_timestamp"     string => "rudder_promises_generated";
       "rudder_promises_timestamp_tmp" string => "rudder_promises_generated.tmp";

       # Same for rudder-tools
       "rudder_tools_updated_tmp"   string => "${g.rudder_tools_updated_filename}.tmp";

     current_promise_timestamp_defined::
       "current_promises_timestamp" string => readfile("${client_inputs}/${rudder_promises_timestamp}", 2048);
     current_tools_timestamp_defined::
       "current_tools_timestamp"   string => readfile("${g.rudder_tools_updated}", 2048);

     # File ought to be there only in second pass, but since classes are persistent, we need to 
     # enforce the check on pass_2
     pass2.(rudder_promises_generated_tmp_file_kept|rudder_promises_generated_tmp_file_repaired).!rudder_promises_generated_tmp_file_error::
       "target_promises_timestamp" string => readfile("${client_inputs}/${rudder_promises_timestamp_tmp}", 2048);

     pass2.(rudder_tools_updated_repaired|rudder_tools_updated_kept).!rudder_tools_updated_error::
       "target_tools_timestamp"    string => readfile("${g.rudder_tools}/${rudder_tools_updated_tmp}", 2048);

     nova_edition::
       "server_inputs"        string => "${server_info.policy_files}/rules/cfengine-nova"; #actual directory with promises
     community_edition::
       "server_inputs"        string => "${server_info.policy_files}/rules/cfengine-community"; #actual directory with promises

  classes:
    current_promise_timestamp_defined::
      "current_promise_timestamp_valid" expression => regcmp("[0-9]+", "${current_promises_timestamp}");

    current_tools_timestamp_defined::
      "current_tools_timestamp_valid" expression => regcmp("[0-9]+", "${current_tools_timestamp}");

    any::
      "current_promise_timestamp_defined" expression => fileexists("${client_inputs}/${rudder_promises_timestamp}");
      "current_tools_timestamp_defined"   expression => fileexists("${g.rudder_tools_updated}");

    # Check for the validity of the content of the files, and check if we ought to update the promises/tools

    pass2.current_promise_timestamp_valid.(rudder_promises_generated_tmp_file_kept|rudder_promises_generated_tmp_file_repaired)::
      "new_promises_available" expression => isgreaterthan("${target_promises_timestamp}", "${current_promises_timestamp}");

    # always update if current promise timestamp is not valid
    pass2.!current_promise_timestamp_valid.(rudder_promises_generated_tmp_file_kept|rudder_promises_generated_tmp_file_repaired)::
      "new_promises_available" expression => "any";

    pass2.current_tools_timestamp_valid.(rudder_tools_updated_repaired|rudder_tools_updated_kept)::
      "new_tools_available"    expression => isgreaterthan("${target_tools_timestamp}", "${current_tools_timestamp}");

    pass2.!current_tools_timestamp_valid.(rudder_tools_updated_repaired|rudder_tools_updated_kept)::
      "new_tools_available"    expression => "any";

    # We need the pass_2 otherwise CFEngine tries to be clever, defines pass_1 and go back to define all other vars, including those that
    # shouldn't yet (notably target_promises_timestamp)

    any::
      "pass3" expression => "pass2";
      "pass2" expression => "pass1";
      "pass1" expression => "any";

  files:

    any::
      "${g.rudder_ncf}/."
        create  => "true",
        comment => "Make sure the ncf directory exists";

    root_server::
      "${g.rudder_ncf}/common"
        copy_from         => copy_digest_without_perms("${g.rudder_ncf_origin_common}"),
        depth_search      => recurse_ignore("inf", @{g.excludedreps}),
        perms             => u_mog("644", "root", "0"),
        action            => immediate,
        move_obstructions => "true",
        classes           => success("rudder_ncf_common_updated", "rudder_ncf_common_update_error", "rudder_ncf_common_updated_ok"),
        comment           => "Update the common Rudder ncf instance";

      "${g.rudder_ncf}/local"
        copy_from         => copy_digest_without_perms("${g.rudder_ncf_origin_local}"),
        depth_search      => recurse_ignore("inf", @{g.ncf_local_excludedreps}),
        perms             => u_mog("644", "root", "0"),
        action            => immediate,
        move_obstructions => "true",
        classes           => success("rudder_ncf_local_updated", "rudder_ncf_local_update_error", "rudder_ncf_local_updated_ok"),
        comment           => "Update the local Rudder ncf instance";

    !root_server::
      "${client_inputs}/${rudder_promises_timestamp_tmp}"
        copy_from         => remote("${server_info.cfserved}","${server_inputs}/${rudder_promises_timestamp}"),
        action            => immediate,
        move_obstructions => "true",
        classes           => success("rudder_promises_generated_tmp_file_repaired", "rudder_promises_generated_tmp_file_error", "rudder_promises_generated_tmp_file_kept");

      # Check if the hashes of ncf changed
      "${g.rudder_ncf}/common/${g.rudder_ncf_hash_file}"
        copy_from         => remote("${server_info.cfserved}","${g.rudder_ncf_origin_common_hash}"),
        action            => immediate,
        move_obstructions => "true",
        classes           => success("rudder_ncf_hash_update_repaired", "rudder_ncf_hash_update_error", "rudder_ncf_hash_update_ok");

      "${g.rudder_ncf}/local/${g.rudder_ncf_hash_file}"
        copy_from         => remote("${server_info.cfserved}","${g.rudder_ncf_origin_local_hash}"),
        action            => immediate,
        move_obstructions => "true",
        classes           => success("rudder_ncf_hash_update_repaired", "rudder_ncf_hash_update_error", "rudder_ncf_hash_update_ok");

    # The defined class are persistent, so if they are already set, promises has already been updated
    # a short while ago
    new_promises_available.!root_server::
      "${client_inputs}"
        copy_from         => remote("${server_info.cfserved}","${server_inputs}"),
        depth_search      => recurse("inf"),
        action            => immediate,
        move_obstructions => "true",
        file_select       => exclude("${rudder_promises_timestamp_tmp}"),
        classes           => success("config", "no_update", "config_ok");

    # We copy ncf on non root server if ncf hash file changed, or if  promises have been updated
    !root_server.(new_promises_available|rudder_ncf_hash_update_repaired)::
      "${g.rudder_ncf}/common"
        copy_from         => remote_unsecured_without_perms("${server_info.cfserved}", "${g.rudder_ncf_origin_common}"),
        depth_search      => recurse_ignore("inf", @{g.ncf_common_excludedreps}),
        perms             => u_mog("644", "root", "0"),
        action            => immediate,
        move_obstructions => "true",
        classes           => success("rudder_ncf_common_updated", "rudder_ncf_common_update_error", "rudder_ncf_common_updated_ok"),
        comment           => "Update the common Rudder ncf instance";

      "${g.rudder_ncf}/local"
        copy_from         => remote("${server_info.cfserved}", "${g.rudder_ncf_origin_local}"),
        depth_search      => recurse_ignore("inf", @{g.excludedreps}),
        perms             => u_mog("644", "root", "0"),
        action            => immediate,
        move_obstructions => "true",
        classes           => success("rudder_ncf_local_updated", "rudder_ncf_local_update_error", "rudder_ncf_local_updated_ok"),
        comment           => "Update the local Rudder ncf instance";

    # If the file to check if update are available was updated, but the promises files failed to copy correctly, we must force a new copy of the promises at next run
    new_promises_available.!root_server.no_update::
      "${client_inputs}/${file_to_check_update}"
        delete  => tidy,
        comment => "Deleting ${file_to_check_update} as the policy files couldn't be downloaded";

    (new_promises_available|rudder_ncf_hash_update_repaired).!root_server.rudder_ncf_common_update_error::
      "${g.rudder_ncf}/common/${g.rudder_ncf_hash_file}"
        delete  => tidy,
        comment => "Deleting ${g.rudder_ncf_hash_file} as common ncf files couldn't be downloaded";

    (new_promises_available|rudder_ncf_hash_update_repaired).!root_server.rudder_ncf_local_update_error::
      "${g.rudder_ncf}/local/${g.rudder_ncf_hash_file}"
        delete  => tidy,
        comment => "Deleting ${g.rudder_ncf_hash_file} as custom ncf files couldn't be downloaded";


    root_server|(rudder_promises_generated_tmp_file_ok|(new_promises_available.(config|config_ok)).!no_update.!rudder_promises_generated_tmp_file_error)::
      # Every time we check update inputs successfully (already up to date or
      # updated), touch a file to let other promises know we are doing ok
      "${sys.workdir}/last_successful_inputs_update"
        touch      => "true";

    # Copy the tools only if the file rudder_tools_updated is not up to date
    !root_server::
      "${g.rudder_tools}/${rudder_tools_updated_tmp}"
        copy_from         => remote_unsecured("${server_info.cfserved}", "${g.rudder_tools_updated_origin}"),
        action            => immediate,
        move_obstructions => "true",
        classes           => success("rudder_tools_updated_repaired", "rudder_tools_updated_error", "rudder_tools_updated_kept"),
        comment           => "Check if we need to update the tools";

    # We copy only if we have new tools based on the timestamp
    new_tools_available::
      "${g.rudder_tools}"
        copy_from         => remote_unsecured("${server_info.cfserved}", "${g.rudder_tools_origin}"),
        depth_search      => recurse_ignore("inf", @{g.excludedreps}),
        file_select       => by_fullname(@{rudder_tools_files_to_select}),
        action            => immediate,
        move_obstructions => "true",
        classes           => success("rudder_tools_updated", "rudder_tools_update_error", "rudder_tools_updated_ok"),
        comment           => "Update the Rudder tools";

    # If we couldn't update the tools, then we must force a copy at next run
    rudder_tools_update_error::
      "${g.rudder_tools_updated}"
        delete => tidy,
        comment => "Deleting ${g.rudder_tools_updated} as the tools couldn't be updated";

  methods:
    pass3.server_ok::
      "any" usebundle => rudder_common_report("Common", "log_repaired", "&TRACKINGKEY&", "Update", "None", "Started the server (cf-serverd)");
    pass3.executor_ok::
      "any" usebundle => rudder_common_report("Common", "log_repaired", "&TRACKINGKEY&", "Update", "None", "Started the scheduler (cf-execd)");

  processes:
    config.!windows::
      "cf-serverd" restart_class => "start_server";

    config.!windows::
      "cf-execd"   restart_class => "start_exec";

&if(NOVA)&
  services:
    windows::
      "CfengineNovaExec"
        service_policy => "start",
        service_method => u_bootstart,
        classes        => outcome("executor"),
        comment        => "Start the executor windows service now and at boot time";
&endif&

  commands:
    start_exec.(!windows|cygwin)::
      "${sys.cf_execd}"
        action  => u_ifwin_bg,
        classes => outcome("executor");

    start_server::
      "${sys.cf_serverd}"
        action  => u_ifwin_bg,
        classes => outcome("server");

#########################################################
}

# This bundle is responsible for the reporting of what happened in the update
# It can work because the classes defined during the update are persistent, so
# the classes are available for the next 4 minutes
bundle agent update_reports
{
  methods:
    no_update::
      "any" usebundle => rudder_common_report("Common", "result_error", "&TRACKINGKEY&", "Update", "None", "Cannot update node's policy (CFEngine promises)");

    rudder_tools_updated_error::
      "any" usebundle => rudder_common_report("Common", "result_error", "&TRACKINGKEY&", "Update", "None", "Cannot update Rudder tools last updated file");

    rudder_tools_update_error::
      "any" usebundle => rudder_common_report("Common", "result_error", "&TRACKINGKEY&", "Update", "None", "Cannot update Rudder tools");

    rudder_ncf_hash_update_error::
      "any" usebundle => rudder_common_report("Common", "result_error", "&TRACKINGKEY&", "Update", "None", "Cannot update Rudder ncf update hash file");

    rudder_ncf_common_update_error::
      "any" usebundle => rudder_common_report("Common", "result_error", "&TRACKINGKEY&", "Update", "None", "Cannot update common Rudder ncf instance");

    rudder_ncf_local_update_error::
      "any" usebundle => rudder_common_report("Common", "result_error", "&TRACKINGKEY&", "Update", "None", "Cannot update local Rudder ncf instance");

    rudder_promises_generated_tmp_file_error::
      "any" usebundle => rudder_common_report("Common", "result_error", "&TRACKINGKEY&", "Update", "None", "Cannot update node's policy");

    # Success report relies on several matching conditions (nodes except root_server)
    # On all nodes except root server:
    #   - Staggered update: rudder_ncf_hash_update_ok OR (rudder_ncf_hash_update_repaired AND rudder_ncf_common_updated_ok AND rudder_ncf_local_updated_ok)
    #   - Staggered update: rudder_tools_updated_kept OR (rudder_tools_updated_repaired AND rudder_tools_updated_ok)
    #   - Staggered update: rudder_promises_generated_tmp_file_kept OR (rudder_promises_generated_tmp_file_repaired AND config_ok)
    # There must be NO components in repair or error
    # Note: we can't use classes new_promises_available and new_tools_available here because they are local to the update_action bundle
    !root_server.(rudder_ncf_hash_update_ok|(rudder_ncf_hash_update_repaired.rudder_ncf_common_updated_ok.rudder_ncf_local_updated_ok)).(rudder_tools_updated_kept|(rudder_tools_updated_repaired.rudder_tools_updated_ok)).(rudder_promises_generated_tmp_file_kept|(rudder_promises_generated_tmp_file_repaired.config_ok)).!(rudder_promises_generated_tmp_file_repaired|rudder_promises_generated_tmp_file_error|rudder_tools_updated_error|rudder_tools_updated|rudder_tools_update_error|rudder_ncf_common_updated|rudder_ncf_common_update_error|rudder_ncf_local_updated|rudder_ncf_local_update_error|config|no_update|rudder_ncf_hash_update_error|rudder_ncf_hash_update_repaired)::
      "any" usebundle => rudder_common_report("Common", "result_success", "&TRACKINGKEY&", "Update", "None", "Rudder policy, tools and ncf instance are already up to date. No action required.");

    # Success report relies on several matching conditions (root_server only)
    # On the root server only:
    #   - Simple test: rudder_ncf_common_updated_ok
    #   - Simple test: rudder_ncf_local_updated_ok
    # There must be NO components in repair or error
    root_server.rudder_ncf_common_updated_ok.rudder_ncf_local_updated_ok.!(rudder_ncf_common_updated|rudder_ncf_common_update_error|rudder_ncf_local_updated|rudder_ncf_local_update_error)::
      "any" usebundle => rudder_common_report("Common", "result_success", "&TRACKINGKEY&", "Update", "None", "Rudder ncf instance already up to date on this root server. No action required.");

    rudder_tools_updated::
      "any" usebundle => rudder_common_report("Common", "log_repaired", "&TRACKINGKEY&", "Update", "None", "Rudder tools updated");

    rudder_ncf_common_updated::
      "any" usebundle => rudder_common_report("Common", "log_repaired", "&TRACKINGKEY&", "Update", "None", "Rudder ncf common instance updated");

    rudder_ncf_local_updated::
      "any" usebundle => rudder_common_report("Common", "log_repaired", "&TRACKINGKEY&", "Update", "None", "Rudder ncf local instance updated");

    config::
      "any" usebundle => rudder_common_report("Common", "log_repaired", "&TRACKINGKEY&", "Update", "None", "Node's policy (CFEngine promises) updated");

    (config|rudder_tools_updated|rudder_ncf_common_updated|rudder_ncf_local_updated|server_ok|executor_ok).!(rudder_promises_generated_tmp_file_error|rudder_tools_updated_error|rudder_tools_update_error|rudder_ncf_common_update_error|rudder_ncf_local_update_error|no_update|rudder_ncf_hash_update_error)::
      "any" usebundle => rudder_common_report("Common", "result_repaired", "&TRACKINGKEY&", "Update", "None", "Rudder policy, tools or ncf instance were updated or CFEngine service restarted");

}


############################################
body classes outcome(x)
{
        promise_repaired => {"${x}_ok"};
}
############################################
body classes success(if, else, kept)
{
        promise_kept     => { "${kept}" };
        promise_repaired => { "${if}" };
        repair_failed    => { "${else}" };
        repair_denied    => { "${else}" };
        repair_timeout   => { "${else}" };

        cancel_kept      => { "${else}" };
        cancel_repaired  => { "${else}" };

        # persist for 4 minutes so that it wont overlap with the next
        # execution in 5 minutes
        persist_time     => "4"; 
}

############################################
body action u_ifwin_bg
{
    windows::
        background => "true";
}

&if(NOVA)&
body service_method u_bootstart
{
        service_autostart_policy => "boot_time";
}
&endif&

body perms u_mog(mode,user,group)
{
owners => { "${user}" };
groups => { "${group}" };
mode   => "${mode}";
}
