# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2021 Normation SAS

#######################################################
#
# promises.cf
#
#######################################################
bundle common rudder_roles
{
  vars:
    # moved from bundle common g since they need to be defined in promises.cf
      "uuid_file"         string => "/opt/rudder/etc/uuid.hive";
      "uuid"              string => readfile("${uuid_file}", 60);

  classes:

&if(INITIAL)&
      # no kind for initial policies
      "root_server"   expression => strcmp("root","${rudder_roles.uuid}");
      "policy_server" expression => "root_server";
&else&
      # kind, i.e. "node", "relay" or "root"
      "rudder_kind_&RUDDER_NODE_KIND&" expression => "any";
      "root_server"                    expression => "rudder_kind_root";
      "policy_server"                          or => { "rudder_kind_relay", "rudder_kind_root" };
&endif&

      # for compatibility, was used in some techniques
      "role_rudder_server_root"        expression => "root_server";

      # on changes only, we only reports on repaired or error message
      "changes_only_mode"      expression => strcmp("&RUDDER_REPORT_MODE&", "changes-only");
      "reports_disabled_mode"  expression => strcmp("&RUDDER_REPORT_MODE&", "reports-disabled");
      "changes_only"           expression => "changes_only_mode.!force_full_compliance";
      "reports_disabled"       expression => "reports_disabled_mode.!force_full_compliance";

      "rudder_global_audit_mode"      not => strcmp("&RUDDER_COMPLIANCE_MODE&", "enforce");

      # full compliance is the default mode
      "full_compliance" not => "changes_only|reports_disabled";
}

body common control
{
        output_prefix => "rudder";
        protocol_version => "2";

        inputs => {
          @{va.inputs_list},
          "rudder-system-directives.cf",
        };

        tls_min_version => "1.2";

      any::
        bundlesequence => {
          rudder_init,
          rudder_common_system_directive,
          rudder_system_directives,
          rudder_directives,
          rudder_common_system_end,
          rudder_end
        };
}

bundle agent rudder_init {
  methods:
      "Abort if agent is disabled"     usebundle => check_disable_agent;
      "Abort if agent is not in audit" usebundle => check_rudder_force_audit_agent;
      "Abort on some cfengine version" usebundle => rudder_check_agent_version;
      "Abort on invalid UUID"          usebundle => check_uuid;
      "Startup report log"             usebundle => startup;
      "Initialize ncf"                 usebundle => initialize_ncf;
      "Define environment variables"   usebundle => get_environment_variables;
&if(!INITIAL)&
      "Load properties"                usebundle => properties;
&endif&

}

bundle agent rudder_common_system_directive {
  vars:
      # Hackish way to skip the cf-promises on server bundles, otherwise it
      # won't work on the agent since the bundle will not be loaded.
      "rudder_apache"                     string => "system_rudder_apache_configuration";
      "rudder_slapd"                      string => "system_rudder_slapd_configuration";
      "server_common"                     string => "system_reload_rudder_services";
      "rudder_postgresql"                 string => "system_rudder_postgresql_configuration";
      "rudder_relay"                      string => "system_rudder_relay_configuration";
      "rudder_webapp"                     string => "system_rudder_webapp";

  methods:
      "Send agent update report"          usebundle => update_reports;
      "Update cfengine files permissions" usebundle => internal_security;
      "Cleanup log files"                 usebundle => garbage_collection;
      "Setup agent check cron"            usebundle => setup_cronjob;
      "Make sure cron daemon is running"  usebundle => check_cron_daemon;
&if(INITIAL)&
      "Run initial inventory"             usebundle => doInventory;
&else&
      "Configure logs for reporting"      usebundle => configure_rudder_reporting_system;
      "Fetch Rudder Inventory variables"  usebundle => rudder_node_inventory;
      "Pre hook for monitoring methods"   usebundle => monitoring_hook_pre;
&endif&

&if(INITIAL)&
    policy_server::
      "rudder_apache"                     usebundle => "${rudder_apache}";
      "rudder_slapd"                      usebundle => "${rudder_slapd}";
      "server_common"                     usebundle => "${server_common}";
      "rudder_postgresql"                 usebundle => "${rudder_postgresql}";
      "rudder_relay"                      usebundle => "${rudder_relay}";
      "rudder_webapp"                     usebundle => "${rudder_webapp}";
&endif&

}

