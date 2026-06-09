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

      "rudder_https_only"      expression => strcmp("&POLICY_SERVER_HTTPS_ONLY&", "true");
      "rudder_cert_validation" expression => strcmp("&POLICY_SERVER_CERT_VALIDATION&", "true");
      "rudder_custom_ca"              not => strcmp("&POLICY_SERVER_CERT_CA&", "");

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

        tls_min_version => "1.3";

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
      # We need context for method reporting
      "Reporting context"                 usebundle => rudder_reporting_context_v4("common-hasPolicyServer-root","hasPolicyServer-root","Common","","","reportid");
      "Send agent update report"          usebundle => update_reports;
      "Update cfengine files permissions" usebundle => internal_security;
      "Disable cf-serverd"                usebundle => disable_cf_serverd,
                                                 if => "rudder_https_only";
      "Cleanup log files"                 usebundle => garbage_collection;
      "Setup agent check cron"            usebundle => setup_cronjob;
      "Make sure cron daemon is running"  usebundle => check_cron_daemon;
      "Make sure rpm key is up to date"   usebundle => import_rpm_key;
      # this one must be before any rudder-client call
&if(!INITIAL)&
      # The value is always empty in initial policies but we need to let it configurable locally.
      "Setup certificate authority"       usebundle => setup_ca;
&endif&

&if(INITIAL)&
      "Run initial inventory"             usebundle => doInventory;
&else&
      "Configure logs for reporting"      usebundle => send_rudder_reports;
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

      "schedule events"                   usebundle => rudder_event_scheduler;
}

