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

      # on changes only, we only reports on repaired or error message
      "changes_only_mode"      expression => strcmp("&RUDDER_REPORT_MODE&", "changes-only");
      "reports_disabled_mode"  expression => strcmp("&RUDDER_REPORT_MODE&", "reports-disabled");
      "changes_only"           expression => "changes_only_mode.!force_full_compliance";
      "reports_disabled"       expression => "reports_disabled_mode.!force_full_compliance";
      "rudder_reporting_https" expression => strcmp("&REPORTING_PROTOCOL&", "HTTPS");
      "rsyslog_disabled"       expression => strcmp("&SYSLOG_PROTOCOL_DISABLED&", "true");

      "rudder_global_audit_mode"      not => strcmp("&RUDDER_COMPLIANCE_MODE&", "enforce");

      # full compliance is the default mode
      "full_compliance" not => "changes_only|reports_disabled";
      "rudder_reporting_syslog" not => "rudder_reporting_https";
}

body common control
{
        output_prefix => "rudder";
        protocol_version => "2";

        inputs => {
          @{va.inputs_list},
          "rudder-system-directives.cf",
        };

        # force tls1.2
        tls_min_version => "1.2";

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
      # Hackish way to skip the cf-promises on component check, otherwise it
      # won't work on the agent since the bundle will not be loaded.
      "root_component_check"              string => "root_component_check";

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
      "Check root services"               usebundle => "${root_component_check}";
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
      "capability_option"      string => "--capability-file /opt/rudder/etc/agent-capabilities";
      "rudder_var"             string => "/var/rudder";
      "ncf_path"               string => "${rudder_var}/ncf";
      "shell_type"             string => "useshell";
      "list_compatible_inputs" string => "NCF_CACHE_PATH=${sys.workdir}/state /bin/sh ${ncf_path}/common/10_ncf_internals/list-compatible-inputs";
      "compatible_inputs_cmd"  string => "${list_compatible_inputs} ${capability_option} --agent-version ${sys.cf_version} --ncf-path '${ncf_path}'";

    any::
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
    # all other inputs are loaded by body file control in rudder-system-directives.cf
      "inputs_list" slist => { @{ncf_inputs} };

&if(INITIAL)&
      # rudder-directives.cf is needed, even if empty since we call it in the bundle sequence
      # Idealy these should be generated at compile time from metadata.xml and put into variable.json
      "common_input_list" slist => {  "rudder-directives.cf", "common/1.0/common.cf", "common/1.0/cf-serverd.cf", "common/1.0/rudder-groups.cf", "common/1.0/hooks.cf", "common/1.0/cron-setup.cf", "common/1.0/site.cf", "common/1.0/update.cf", "common/1.0/monitoring.cf", "common/1.0/restart-services.cf", "common/1.0/internal-security.cf",  "common/1.0/environment-variables.cf", "common/1.0/properties.cf" };
      "inventory_input_list" slist => { "inventory/1.0/fusionAgent.cf" };
      "distribute_policy_input_list" slist => { "distributePolicy/1.0/common.cf", "distributePolicy/1.0/rsyslogConf.cf", "distributePolicy/1.0/propagatePromises.cf", "distributePolicy/1.0/apache-acl.cf" };
      "server_roles_input_list" slist => { "server-roles/1.0/common.cf", "server-roles/1.0/relayd.cf", "server-roles/1.0/component-check.cf", "server-roles/1.0/alive-check.cf", "server-roles/1.0/service-check.cf", "server-roles/1.0/integrity-check.cf", "server-roles/1.0/network-check.cf", "server-roles/1.0/password-check.cf", "server-roles/1.0/postgres-check.cf", "server-roles/1.0/logrotate-check.cf", "server-roles/1.0/technique-reload.cf", "server-roles/1.0/compress-webapp-log.cf", "server-roles/1.0/compress-ldap-backups.cf", "server-roles/1.0/servers-by-role.cf" };

    policy_server::
      "inputs_list" slist => { @{ncf_inputs}, @{common_input_list}, @{inventory_input_list}, @{distribute_policy_input_list}, @{server_roles_input_list} };
    !policy_server::
      "inputs_list" slist => { @{ncf_inputs}, @{common_input_list}, @{inventory_input_list} };
&endif&

  classes:
    # OS classes for compatibility
      "SUSE" expression => "sles";
      "SuSE" expression => "sles";
      "suse" expression => "sles";
    
    # We are inside Rudder policies
      "rudder_policies" expression => "any";

    # I don't know why, but these classes are not evaluated if they are in rudder_roles
      # Policy Server is a machine which delivers promises
      "policy_server" expression => strcmp("root","${rudder_roles.uuid}");
      # Root Server is the top policy server machine
      "root_server" expression => strcmp("root","${rudder_roles.uuid}");
    # static definition of the machine roles
&NODEROLE&

}