bundle agent rudder_common_system_end {
  methods:
&if(!INITIAL)&
      "Post hook for monitoring methods"  usebundle => monitoring_hook_post;
&endif&

      "End report log"                    usebundle => endExecution;
      # keep this at real end to avoid breaking reporting if syslog still works
      "Restart syslog if needed"          usebundle => restart_services;
}

bundle agent rudder_end {
# may contain methods some day
# Keep this for rudder agent run-directive to work
}

bundle common va
{
  vars:
    any::
      "rudder_capabilities_file"   string => "/opt/rudder/etc/agent-capabilities";
      "capability_option"          string => "--capability-file ${rudder_capabilities_file}";
      "rudder_var"                 string => "/var/rudder";
      "ncf_path"                   string => "${rudder_var}/ncf";
      "shell_type"                 string => "useshell";
      "list_compatible_inputs"     string => "NCF_CACHE_PATH=${sys.workdir}/state /bin/sh ${ncf_path}/common/10_ncf_internals/list-compatible-inputs";
      "compatible_inputs_cmd"      string => "${list_compatible_inputs} ${capability_option} --agent-version ${sys.cf_version} --ncf-path '${ncf_path}'";

    # prevent reevaluation of list-compatible-inputs command
    # the list_compatible_inputs_ok is set in the classes part, meaning
    # command has already been executed, for the better or worse
    any.!list_compatible_inputs_ok::
      "raw_path_ncf_common_inputs" slist => splitstring(execresult("${compatible_inputs_cmd} common", "${shell_type}"), "\n", 10000);
      "raw_path_ncf_local_inputs"  slist => splitstring(execresult("${compatible_inputs_cmd} local", "${shell_type}"), "\n", 10000);

      # filtering files at the root of common (ncf built-in loading tools)
      "path_ncf_common_inputs" slist => filter("common/[^/]+.cf", "raw_path_ncf_common_inputs", "true", "true", 10000);

      # filtering out 50_techniques from local list
      "path_ncf_local_inputs" slist  => filter("local/50_techniques.*", "raw_path_ncf_local_inputs", "true", "true", 10000);

      # Must concatenate all lists + filter, as maplist on an empty element returns cf_null
      "raw_path_ncf_inputs"    slist => { @{path_ncf_common_inputs}, @{path_ncf_local_inputs}},
                              policy => "ifdefined";

      "raw_ncf_inputs"        slist => maplist("${ncf_path}/${this}", @{raw_path_ncf_inputs});
      # Need to remove cf_null from the list
      "ncf_inputs"            slist => filter("${ncf_path}/cf_null", "raw_ncf_inputs", "false", "true", 10000);

      # create the final input list after ncf
      # all other inputs are loaded by body file control in rudder-system-directives.cf and rudder-directives.cf
      "inputs_list" slist => { @{ncf_inputs} };

&if(INITIAL)&
      "common_input_list" slist => {
        &COMMON_SEQUENCE:{common_file_input |"&common_file_input&",
}&      };
      "inventory_input_list" slist => {
        &INVENTORY_SEQUENCE:{inventory_file_input |"&inventory_file_input&",
}&      };
      "rudder_apache_input_list" slist => {
        &RUDDER_SERVICE_APACHE_SEQUENCE:{rudder_apache_file_input |"&rudder_apache_file_input&",
}&      };
      "rudder_webapp_input_list" slist => {
        &RUDDER_SERVICE_WEBAPP_SEQUENCE:{rudder_webapp_file_input |"&rudder_webapp_file_input&",
}&      };
      "rudder_relay_input_list" slist => {
        &RUDDER_SERVICE_RELAYD_SEQUENCE:{rudder_relay_file_input |"&rudder_relay_file_input&",
}&      };
      "rudder_postgresql_input_list" slist => {
        &RUDDER_SERVICE_POSTGRESQL_SEQUENCE:{rudder_postgresql_file_input |"&rudder_postgresql_file_input&",
}&      };
      "rudder_slapd_input_list" slist => {
        &RUDDER_SERVICE_SLAPD_SEQUENCE:{rudder_slapd_file_input |"&rudder_slapd_file_input&",
}&      };
      "server_common_input_list" slist => {
        &SERVER_COMMON_SEQUENCE:{server_common_file_input |"&server_common_file_input&",
}&      };

    policy_server::
      "inputs_list" slist => {
        @{ncf_inputs},
        @{common_input_list},
        @{inventory_input_list},
        @{rudder_apache_input_list},
        @{rudder_webapp_input_list},
        @{rudder_relay_input_list},
        @{rudder_postgresql_input_list},
        @{rudder_slapd_input_list},
        @{server_common_input_list},
      };
    !policy_server::
      "inputs_list" slist => { @{ncf_inputs}, @{common_input_list}, @{inventory_input_list} };
&endif&

  classes:
    # OS classes for compatibility
    # those two are deprecated
      "SUSE" expression => "sles|sled|opensuse";
      "SuSE" expression => "sles|sled|opensuse";
    # for OS family compatibility
      "suse" expression => "sles|sled|opensuse";
      "redhat" expression => "amzn|amazon_linux";

    # We are inside Rudder policies
      "rudder_policies" expression => "any";

    # Define classes that policies are evaluable
      "list_compatible_inputs_ok" expression => "any";
}