bundle agent rudder_common_system_end {
  methods:
&if(!INITIAL)&
      "Post hook for monitoring methods"  usebundle => monitoring_hook_post;
&endif&

      "End report log"                    usebundle => endExecution;
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
    # sles 12 and after already have this
      "sles_11" expression => "SLES_11";
      "sles_11_1" expression => "SLES_11_1";
      "sles_11_2" expression => "SLES_11_2";
      "sles_11_3" expression => "SLES_11_3";
      "sles_11_4" expression => "SLES_11_4";

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
    # Always send start/end messages, this will ensure all sent runlogs are valid.
    # We can as disabled reporting is handled in the wrapper layer, and should as changes-only always produces some logs
    # (since HTTP reporting and agent output capture).
    # NOTE: This means heartbeat configuration is actually ignored and a report is sent for every agent run.
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

      "${g.rudder_var}/system-updates"
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
# This bundle is responsible for setting up the certificate authority required by the server
bundle agent setup_ca
{
  files:
      "${g.rudder_var}//lib/ssl/policy_server_ca.pem"
&if(POLICY_SERVER_CERT_CA)&
        content => "&POLICY_SERVER_CERT_CA&",
        create => "true",
        perms => mog("644","root","root");
&else&
        delete => tidy;
&endif&
}

#######################################################
# This bundle is responsible for the reporting of what happened in the update
# It can work because the classes defined during the update are persistent, so
# the classes are available for the next 4 minutes
bundle agent update_reports
{
  methods:
    rudder_https_only.!root_server::
      "any" usebundle => rudder_common_report("Common", "result_success", "${system_common.directiveId}", "Update", "None", "Update is done over HTTPS");

    p_no_update::
      "any" usebundle => rudder_common_report("Common", "result_error", "${system_common.directiveId}", "Update", "None", "Cannot update configuration policy");

    p_rudder_ncf_hash_update_error::
      "any" usebundle => rudder_common_report("Common", "result_error", "${system_common.directiveId}", "Update", "None", "Cannot update common configuration library (update flag file)");

    p_rudder_ncf_common_update_error::
      "any" usebundle => rudder_common_report("Common", "result_error", "${system_common.directiveId}", "Update", "None", "Cannot update common configuration library");

    p_rudder_ncf_local_update_error::
      "any" usebundle => rudder_common_report("Common", "result_error", "${system_common.directiveId}", "Update", "None", "Cannot update local configuration library");

    p_rudder_promises_generated_tmp_file_error::
      "any" usebundle => rudder_common_report("Common", "result_error", "${system_common.directiveId}", "Update", "None", "Cannot update configuration policy");

    # Success report relies on several matching conditions (nodes except root_server)
    # On all nodes except root server:
    #   - Staggered update: rudder_ncf_hash_update_ok OR (rudder_ncf_hash_update_repaired AND rudder_ncf_common_updated_ok AND rudder_ncf_local_updated_ok)
    #   - Staggered update: rudder_promises_generated_tmp_file_kept OR (rudder_promises_generated_tmp_file_repaired AND config_ok)
    # Note: its possible to have only rudder_ncf_hash_update_repaired and not rudder_ncf_common_updated, rudder_ncf_common_update_error but rudder_ncf_common_updated_ok if a technique is created, but not applied - in this case this is a success
    # There must be NO components in repair or error
    # Note: we can't use class new_promises_available here because they are local to the update_action bundle
    !root_server.(p_rudder_ncf_hash_update_ok|(p_rudder_ncf_hash_update_repaired.p_rudder_ncf_common_updated_ok.p_rudder_ncf_local_updated_ok)).(p_rudder_promises_generated_tmp_file_kept|(p_rudder_promises_generated_tmp_file_repaired.p_config_ok)).!(p_rudder_promises_generated_tmp_file_error|p_rudder_ncf_common_updated|p_rudder_ncf_common_update_error|p_rudder_ncf_local_updated|p_rudder_ncf_local_update_error|p_config|p_no_update|p_rudder_ncf_hash_update_error|(p_rudder_ncf_hash_update_repaired.!p_rudder_ncf_common_updated_ok))::
      "any" usebundle => rudder_common_report("Common", "result_success", "${system_common.directiveId}", "Update", "None", "Policy and configuration library are already up to date. No action required.");

    # Success report relies on several matching conditions (root_server only)
    # On the root server only:
    #   - Simple test: rudder_ncf_common_updated_ok
    #   - Simple test: rudder_ncf_local_updated_ok
    # There must be NO components in repair or error
    root_server.p_rudder_ncf_common_updated_ok.p_rudder_ncf_local_updated_ok.!(p_rudder_ncf_common_updated|p_rudder_ncf_common_update_error|p_rudder_ncf_local_updated|p_rudder_ncf_local_update_error)::
      "any" usebundle => rudder_common_report("Common", "result_success", "${system_common.directiveId}", "Update", "None", "Configuration library already up to date on this root server. No action required.");

    p_rudder_ncf_common_updated::
      "any" usebundle => rudder_common_report("Common", "log_repaired", "${system_common.directiveId}", "Update", "None", "Common configuration library updated");

    p_rudder_ncf_local_updated::
      "any" usebundle => rudder_common_report("Common", "log_repaired", "${system_common.directiveId}", "Update", "None", "Local configuration library updated");

    p_config::
      "any" usebundle => rudder_common_report("Common", "log_repaired", "${system_common.directiveId}", "Update", "None", "Configuration policy updated");

    (p_config|p_rudder_ncf_common_updated|p_rudder_ncf_local_updated).!(p_rudder_promises_generated_tmp_file_error|p_rudder_ncf_common_update_error|p_rudder_ncf_local_update_error|p_no_update|p_rudder_ncf_hash_update_error)::
      "any" usebundle => rudder_common_report("Common", "result_repaired", "${system_common.directiveId}", "Update", "None", "Policy or configuration library were updated");

    server_ok::
      "any" usebundle => rudder_common_report("Common", "log_repaired", "${system_common.directiveId}", "Update", "None", "Started the server (cf-serverd)");
    executor_ok::
      "any" usebundle => rudder_common_report("Common", "log_repaired", "${system_common.directiveId}", "Update", "None", "Started the scheduler (cf-execd)");

    reports:
      # We want to have always reports if something goes bad
      p_rudder_promises_generated_error|p_no_update|p_rudder_promises_generated_tmp_file_error::
&if(INITIAL)&
        "*********************************************************************************
  *  You must accept this node on the server before updating this node            *
  *  This node uses initial policies, some error message are expected until       *
  *  the node is accepted.                                                        *
  *********************************************************************************"
&endif&

&if(!INITIAL)&
        "*********************************************************************************
  * rudder-agent could not get an updated configuration from the policy server.   *
  * This can be caused by a network issue, an unavailable server, or if this      *
  * node was deleted from the Rudder root server.                                 *
  * Any existing configuration policy will continue to be applied without change. *
  *********************************************************************************"
&endif&

        action => immediate;
}


#######################################################

bundle agent rudder_check_agent_version
{
  methods:
    cfengine_3_1|cfengine_3_2|cfengine_3_3|cfengine_3_4|cfengine_3_5|cfengine_3_6|cfengine_3_7|cfengine_3_8|cfengine_3_9|cfengine_3_10|cfengine_3_11|cfengine_3_12|cfengine_3_13|cfengine_3_14|cfengine_3_15::
      "any" usebundle => _abort("unsupported_agent", "This agent is not compatible with its Rudder server, please upgrade");
}

#######################################################
# Select the syslog facility to use based on node property syslog_facility
bundle common rudder_syslog_facility {
  vars:
@if feature(syslog_disableable)
      "valid_values" slist => {"NONE", "LOG_USER", "LOG_DAEMON", "LOG_LOCAL0", "LOG_LOCAL1", "LOG_LOCAL2", "LOG_LOCAL3", "LOG_LOCAL4", "LOG_LOCAL5", "LOG_LOCAL6", "LOG_LOCAL7"};
      "syslog"      string => "NONE";
@else
      "valid_values" slist => {"LOG_USER", "LOG_DAEMON", "LOG_LOCAL0", "LOG_LOCAL1", "LOG_LOCAL2", "LOG_LOCAL3", "LOG_LOCAL4", "LOG_LOCAL5", "LOG_LOCAL6", "LOG_LOCAL7"};
      "syslog"      string => "LOG_LOCAL3";
@endif

    rudder_is_syslog_out_valid::
      "syslog" string => "${node.properties[rudder][log][syslog_facility]}";
  classes:
    "rudder_is_syslog_out_valid" expression => strcmp("${valid_values}", "${node.properties[rudder][log][syslog_facility]}");
}


#######################################################

body agent control
{
    # if default runtime is 5 mins we need this for long jobs
        ifelapsed => "1";

    #define here some environment variables
        environment => { "DEBIAN_FRONTEND=noninteractive" };

        abortclasses => { "should_not_continue", "abort_agent_run" };

        agentfacility => "${rudder_syslog_facility.syslog}";

  # Repository where to put the copy of modified files
        default_repository => "${g.rudder_var}/modified-files";

        default_timeout => "10";

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