#########################################################
# Control execution
#########################################################
bundle agent startup
{
  methods:
    # Should we send a message stating this agent is starting up?
    # Always do it in "full_compliance" mode
    # In other modes, only do it here if we need to send it as a "heartbeat", that is if it hasn't already been sent recently enough
    full_compliance|!heartbeat_sent::
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
      "components" slist => { "cf-serverd", "cf-execd" };

  classes:
      "should_disable_agent" expression => fileexists("${g.rudder_disable_agent_file}");

      # Only define this class when we're ready to die - this is a special class name in "abortclasses"
      "should_not_continue"
        expression => "should_disable_agent.!ignore_disable_agent",
        ifvarclass => "abort_report_done";

  processes:
    should_disable_agent::
      "${sys.workdir}/bin/${components}"
        signals       => { "term", "kill" },
        action        => immediate;

  reports:
    should_disable_agent::
      "FATAL: The file ${g.rudder_disable_agent_file} is present. Rudder will kill all running daemons and halt immediately."
        classes => if_ok("abort_report_done"),
        action  => immediate;

}

###################################################
# Special handler when aborting from ncf
###################################################
bundle agent abort_rudder(reason, message)
{
  methods:
      "abort log" usebundle => rudder_common_report("Common", "log_info", "${system_common.directiveId}", "Abort run", "${reason}", "${message}");
      "end run"   usebundle => endExecution;
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
    rudder_reporting_syslog.!reports_disabled::
      "configure_syslog"    usebundle => check_log_system;
      "configure_reporting" usebundle => check_rsyslog_version;

    (rudder_reporting_syslog.reports_disabled)|rudder_reporting_https::
      "remove_reporting"    usebundle => remove_rudder_syslog_configuration;

    rudder_reporting_https::
      "make http reports"   usebundle => send_rudder_reports;
}

#######################################################
# Check the log system, and configure it accordingly
#
# Note: if any configurations are changed here, corresponding services must be
# restarted in the restart_services bundle