bundle common agent_capabilities {
  vars:
      "capabilities" slist => readstringlist("${va.rudder_capabilities_file}", "", "\n", inf, inf);
  classes:
      "agent_capability_${capabilities}";
}

#########################################################
# Control execution
#########################################################
bundle agent startup
{
  classes:
    "rudder_is_full_run" expression => "any",
                              scope => "namespace";

  methods:
    # Should we send a message stating this agent is starting up?
    # Always do it in "full_compliance" mode
    full_compliance::
      "Send start message"
        usebundle => startExecution,
        action    => immediate;
}

##########################################################
# Check for "disable-agent" file and cleanly stop and
# warn about this if it is present
#
# All promises in this bundle MUST have action=>immediate,
# to avoid locking issues in case of several close runs.
##########################################################
bundle agent check_disable_agent
{
  vars:
      "components" slist => { "cf-execd" };

  classes:
      "should_disable_agent" expression => fileexists("${g.rudder_disable_agent_file}");

      "pass2" expression => "pass1";
      "pass1" expression => "any";

  methods:
    pass2.should_disable_agent.!ignore_disable_agent::
      "any" usebundle => _abort("disabled_agent", "This agent is disabled, killing running daemons and aborting");

  processes:
    should_disable_agent::
      "${sys.workdir}/bin/${components}"
        signals       => { "term", "kill" },
        action        => immediate;
}


##########################################################
# Check for "force-audit-agent" file and cleanly stop and
# warn about this if it is present and agent is not in audit
#
# All promises in this bundle MUST have action=>immediate,
# to avoid locking issues in case of several close runs.
##########################################################
bundle agent check_rudder_force_audit_agent
{
  classes:
      "should_be_in_audit" expression => fileexists("${g.rudder_force_audit_agent}");

  methods:
    should_be_in_audit.!rudder_global_audit_mode::
      "any" usebundle => _abort("not_in_audit_mode", "The file ${g.rudder_force_audit_agent} is present, but node is not in audit mode. Rudder will halt immediately.");
}


###################################################
# Initialize ncf and report about it
###################################################
bundle agent initialize_ncf()
{
  vars:
      "local_modules" string => "${g.rudder_ncf}/local/10_ncf_internals/modules/";

  classes:
      "local_modules_exist" expression => isdir("${local_modules}");

  methods:
      "initialize ncf"        usebundle => initialization;
      "add local modules"     usebundle => additional_modules("${local_modules}"),
                             ifvarclass => "local_modules_exist";
      "report about ncf init" usebundle => rudder_common_reports_generic("Common", "ncf_init", "${system_common.directiveId}", "ncf Initialization", "None", "Configuration library initialization");
}

