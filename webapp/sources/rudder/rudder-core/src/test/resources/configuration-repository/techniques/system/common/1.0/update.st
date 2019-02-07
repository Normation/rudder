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

# Use for sensitive files
body copy_from remote(server, path)
{

        servers    => {
          "${server}"
        };
        encrypt    => "true";
        trustkey   => "true";
        source     => "${path}";
        compare    => "digest";
        preserve   => "false"; #do not preserve permissions
        verify     => "true";
        purge      => "true";
        copy_backup => "false";
    community_edition::
        portnumber => "&COMMUNITYPORT&";

}

# Use for big and non sensitive files
body copy_from remote_unsecured(server, path)
{

        servers    => {
          "${server}"
        };
        encrypt    => "false";
        trustkey   => "true";
        source     => "${path}";
        compare    => "mtime";
        preserve   => "true"; #preserve permissions
        verify     => "true";
        purge      => "true";
        copy_backup => "false";
    community_edition::
        portnumber => "&COMMUNITYPORT&";

}

# Local copy
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

# Use for non-sensitive policy files
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
        copy_backup => "false";
    community_edition::
        portnumber => "&COMMUNITYPORT&";

}

bundle common server_info
{
  vars:
    any::
      "policy_files" string => "/var/rudder/share/${g.uuid}";   #directory where to look for promises in the server for that client

      "policy_server_file"
        string  => translatepath("${sys.workdir}/policy_server.dat"),
        comment => "Path to file containing address to policy server";

      # Use "rudder" as default policy_server if no policy_server.dat exists
      "cfserved" string => ifelse(fileexists("${policy_server_file}"),
                                  readfile("${policy_server_file}", 2048),
                                  "rudder");

  classes:
      "should_disable_server" expression => fileexists("${g.rudder_disable_server_file}");
}

# The update is now split in two parts
# - the action part, only launched during failsafe
#   it copies files, restarts deamons, defines persistent classes
# - the report part, not done during failsafe but during regular run
#   see update_reports in promises.st
#
# Since the defined class are persistent, the classes are still
# available during the "normal" agent execution, for reporting
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

# do not copy potentialy incompatible ncf in initial promises
&if(!INITIAL)&
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
        perms             => u_mog("600", "root", "0"),
        action            => immediate,
        move_obstructions => "true",
        classes           => success("rudder_ncf_local_updated", "rudder_ncf_local_update_error", "rudder_ncf_local_updated_ok"),
        comment           => "Update the local Rudder ncf instance";
&endif&

    !root_server::
      "${client_inputs}/${rudder_promises_timestamp_tmp}"
        copy_from         => remote_unsecured_without_perms("${server_info.cfserved}","${server_inputs}/${rudder_promises_timestamp}"),
        action            => immediate,
        move_obstructions => "true",
        classes           => success("rudder_promises_generated_tmp_file_repaired", "rudder_promises_generated_tmp_file_error", "rudder_promises_generated_tmp_file_kept");

# do not copy potentialy incompatible ncf in initial promises
&if(!INITIAL)&
      # Check if the hashes of ncf changed
      "${g.rudder_ncf}/common/${g.rudder_ncf_hash_file}"
        copy_from         => remote_unsecured_without_perms("${server_info.cfserved}","${g.rudder_ncf_origin_common_hash}"),
        action            => immediate,
        move_obstructions => "true",
        classes           => success("rudder_ncf_hash_update_repaired", "rudder_ncf_hash_update_error", "rudder_ncf_hash_update_ok");

      "${g.rudder_ncf}/local/${g.rudder_ncf_hash_file}"
        copy_from         => remote_unsecured_without_perms("${server_info.cfserved}","${g.rudder_ncf_origin_local_hash}"),
        action            => immediate,
        move_obstructions => "true",
        classes           => success("rudder_ncf_hash_update_repaired", "rudder_ncf_hash_update_error", "rudder_ncf_hash_update_ok");
&endif&

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

# do not copy potentialy incompatible ncf in initial promises
&if(!INITIAL)&
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
        perms             => u_mog("600", "root", "0"),
        action            => immediate,
        move_obstructions => "true",
        classes           => success("rudder_ncf_local_updated", "rudder_ncf_local_update_error", "rudder_ncf_local_updated_ok"),
        comment           => "Update the local Rudder ncf instance";
&endif&

    # If the file to check if update are available was updated, but the promises files failed to copy correctly, we must force a new copy of the promises at next run
    new_promises_available.!root_server.no_update::
      "${client_inputs}/${rudder_promises_timestamp}"
        delete  => tidy,
        comment => "Deleting ${rudder_promises_timestamp} as the policy files couldn't be downloaded";