bundle agent check_log_system
{
  vars:
    debian::
      "syslog_ng_source" string => "s_src";

    SuSE::
      "syslog_ng_source" string => "src";

    redhat::
      "syslog_ng_source" string => "s_sys";

    use_tcp::
      "rsyslog_rule_prefix"   string => "@@";
      "syslogng_rule_prefix"  string => "tcp";
    use_udp::
      "rsyslog_rule_prefix"   string => "@";
      "syslogng_rule_prefix"  string => "udp";

    rsyslogd_conffile_present.rsyslog_version_5_6_4_ok::

      "rsyslogd_message_reduction" string => "$RepeatedMsgReduction off${const.n}";

    rsyslogd_conffile_present.rsyslog_version_5_6_4_not_ok::

      "rsyslogd_message_reduction" string => "";

    any::

      "syslog_conf_comment"   string => "# Autogenerated by rudder, do not edit${const.n}";
      "syslog_ng_conf_prefix" string => "filter f_local_rudder{facility(local6) and program(\"rudder.*|cf-agent.*\");};destination rudder_loghost {";
      "syslog_ng_conf_proto"  string => "${syslogng_rule_prefix}";
      "syslog_ng_conf_start"  string => "(\"";
      "syslog_ng_conf_port"   string => "\" port (";
      "syslog_ng_conf_portno" string => "&SYSLOGPORT&";
      "syslog_ng_conf_suffix" string => "));};log {source(${syslog_ng_source});filter(f_local_rudder);destination(rudder_loghost);";
      "syslog_ng_conf_final"  string => "flags(final);};";

      "syslog_ng_conf"        string => concat("${syslog_conf_comment}", "${syslog_ng_conf_prefix}", "${syslog_ng_conf_proto}", "${syslog_ng_conf_start}", "${server_info.policy_server}", "${syslog_ng_conf_port}", "${syslog_ng_conf_portno}", "${syslog_ng_conf_suffix}", "${syslog_ng_conf_final}");
      "syslog_ng_conf_regex"  string => concat(escape("filter f_local_rudder{facility(local6) and program("), ".*", escape(");};destination rudder_loghost {"), "(tcp|udp)", escape("${syslog_ng_conf_start}"), "[^\"]+", escape("${syslog_ng_conf_port}"), "[0-9]+", escape("${syslog_ng_conf_suffix}"), ".*");

      "rsyslog_conffile"      string => "/etc/rsyslog.conf";
      "syslogng_conffile"     string => "/etc/syslog-ng/syslog-ng.conf";
      "syslog_conffile"       string => "/etc/syslog.conf";

      "rsyslog_rudder_conffile" string => "/etc/rsyslog.d/rudder-agent.conf";

  classes:

    any::
      "rsyslogd_conffile_present" expression => fileexists("${rsyslog_conffile}"), scope => "namespace";
      "syslogng_conffile_present" expression => fileexists("${syslogng_conffile}"), scope => "namespace";
      "syslogd_conffile_present"  expression => fileexists("${syslog_conffile}"), scope => "namespace";

      "use_udp" expression => strcmp("&RUDDER_SYSLOG_PROTOCOL&", "UDP");
      "use_tcp" expression => strcmp("&RUDDER_SYSLOG_PROTOCOL&", "TCP");

      "pass3" expression => "pass2";
      "pass2" expression => "pass1";
      "pass1" expression => "any";

      "check_rsyslog_version_present" expression => fileexists("${this.promise_dirname}/common/1.0/check-rsyslog-version");

  files:

    rsyslogd_conffile_present::
      "${rsyslog_conffile}"
        edit_line => append_if_no_lines("$IncludeConfig /etc/rsyslog.d/*.conf"),
        edit_defaults => noempty_backup,
        comment => "Add the rsyslog.conf.d include if not already present",
        classes => kept_if_else("rsyslog_kept", "rsyslog_repaired" , "rsyslog_failed");

    (rsyslog_version_5_6_4_ok|rsyslog_version_5_6_4_not_ok).rsyslogd_conffile_present.!policy_server::
      "${rsyslog_rudder_conffile}"
        edit_line => append_if_no_lines("#Rudder log system${const.n}${rsyslogd_message_reduction}${const.n}if $syslogfacility-text == 'local6' and $programname startswith 'rudder' then ${rsyslog_rule_prefix}${server_info.policy_server}:&SYSLOGPORT&${const.n}if $syslogfacility-text == 'local6' and $programname startswith 'rudder' then ~${const.n}if $syslogfacility-text == 'local6' and $programname startswith 'cf-agent' then ${rsyslog_rule_prefix}${server_info.policy_server}:&SYSLOGPORT&${const.n}if $syslogfacility-text == 'local6' and $programname startswith 'cf-agent' then ~"),
        create => "true",
        edit_defaults => empty_backup,
        classes       => classes_generic("rsyslog");

    SuSE.rsyslogd_conffile_present.policy_server::
      # For SuSE, ensure that SYSLOG_DAEMON is set to 'rsyslogd' even if another syslog has been installed before
      "/etc/sysconfig/syslog"
        edit_line => ensure_rsyslogd_on_suse,
        edit_defaults => noempty_backup,
        classes       => classes_generic("rsyslog");

    syslogng_conffile_present.!policy_server::
      "${syslogng_conffile}"
        edit_line => edit_syslog_conf_file("${syslog_ng_conf}", "${syslog_ng_conf_regex}"),
        edit_defaults => noempty_backup,
        classes       => classes_generic("syslog_ng");

    syslogd_conffile_present.!policy_server::
      "${syslog_conffile}"
        edit_line => fix_syslogd("@${server_info.policy_server}"),
        edit_defaults => noempty_backup,
        classes       => classes_generic("syslogd");

  methods:

    pass2.syslogd_conffile_present::
      "any" usebundle => rudder_common_report("Common", "log_info", "${system_common.directiveId}", "Log system for reports", "None", "Detected running syslog as syslogd");

    pass2.syslogng_conffile_present::
      "any" usebundle => rudder_common_report("Common", "log_info", "${system_common.directiveId}", "Log system for reports", "None", "Detected running syslog as syslog-ng");

    pass2.rsyslogd_conffile_present::
      "any" usebundle => rudder_common_report("Common", "log_info", "${system_common.directiveId}", "Log system for reports", "None", "Detected running syslog as rsyslog");

    pass3.(syslogd_failed|syslog_ng_failed|rsyslog_failed)::
      "any" usebundle => rudder_common_report("Common", "result_error", "${system_common.directiveId}", "Log system for reports", "None", "Logging system could not be configured for report centralization");

    pass3.!(syslogd_failed|syslog_ng_failed|rsyslog_failed).(syslogd_repaired|syslog_ng_repaired|rsyslog_repaired)::
      "any" usebundle => rudder_common_report("Common", "result_repaired", "${system_common.directiveId}", "Log system for reports", "None", "Configured logging system for report centralization");

    pass3.!(syslogd_failed|syslog_ng_failed|rsyslog_failed).!(syslogd_repaired|syslog_ng_repaired|rsyslog_repaired).(syslogd_conffile_present.syslogd_kept|syslogng_conffile_present.syslog_ng_kept|rsyslogd_conffile_present.rsyslog_kept))::
      "any" usebundle => rudder_common_report("Common", "result_success", "${system_common.directiveId}", "Log system for reports", "None", "Logging system for report centralization is already correctly configured");

  commands:

    rsyslogd_conffile_present.check_rsyslog_version_present::
      # 5.6.4 is the first version mentionning RepeatedMsgReduction in http://www.rsyslog.com/change-log/
      # The option is probably older though.
      "/bin/sh ${this.promise_dirname}/common/1.0/check-rsyslog-version 5.6.4"
        contain => in_shell,
        module  => "true",
        comment => "Check rsyslog version to know if it supports RepeatedMsgReduction";

}