&if(!INITIAL)&
bundle agent configure_rudder_reporting_system {
  methods:
      "remove_reporting"    usebundle => remove_rudder_syslog_configuration;
      "make http reports"   usebundle => send_rudder_reports;

  reports:
    !agent_capability_http_reporting.!reports_disabled::
      "******************************************************************
       * This version of rudder-agent does not support https reporting. *
       * Please upgrade rudder-agent to version 6.0 or more.            *
       ******************************************************************";
}

#############################################################
# Remove syslog configurations that Rudder may have applied #
#############################################################
# Note: if any configurations are changed here, corresponding services must be
# restarted in the restart_services bundle

bundle agent remove_rudder_syslog_configuration {
  vars:
      "syslogng_delete_lines_patterns" slist => {
        "\s*# Autogenerated by rudder, do not edit.*",
        ".*destination\(rudder_loghost\);.*",
      };

      "syslog_delete_lines_patterns" slist => {
        "\s*# Rudder specific logging parameters.*",
        "local6.notice\s+@${server_info.policy_server}",
      };

      "syslogng_conffile"     string => "/etc/syslog-ng/syslog-ng.conf";
      "syslog_conffile"       string => "/etc/syslog.conf";

  classes:
      "syslogng" expression => fileexists("${syslogng_conffile}");
      "syslogd"  expression => fileexists("${syslog_conffile}");

  files:
      "/etc/rsyslog.d/rudder.conf"
        delete         => tidy,
        classes        => classes_generic("remove_rudder_syslog_configuration_rsyslog");

      "/etc/rsyslog.d/rudder-agent.conf"
        delete         => tidy,
        classes        => classes_generic("remove_rudder_syslog_configuration_rsyslog");

      "/etc/rsyslog.d/remove_limit.conf"
        delete         => tidy;

    syslogng::
      "${syslogng_conffile}"
        edit_line      => delete_lines_matching("${syslogng_delete_lines_patterns}"),
        edit_defaults  => noempty_backup,
        classes        => classes_generic("remove_rudder_syslog_configuration_syslogng");

    syslogd::
      "${syslog_conffile}"
        edit_line      => delete_lines_matching("${syslog_delete_lines_patterns}"),
        edit_defaults  => noempty_backup,
        classes        => classes_generic("remove_rudder_syslog_configuration_syslog");

  methods:
      "merge result classes" usebundle  => _classes_combine_three("remove_rudder_syslog_configuration_rsyslog", "remove_rudder_syslog_configuration_syslogng", "remove_rudder_syslog_configuration_syslog", "remove_rudder_syslog_configuration_result");
      "reports"              usebundle  => rudder_common_reports_generic("Common", "log_repaired", "${system_common.directiveId}", "Log system for reports", "None", "Rudder syslog configuration was correctly removed"),
                             ifvarclass => "remove_rudder_syslog_configuration_result_repaired";
      "reports"              usebundle  => rudder_common_reports_generic("Common", "log_info", "${system_common.directiveId}", "Log system for reports", "None", "Rudder syslog configuration is not present"),
                             ifvarclass => "remove_rudder_syslog_configuration_result_kept";
      "reports"              usebundle  => rudder_common_reports_generic("Common", "result_error", "${system_common.directiveId}", "Log system for reports", "None", "Rudder syslog configuration could not be removed"),
                             ifvarclass => "remove_rudder_syslog_configuration_result_error";

}
&endif&

###################################################################
# Trash every output report and modified files older than the TTL #
###################################################################