# do not copy potentialy incompatible ncf in initial promises
&if(!INITIAL)&
    (new_promises_available|rudder_ncf_hash_update_repaired).!root_server.rudder_ncf_common_update_error::
      "${g.rudder_ncf}/common/${g.rudder_ncf_hash_file}"
        delete  => tidy,
        comment => "Deleting ${g.rudder_ncf_hash_file} as common ncf files couldn't be downloaded";

    (new_promises_available|rudder_ncf_hash_update_repaired).!root_server.rudder_ncf_local_update_error::
      "${g.rudder_ncf}/local/${g.rudder_ncf_hash_file}"
        delete  => tidy,
        comment => "Deleting ${g.rudder_ncf_hash_file} as custom ncf files couldn't be downloaded";
&endif&

    root_server|(rudder_promises_generated_tmp_file_kept|(new_promises_available.(config|config_ok)).!no_update.!rudder_promises_generated_tmp_file_error)::
      # Every time we check update inputs successfully (already up to date or
      # updated), touch a file to let other promises know we are doing ok
      "${sys.workdir}/last_successful_inputs_update"
        touch      => "true";

    # Copy the tools only if the file rudder_tools_updated is not up to date
    !policy_server::
      "${g.rudder_tools}/${rudder_tools_updated_tmp}"
        copy_from         => remote_unsecured_without_perms("${server_info.cfserved}", "${g.rudder_tools_updated_origin}"),
        action            => immediate,
        move_obstructions => "true",
        classes           => success("rudder_tools_updated_repaired", "rudder_tools_updated_error", "rudder_tools_updated_kept"),
        comment           => "Check if we need to update the tools";

    # We copy only if we have new tools based on the timestamp
    !policy_server.new_tools_available::
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

  processes:
    config.!windows::
      "cf-serverd" restart_class => "start_server";
      "cf-execd"   restart_class => "start_exec";

&if(NOVA)&
  services:
    windows::
      "CfengineNovaExec"
        service_policy => "start",
        service_method => u_bootstart,
        classes        => success("executor_ok", "executor_error", "executor_kept"),
        comment        => "Start the executor windows service now and at boot time";
&endif&

  commands:
    start_exec.(!windows|cygwin)::
      "${sys.cf_execd}"
        action  => u_ifwin_bg,
        classes => success("executor_ok", "executor_error", "executor_kept");

    !should_disable_server.start_server::
      "${sys.cf_serverd}"
        action  => u_ifwin_bg,
        classes => success("server_ok", "server_error", "server_kept");

  reports:
    # We want to have always reports if something goes bad
    rudder_ncf_hash_update_error::
      "*********************************************************************************
* rudder-agent could not get an updated configuration from the policy server.   *
* This can be caused by:                                                        *
*   * a networking issue                                                        *
*   * an unavailable server                                                     *
*   * if the node's IP in not if the allowed networks of its policy server.     *
* Any existing configuration policy will continue to be applied without change. *
*********************************************************************************"
      action => immediate;

     !rudder_ncf_hash_update_error.(rudder_promises_generated_error|no_update|rudder_promises_generated_tmp_file_error)::
       "*********************************************************************************
* rudder-agent could not get an updated configuration from the policy server.   *
* This can be caused by:                                                        *
*   * an incorrect DNS resolution of this node                                  *
*   * an agent key that has been changed                                        *
*   * if this node is not accepted or deleted node on the Rudder root server    *
*   * if this node has changed policy server without sending a new inventory    *
* Any existing configuration policy will continue to be applied without change. *
*********************************************************************************"
      action => immediate;
}

#######################################################
# UUID file checking

bundle agent check_uuid
{
  classes:
    # Abort if no uuid is defined
    "should_not_continue" expression => "wrong_uuid|no_uuid";
    "no_uuid" not => fileexists("${g.uuid_file}");
&if(!INITIAL)&
    "wrong_uuid" not => strcmp("&UUID&", "${g.uuid}");
&endif&

  reports:
&if(!INITIAL)&
    wrong_uuid::
      "FATAL: The UUID in ${g.uuid_file} (${g.uuid}) doesn't match the UUID recorded for this node in your Rudder server (&UUID&)";
&endif&

    no_uuid::
      "FATAL: The UUID file ${g.uuid_file} is missing. Please run 'rudder agent check' to generate a new one or restore it from a backup.";
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

body service_method u_bootstart
{
        service_autostart_policy => "boot_time";
}

body perms u_mog(mode,user,group)
{
  mode   => "${mode}";
!windows::
  owners => { "${user}" };
  groups => { "${group}" };
}