#######################################################
# Check the version of rsyslog, and correct the conf
# file if > 5.7.1
# This is done in another bundle than check_log_system
# as it would make it too complex to read and maintain
# (we would have needed to delay the restart of the services
# at later iteration)
#
# Note: if any configurations are changed here, corresponding services must be
# restarted in the restart_services bundle

bundle agent check_rsyslog_version {

  classes:
      "check_rsyslog_version_present" expression => fileexists("${this.promise_dirname}/common/1.0/check-rsyslog-version");
      "rsyslogd" expression => fileexists("${check_log_system.rsyslog_conffile}");

    any::
      "pass3" expression => "pass2";
      "pass2" expression => "pass1";
      "pass1" expression => "any";

  files:
    rsyslogd.rsyslog_version_5_7_1_ok::
      "/etc/rsyslog.d/remove_limit.conf"
        edit_line => append_if_no_lines("$SystemLogRateLimitInterval 0"),
        edit_defaults => noempty_backup,
        create    => "true",
        comment   => "Add a config line in the rsyslog.conf file to prevent from dropping rudder messages",
        classes   => classes_generic("rsyslog_limit");

  methods:

    pass3.(rsyslogd.!check_rsyslog_version_present)::
      "any" usebundle => rudder_common_report("Common", "result_error", "${system_common.directiveId}", "Log system for reports", "None", "The file ${this.promise_dirname}/common/1.0/check-rsyslog-version is missing");

    pass3.rsyslog_limit_error::
      "any" usebundle => rudder_common_report("Common", "result_error", "${system_common.directiveId}", "Log system for reports", "None", "Could not remove message limit in rsyslog");

    pass3.rsyslog_limit_repaired::
      "any" usebundle => rudder_common_report("Common", "log_repaired", "${system_common.directiveId}", "Log system for reports", "None", "Updated the rsyslog configuration to remove message limit");

  commands:
    rsyslogd.check_rsyslog_version_present::
      "/bin/sh ${this.promise_dirname}/common/1.0/check-rsyslog-version"
        contain => in_shell,
        module  => "true",
        comment => "Check rsyslog version in order to add or not a configuration line in rsyslog.conf";

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

  classes:
      "syslogng" expression => fileexists("${check_log_system.syslogng_conffile}");
      "syslogd"  expression => fileexists("${check_log_system.syslog_conffile}");

  files:
      "${check_log_system.rsyslog_rudder_conffile}"
        delete         => tidy,
        classes        => classes_generic("remove_rudder_syslog_configuration_rsyslog");

    syslogng::
      "${check_log_system.syslogng_conffile}"
        edit_line      => delete_lines_matching("${syslogng_delete_lines_patterns}"),
        edit_defaults  => noempty_backup,
        classes        => classes_generic("remove_rudder_syslog_configuration_syslogng");

    syslogd::
      "${check_log_system.syslog_conffile}"
        edit_line      => delete_lines_matching("${syslog_delete_lines_patterns}"),
        edit_defaults  => noempty_backup,
        classes        => classes_generic("remove_rudder_syslog_configuration_syslog");

  methods:
      "merge result classes" usebundle  => _classes_combine_three("remove_rudder_syslog_configuration_rsyslog", "remove_rudder_syslog_configuration_syslogng", "remove_rudder_syslog_configuration_syslog", "remove_rudder_syslog_configuration_result");
      "reports"              usebundle  => rudder_common_reports_generic("Common", "remove_rudder_syslog_configuration_result", "${system_common.directiveId}", "Log system for reports", "None", "Rudder syslog configuration removed");

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

    (config|rudder_ncf_common_updated|rudder_ncf_local_updated|server_ok|executor_ok).!(rudder_promises_generated_tmp_file_error|rudder_ncf_common_update_error|rudder_ncf_local_update_error|no_update|rudder_ncf_hash_update_error)::
      "any" usebundle => rudder_common_report("Common", "result_repaired", "${system_common.directiveId}", "Update", "None", "Policy or configuration library were updated or agent service restarted");

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
    cfengine_3_1|cfengine_3_2|cfengine_3_3|cfengine_3_4|cfengine_3_5|cfengine_3_6|cfengine_3_7|cfengine_3_8|cfengine_3_9::
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

#Enforce that the file only contains this information
bundle edit_line enforce_content(str)
{
  delete_lines:
      "${str}" not_matching => "true";

  insert_lines:
      "${str}";
}

# Never used in initial promises
&if(!INITIAL)&
# Fix syslogd content : caution, the @ must be in the argument
bundle edit_line fix_syslogd(syslogd)
{
  delete_lines:

      "^\s*local6.*"
        comment => "Delete all local6 facility related lines";

  insert_lines:

      "# Rudder specific logging parameters";
      "local6.notice ${syslogd}"
        comment => "Add the rudder syslogd destination";

}

bundle edit_line edit_syslog_conf_file(line_to_add, pattern_to_remove)
{

  delete_lines:
      "${pattern_to_remove}";

      # Remove any unexpanded variables that may have slipped through due to old bugs or race conditions at installation time
      "\$\(syslog_ng_conf\)";
      "\$\{syslog_ng_conf\}";

      # Remove the syslog config when the loghost was just called "loghost" (now called "rudder_loghost")
      # This was used in Rudder pre-3.0. We need to keep this in all versions that support upgrading from pre-3.0 versions (up to and including 2.11)
      # See http://www.rudder-project.org/redmine/issues/5773
      "filter\ f\_local\_rudder\{facility\(local6\)\ and\ program\(\"rudder\.\*\"\)\;\}\;destination\ loghost\ \{(tcp|udp)\(\"[^\"]+\".*";
  insert_lines:
      "${line_to_add}"
        location => syslogng_log_part;
}
body location syslogng_log_part
{
        select_line_matching => "^\s*log\s*\{.*";
        before_after => "before";
        first_last => "first";
}
bundle edit_line ensure_rsyslogd_on_suse
{
  field_edits:
      # match a line starting like 'SYSLOG_DAEMON=something'
      "^SYSLOG_DAEMON=.*$"
        edit_field => col("=","2","\"rsyslogd\"","set"),
        comment => "Match a line starting like key = something";
}
&endif&

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