bundle agent garbage_collection
{
  vars:
      "log_files_to_rotate" slist => {
                                        "${sys.workdir}/cf3.${sys.uqhost}.runlog",
                                        "${sys.workdir}/promise_summary.log",
                                        "${sys.workdir}/cf_notkept.log",
                                        "${sys.workdir}/cf_repair.log",
                                        "/var/log/rudder/agent-check/check.log",
                                     };

  files:

      "${sys.workdir}/outputs"

        delete => tidy,
        file_select => days_old("&CFENGINE_OUTPUTS_TTL&"),
        depth_search => recurse("inf"),
        action => if_elapsed_day;

      "${g.rudder_var}/modified-files"

        delete => tidy,
        file_select => days_old("&MODIFIED_FILES_TTL&"),
        depth_search => recurse("inf");

      "/var/log/rudder/agent-check/." create => "true";

      "${log_files_to_rotate}"
        comment     => "Rotate file if above specified size",
        rename      => rotate("10"),
        file_select => bigger_than("1M");

}

#######################################################
# This bundle is responsible for the reporting of what happened in the update
# It can work because the classes defined during the update are persistent, so
# the classes are available for the next 4 minutes
bundle agent update_reports
{
  methods:
    no_update::
      "any" usebundle => rudder_common_report("Common", "result_error", "${system_common.directiveId}", "Update", "None", "Cannot update configuration policy");

    rudder_ncf_hash_update_error::
      "any" usebundle => rudder_common_report("Common", "result_error", "${system_common.directiveId}", "Update", "None", "Cannot update common configuration library (update flag file)");

    rudder_ncf_common_update_error::
      "any" usebundle => rudder_common_report("Common", "result_error", "${system_common.directiveId}", "Update", "None", "Cannot update common configuration library");

    rudder_ncf_local_update_error::
      "any" usebundle => rudder_common_report("Common", "result_error", "${system_common.directiveId}", "Update", "None", "Cannot update local configuration library");

    rudder_promises_generated_tmp_file_error::
      "any" usebundle => rudder_common_report("Common", "result_error", "${system_common.directiveId}", "Update", "None", "Cannot update configuration policy");

    # Success report relies on several matching conditions (nodes except root_server)
    # On all nodes except root server:
    #   - Staggered update: rudder_ncf_hash_update_ok OR (rudder_ncf_hash_update_repaired AND rudder_ncf_common_updated_ok AND rudder_ncf_local_updated_ok)
    #   - Staggered update: rudder_promises_generated_tmp_file_kept OR (rudder_promises_generated_tmp_file_repaired AND config_ok)
    # Note: its possible to have only rudder_ncf_hash_update_repaired and not rudder_ncf_common_updated, rudder_ncf_common_update_error but rudder_ncf_common_updated_ok if a technique is created, but not applied - in this case this is a success
    # There must be NO components in repair or error
    # Note: we can't use classe new_promises_available here because they are local to the update_action bundle
    !root_server.(rudder_ncf_hash_update_ok|(rudder_ncf_hash_update_repaired.rudder_ncf_common_updated_ok.rudder_ncf_local_updated_ok)).(rudder_promises_generated_tmp_file_kept|(rudder_promises_generated_tmp_file_repaired.config_ok)).!(rudder_promises_generated_tmp_file_error|rudder_ncf_common_updated|rudder_ncf_common_update_error|rudder_ncf_local_updated|rudder_ncf_local_update_error|config|no_update|rudder_ncf_hash_update_error|(rudder_ncf_hash_update_repaired.!rudder_ncf_common_updated_ok))::
      "any" usebundle => rudder_common_report("Common", "result_success", "${system_common.directiveId}", "Update", "None", "Policy and configuration library are already up to date. No action required.");

    # Success report relies on several matching conditions (root_server only)
    # On the root server only:
    #   - Simple test: rudder_ncf_common_updated_ok
    #   - Simple test: rudder_ncf_local_updated_ok
    # There must be NO components in repair or error
    root_server.rudder_ncf_common_updated_ok.rudder_ncf_local_updated_ok.!(rudder_ncf_common_updated|rudder_ncf_common_update_error|rudder_ncf_local_updated|rudder_ncf_local_update_error)::
      "any" usebundle => rudder_common_report("Common", "result_success", "${system_common.directiveId}", "Update", "None", "Configuration library already up to date on this root server. No action required.");

    rudder_ncf_common_updated::
      "any" usebundle => rudder_common_report("Common", "log_repaired", "${system_common.directiveId}", "Update", "None", "Common configuration library updated");

    rudder_ncf_local_updated::
      "any" usebundle => rudder_common_report("Common", "log_repaired", "${system_common.directiveId}", "Update", "None", "Local configuration library updated");

    config::
      "any" usebundle => rudder_common_report("Common", "log_repaired", "${system_common.directiveId}", "Update", "None", "Configuration policy updated");

    (config|rudder_ncf_common_updated|rudder_ncf_local_updated).!(rudder_promises_generated_tmp_file_error|rudder_ncf_common_update_error|rudder_ncf_local_update_error|no_update|rudder_ncf_hash_update_error)::
      "any" usebundle => rudder_common_report("Common", "result_repaired", "${system_common.directiveId}", "Update", "None", "Policy or configuration library were updated");

    server_ok::
      "any" usebundle => rudder_common_report("Common", "log_repaired", "${system_common.directiveId}", "Update", "None", "Started the server (cf-serverd)");
    executor_ok::
      "any" usebundle => rudder_common_report("Common", "log_repaired", "${system_common.directiveId}", "Update", "None", "Started the scheduler (cf-execd)");

    reports:
      # We want to have always reports if something goes bad
      rudder_promises_generated_error|no_update|rudder_promises_generated_tmp_file_error::
        "*********************************************************************************
  * rudder-agent could not get an updated configuration from the policy server.   *
  * This can be caused by a network issue, an unavailable server, or if this      *
  * node was deleted from the Rudder root server.                                 *
  * Any existing configuration policy will continue to be applied without change. *
  *********************************************************************************"
        action => immediate;
}


#######################################################

bundle agent rudder_check_agent_version
{
  methods:
    cfengine_3_1|cfengine_3_2|cfengine_3_3|cfengine_3_4|cfengine_3_5|cfengine_3_6|cfengine_3_7|cfengine_3_8|cfengine_3_9|cfengine_3_10|cfengine_3_11|cfengine_3_12|cfengine_3_13|cfengine_3_14::
      "any" usebundle => _abort("unsupported_agent", "This agent is not compatible with its Rudder server, please upgrade");
}

#######################################################

body agent control
{
    # if default runtime is 5 mins we need this for long jobs
        ifelapsed => "1";

    #define here some environment variables
        environment => { "DEBIAN_FRONTEND=noninteractive" };

        abortclasses => { "should_not_continue", "abort_agent_run" };

        agentfacility => "LOG_LOCAL6";

  # Repository where to put the copy of modified files
        default_repository => "${g.rudder_var}/modified-files";

&if(!INITIAL)&
# to protect copy methods and techniques
@if feature(copyfrom_restrict_keys)
      copyfrom_restrict_keys => { "&POLICY_SERVER_KEY&" };
@endif
&endif&

}

#######################################################

body executor control
{
        splaytime => "&AGENT_RUN_SPLAYTIME&";
        schedule         => { &AGENT_RUN_SCHEDULE& };
        executorfacility => "LOG_DAEMON";

        exec_command => "${g.rudder_command} agent run -uRN";
}

########################################################

#
# Insert the standard Rudder disclaimer into a file
#
bundle edit_line rudder_common_disclaimer
{
  vars:
      "file_edit_header" string => "${rudder_parameters.rudder_file_edit_header}";

  defaults:
      "file_edit_header" string => "#############################################################
### This file is protected by your Rudder infrastructure. ###
### Manually editing the file might lead your Rudder      ###
### infrastructure to change back the server's            ###
### configuration and/or to raise a compliance alert.     ###
#############################################################
",
        if_match_regex => "${const.dollar}{rudder_parameters.rudder_file_edit_header}";

  insert_lines:
      "${file_edit_header}"
        location    => start,
        insert_type => "preserve_block";
}

