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
body common control
{

    any::
        output_prefix => "rudder";

        inputs => {
          @{va.ncf_inputs},
          "rudder_system_directives.cf",
          "rudder_directives.cf"
        };

        bundlesequence => { @{va.bs}, rudder_system_directives, rudder_directives, @{va.end} };

    android::
        # if native syslog is not available, use cfengine implementation (eg: android)
        syslog_host => "${server_info.cfserved}";
        syslog_port => "&SYSLOGPORT&";
}

bundle common va
{
  vars:

      "end" slist => { "restart_services", "endExecution" };

    policy_server::

      "bs" slist => {
        "startup",
        "check_disable_agent",
        "clean_red_button",
        "update",
        "configuration",
        "set_red_button",
        "internal_security",
        "check_red_button_status",
        "check_cf_processes",
        "check_uuid",
        "configure_rudder_reporting_system",
        "garbage_collection",
        "setup_cronjob",
        "check_cron_daemon",
        "check_binaries_freshness",
        "check_zypper",
        "get_environment_variables",
        "nxlog_enable",
        "configuration"
      };

    !policy_server::

      "bs" slist => {
        "startup",
        "check_disable_agent",
        "clean_red_button",
        "update",
        "configuration",
        "set_red_button",
        "internal_security",
        "check_red_button_status",
        "check_cf_processes",
        "garbage_collection",
        "setup_cronjob",
        "check_cron_daemon",
        "check_binaries_freshness",
        "configure_rudder_reporting_system",
        "nxlog_enable",
        "check_zypper",
        "check_uuid",
        "get_environment_variables",
        "configuration"
      };

    !android.!windows::
      "rudder_var"     string => "/var/rudder";
    android::
      "rudder_var"     string => "/data/rudder";

    !windows::
      "ncf_path"       string => "${rudder_var}/ncf";
      "rudder_tools"   string => "${rudder_var}/tools";

    windows::
      "rudder_base"    string => "${sys.winprogdir}\Rudder";
      "rudder_tools"   string => "${rudder_base}\sbin";
      "ncf_path"       string => "${rudder_base}\ncf";

    !windows::

      "list_compatible_inputs" string => "NCF_CACHE_PATH=${sys.workdir}/state /bin/sh ${ncf_path}/common/10_ncf_internals/list-compatible-inputs";

      "ncf_common_inputs_10" slist => splitstring(execresult("${list_compatible_inputs} ${sys.cf_version} ${ncf_path}/common 10_ncf_internals", "useshell"), "\n", 10000);
      "ncf_common_inputs_20" slist => splitstring(execresult("${list_compatible_inputs} ${sys.cf_version} ${ncf_path}/common 20_cfe_basics", "useshell"), "\n", 10000);
      "ncf_common_inputs_30" slist => splitstring(execresult("${list_compatible_inputs} ${sys.cf_version} ${ncf_path}/common 30_generic_methods", "useshell"), "\n", 10000);
      "ncf_common_inputs_40" slist => splitstring(execresult("${list_compatible_inputs} ${sys.cf_version} ${ncf_path}/common 40_it_ops_knowledge", "useshell"), "\n", 10000);
      "ncf_common_inputs_50" slist => splitstring(execresult("${list_compatible_inputs} ${sys.cf_version} ${ncf_path}/common 50_techniques", "useshell"), "\n", 10000);
      "ncf_common_inputs_60" slist => splitstring(execresult("${list_compatible_inputs} ${sys.cf_version} ${ncf_path}/common 60_services", "useshell"), "\n", 10000);

      "ncf_local_inputs_10" slist => splitstring(execresult("${list_compatible_inputs} ${sys.cf_version} ${ncf_path}/local 10_ncf_internals", "useshell"), "\n", 10000);
      "ncf_local_inputs_20" slist => splitstring(execresult("${list_compatible_inputs} ${sys.cf_version} ${ncf_path}/local 20_cfe_basics", "useshell"), "\n", 10000);
      "ncf_local_inputs_30" slist => splitstring(execresult("${list_compatible_inputs} ${sys.cf_version} ${ncf_path}/local 30_generic_methods", "useshell"), "\n", 10000);
      "ncf_local_inputs_40" slist => splitstring(execresult("${list_compatible_inputs} ${sys.cf_version} ${ncf_path}/local 40_it_ops_knowledge", "useshell"), "\n", 10000);
      # Files from 50_techniques are automatically included by Rudder's metadata.xml mechanism
      "ncf_local_inputs_60" slist => splitstring(execresult("${list_compatible_inputs} ${sys.cf_version} ${ncf_path}/local 60_services", "useshell"), "\n", 10000);

    windows::

      # Those direct PS calls should be replaced by a proper script, as explained in #5659

      "ncf_common_inputs_10" slist => splitstring(execresult("c:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe -Command \"ls -Path 'c:\Program Files\Rudder\var\ncf\common\10_ncf_internals' -Name *.cf -Recurse -ErrorAction SilentlyContinue | % { Write-Host c:\Program Files\Rudder\var\ncf\common\10_ncf_internals\$_ }\"", "useshell"), "\n", 10000);
      "ncf_common_inputs_20" slist => splitstring(execresult("c:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe -Command \"ls -Path 'c:\Program Files\Rudder\var\ncf\common\20_cfe_basics' -Name *.cf -Recurse -ErrorAction SilentlyContinue | % { Write-Host c:\Program Files\Rudder\var\ncf\common\20_cfe_basics\$_ }\"", "useshell"), "\n", 10000);
      "ncf_common_inputs_30" slist => splitstring(execresult("c:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe -Command \"ls -Path 'c:\Program Files\Rudder\var\ncf\common\30_generic_methods' -Name *.cf -Recurse -ErrorAction SilentlyContinue | % { Write-Host c:\Program Files\Rudder\var\ncf\common\30_generic_methods\$_ }\"", "useshell"), "\n", 10000);
      "ncf_common_inputs_40" slist => splitstring(execresult("c:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe -Command \"ls -Path 'c:\Program Files\Rudder\var\ncf\common\40_it_ops_knowledge' -Name *.cf -Recurse -ErrorAction SilentlyContinue | % { Write-Host c:\Program Files\Rudder\var\ncf\common\40_it_ops_knowledge\$_ }\"", "useshell"), "\n", 10000);
      "ncf_common_inputs_50" slist => splitstring(execresult("c:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe -Command \"ls -Path 'c:\Program Files\Rudder\var\ncf\common\50_techniques' -Name *.cf -Recurse -ErrorAction SilentlyContinue | % { Write-Host c:\Program Files\Rudder\var\ncf\common\50_techniques\$_ }\"", "useshell"), "\n", 10000);
      "ncf_common_inputs_60" slist => splitstring(execresult("c:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe -Command \"ls -Path 'c:\Program Files\Rudder\var\ncf\common\60_services' -Name *.cf -Recurse -ErrorAction SilentlyContinue | % { Write-Host c:\Program Files\Rudder\var\ncf\common\60_services\$_ }\"", "useshell"), "\n", 10000);

      "ncf_local_inputs_10" slist => splitstring(execresult("c:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe -Command \"ls -Path 'c:\Program Files\Rudder\var\ncf\local\10_ncf_internals' -Name *.cf -Recurse -ErrorAction SilentlyContinue | % { Write-Host c:\Program Files\Rudder\var\ncf\local\10_ncf_internals\$_ }\"", "useshell"), "\n", 10000);
      "ncf_local_inputs_20" slist => splitstring(execresult("c:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe -Command \"ls -Path 'c:\Program Files\Rudder\var\ncf\local\20_cfe_basics' -Name *.cf -Recurse -ErrorAction SilentlyContinue | % { Write-Host c:\Program Files\Rudder\var\ncf\local\20_cfe_basics\$_ }\"", "useshell"), "\n", 10000);
      "ncf_local_inputs_30" slist => splitstring(execresult("c:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe -Command \"ls -Path 'c:\Program Files\Rudder\var\ncf\local\30_generic_methods' -Name *.cf -Recurse -ErrorAction SilentlyContinue | % { Write-Host c:\Program Files\Rudder\var\ncf\local\30_generic_methods\$_ }\"", "useshell"), "\n", 10000);
      "ncf_local_inputs_40" slist => splitstring(execresult("c:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe -Command \"ls -Path 'c:\Program Files\Rudder\var\ncf\local\40_it_ops_knowledge' -Name *.cf -Recurse -ErrorAction SilentlyContinue | % { Write-Host c:\Program Files\Rudder\var\ncf\local\40_it_ops_knowledge\$_ }\"", "useshell"), "\n", 10000);
      # Files from 50_techniques are automatically included by Rudder's metadata.xml mechanism
      "ncf_local_inputs_60" slist => splitstring(execresult("c:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe -Command \"ls -Path 'c:\Program Files\Rudder\var\ncf\local\60_services' -Name *.cf -Recurse -ErrorAction SilentlyContinue | % { Write-Host c:\Program Files\Rudder\var\ncf\local\60_services\$_ }\"", "useshell"), "\n", 10000);

    any::

      # ncf_inputs contains all the files of ncf, and ignore the empty lists
      "ncf_inputs" slist => {
                             "@{ncf_common_inputs_10}",
                             "@{ncf_common_inputs_20}",
                             "@{ncf_common_inputs_30}",
                             "@{ncf_common_inputs_40}",
                             "@{ncf_common_inputs_50}",
                             "@{ncf_common_inputs_60}",
                             "@{ncf_local_inputs_10}",
                             "@{ncf_local_inputs_20}",
                             "@{ncf_local_inputs_30}",
                             "@{ncf_local_inputs_40}",
                             "@{ncf_local_inputs_60}",
                            },
                  policy => "ifdefined";


    # definition of the machine roles
&NODEROLE&
}

bundle common rudder_roles
{

  classes:
      # Abort if no uuid is defined
      "should_not_continue" not => fileexists("${g.uuid_file}");

      # Policy Server is a machine which delivers promises
      "policy_server" expression => strcmp("root","${g.uuid}");
      # Root Server is the top policy server machine
      "root_server" expression => strcmp("root","${g.uuid}");


      # on changes only, we only reports on repaired or error message
      "changes_only"     expression => strcmp("&RUDDER_REPORT_MODE&", "changes-only");
      "reports_disabled" expression => strcmp("&RUDDER_REPORT_MODE&", "reports-disabled");

      # full compliance is the default mode
      "full_compliance" not => "changes_only|reports_disabled";
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
##########################################################
bundle agent check_disable_agent
{
  vars:
      "components" slist => { "cf-serverd", "cf-execd", "cf-monitord" };

  classes:
      "should_disable_agent" expression => fileexists("${g.rudder_disable_agent_file}");

      # Only define this class when we're ready to die - this is a special class name in "abortclasses"
      "should_not_continue"
        expression => "should_disable_agent",
        ifvarclass => "abort_report_done";

  processes:
    should_disable_agent::
      "${sys.workdir}/bin/${components}"
        signals       => { "term", "kill" };

  reports:
    should_disable_agent::
      "FATAL: The file ${g.rudder_disable_agent_file} is present. Rudder will kill all running daemons and halt immediately."
        classes => if_ok("abort_report_done");

}


##########################################################
# Red Button part.
# When the file ${sys.workdir}/inputs/stop exists, we must stop the
# execution of the agent on all client machines
##########################################################
bundle agent clean_red_button()
{
  files:
    safe.policy_server::
      "${g.rudder_var}/share/[a-f0-9A-F\-]+/rules/cfengine-(community|nova)/stopFile"
        delete => tidy,
        comment => "Deleting the stop file on clients promises, cfengine is good to go";

    safe.!policy_server::
      "${sys.workdir}/inputs/stopFile"
        delete => tidy,
        comment => "Deleting the stop file, cfengine is good to go";

  methods:
    safe::
      "any" usebundle => rudder_common_report("Common", "result_repaired", "&TRACKINGKEY&", "Red Button", "None", "Authorizing Cfengine to restart");

  commands:
    safe.policy_server::
      "${sys.workdir}/bin/cf-runagent"
        args => "-Dsafe",
        comment => "Propagate the safe information to children";

}

bundle agent set_red_button()
{
  classes:
    policy_server::
      "danger" expression => fileexists("${g.rudder_var}/share/root/stopFile");
  methods:
    danger::
      "any" usebundle => setStopFile;

    danger.policy_server::
      "any" usebundle => stopClients;
}

bundle agent setStopFile
{
  files:
    danger.!policy_server::
      "${sys.workdir}/inputs/stopFile"
        create => "true";
    danger.policy_server::
      "${g.rudder_var}/share/[a-f0-9A-F\-]+/rules/cfengine-(community|nova)/stopFile"
        create => "true";



  methods:
    danger.!policy_server::
      "any" usebundle => rudder_common_report("Common", "result_repaired", "&TRACKINGKEY&", "Red Button", "None", "Creating local stop file for this node");
    danger.policy_server::
      "any" usebundle => rudder_common_report("Common", "result_repaired", "&TRACKINGKEY&", "Red Button", "None", "Creating stop files for all clients of this policy server");

}

bundle agent stopClients
{
  classes:
    policy_server::
      "danger" expression => fileexists("${g.rudder_var}/share/root/stopFile");

  methods:
    danger.policy_server::
      "any" usebundle => rudder_common_report("Common", "log_repaired", "&TRACKINGKEY&", "Red Button", "None", "Actively stopping CFEngine operations on all clients of this policy server (via cf-runagent)");

  commands:
    danger.policy_server::
      "${sys.workdir}/bin/cf-runagent"
        args => "-Ddanger",
        comment => "Propagate the danger information to children";

}

bundle agent check_red_button_status()
{
  classes:
    !policy_server::
      "should_not_continue" expression => fileexists("${sys.workdir}/inputs/stopFile");

  methods:
    !should_not_continue::
      "any" usebundle => rudder_common_report("Common", "result_success", "&TRACKINGKEY&", "Red Button", "None", "Red Button is not in effect, continuing as normal...");

}


###################################################
# Check that CFengine services are up
###################################################
bundle agent check_cf_processes
{
  vars:

      # process_term defines how many maximum instances of this
      # binary should be running before attempting to SIGTERM them.
      # process_kill is the same for SIGKILL.
    !windows::
      # On windows, cf-execd is a service, and there can be only one instance of it running (by design)
      "process_term[execd]" string => "2";
      "process_kill[execd]" string => "5";

    any::
      "process_term[agent]" string => "5";
      "process_kill[agent]" string => "8";

      "binaries"            slist  => getindices("process_term");

  classes:
    any::
      "pass3" expression => "pass2";
      "pass2" expression => "pass1";
      "pass1" expression => "any";

  methods:
    pass3::

      "any" usebundle => rudder_common_report("Common", "result_success", "&TRACKINGKEY&", "Process checking", "None", "There is an acceptable number of CFEngine processes running on the machine"),
      # Here, I can not use the binaries variable as CFEngine will iterate and output two reports, breaking the reporting.
        ifvarclass => "!agent_has_gone_wild.!agent_has_gone_really_wild.!execd_has_gone_wild.!execd_has_gone_really_wild";

      "any" usebundle => rudder_common_report("Common", "result_repaired", "&TRACKINGKEY&", "Process checking", "None", "Warning, more than ${process_term[${binaries}]} cf-${binaries} processes were detected. They have been sent a graceful termination signal."),
        ifvarclass => "${binaries}_has_gone_wild.!${binaries}_has_gone_really_wild";

      "any" usebundle => rudder_common_report("Common", "result_error", "&TRACKINGKEY&", "Process checking", "None", "ALERT: more than ${process_term[${binaries}]} cf-${binaries} processes were detected. Killing processes that do not respect graceful termination signals."),
        ifvarclass => "${binaries}_has_gone_really_wild";

  processes:
    windows::
      # Using the path on windows fails, as process are not reported the same way
      # And unfortunately, the cf-serverd is not a service.
      "cf-serverd"  restart_class => "start_server";

    !windows::

      "${sys.workdir}/bin/cf-serverd"  restart_class => "start_server";
      "${sys.workdir}/bin/cf-execd"    restart_class => "start_executor";

      # If there are more than 2 cf-execd's, it means cf-execd is starting to
      # go crazy, so we ask politely to these processes to shut down.

      "${sys.workdir}/bin/cf-${binaries}"
        process_count => check_range("${binaries}", "0","${process_term[${binaries}]}"),
        signals       => { "term" },
        classes       => if_repaired("${binaries}_has_gone_wild"),
        comment       => "Checking if cf-${binaries} has gone wild";

      # If there are too much cf-execd's/cf-agents running, it means that they are really
      # going crazy. Let's be a bit less polite and more violent about killing them.
      #
      # These two promises overlap, because when you go past the 2/5-limit treshold,
      # you still leave a chance for them to die with SIGTERM before the SIGKILL.
      #
      # Reason: The backend databases that stores the classes and some runtime
      # parameters do really not appreciate beeing killed violently and may prevent
      # the agent from operating properly.

      "${sys.workdir}/bin/cf-${binaries}"
        process_count => check_range("${binaries}", "0","${process_kill[${binaries}]}"),
        signals       => { "kill" },
        classes       => if_repaired("${binaries}_has_gone_really_wild"),
        comment       => "Checking if cf-${binaries} has gone really wild";

&if(NOVA)&
    windows::

      # Windows does only implement SIGTERM. Using SIGKILL makes no sense on it.

      "cf-${binaries}"
        process_count => check_range("${binaries}", "0","${process_term[${binaries}]}"),
        signals       => { "term" },
        classes       => if_repaired("${binaries}_has_gone_wild"),
        comment       => "Checking if cf-${binaries} has gone wild";

  services:
    # By design, there can be only one cf-execd service running on Windows
    windows::
      "CfengineNovaExec"
        service_policy => "start",
        service_method => u_bootstart,
        comment => "Start the executor windows service now and at boot time";

&endif&

  commands:

    start_server::
      "${sys.cf_serverd}"
        action  => u_ifwin_bg,
        classes => outcome("server");

    start_executor::
      "${sys.cf_execd}"
        action  => u_ifwin_bg,
        classes => outcome("executor");

}

#######################################################
# UUID file enforcing

bundle agent check_uuid
{
  files:
      "${g.uuid_file}"
        create        => "true",
        edit_line     => enforce_content("${g.uuid}"),
        edit_defaults => noempty_backup,
        perms         => m("644"),
        comment       => "Setting the uuid variable in a machine";
}


bundle agent configure_rudder_reporting_system {
  methods:
    !reports_disabled::
      "configure_syslog"    usebundle => check_log_system;
      "configure_reporting" usebundle => check_rsyslog_version;
    reports_disabled::
      "remove_reporting"    usebundle => remove_rudder_syslog_configuration;
}

#######################################################
# Check the log system, and configure it accordingly
# This only works with UNIX flavoured systems
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

    any::

      "syslog_conf_comment"   string => "# Autogenerated by rudder, do not edit${const.n}";
      "syslog_ng_conf_prefix" string => "filter f_local_rudder{facility(local6) and program(\"rudder.*\");};destination rudder_loghost {${syslogng_rule_prefix}(\"";
      "syslog_ng_conf_suffix" string => "\" port (&SYSLOGPORT&));};log {source(${syslog_ng_source});filter(f_local_rudder);destination(rudder_loghost);";
      "syslog_ng_conf_final"  string => "flags(final);};";

      "syslog_ng_conf"        string => concat("${syslog_conf_comment}", "${syslog_ng_conf_prefix}", "${server_info.cfserved}", "${syslog_ng_conf_suffix}", "${syslog_ng_conf_final}");
      "syslog_ng_conf_regex"  string => concat("filter\ f\_local\_rudder\{facility\(local6\)\ and\ program\(\"rudder\.\*\"\)\;\}\;destination\ loghost\ \{(tcp|udp)\(\"", "[^\"]+", escape("${syslog_ng_conf_suffix}"), ".*");

      "rsyslog_conffile"      string => "/etc/rsyslog.conf";
      "syslogng_conffile"     string => "/etc/syslog-ng/syslog-ng.conf";
      "syslog_conffile"       string => "/etc/syslog.conf";

      "rsyslog_rudder_conffile" string => "/etc/rsyslog.d/rudder-agent.conf";

  classes:

    !android::
      "rsyslogd" expression => fileexists("${rsyslog_conffile}");
      "syslogng" expression => fileexists("${syslogng_conffile}");
      "syslogd"  expression => fileexists("${syslog_conffile}");

    any::
      "use_udp" expression => strcmp("&RUDDER_SYSLOG_PROTOCOL&", "UDP");
      "use_tcp" expression => strcmp("&RUDDER_SYSLOG_PROTOCOL&", "TCP");

      "pass3" expression => "pass2";
      "pass2" expression => "pass1";
      "pass1" expression => "any";


  files:

    !windows.rsyslogd::
      "${rsyslog_conffile}"
        edit_line => append_if_no_lines("$IncludeConfig /etc/rsyslog.d/*.conf"),
        edit_defaults => noempty_backup,
        comment => "Add the rsyslog.conf.d include if not already present",
        classes => kept_if_else("rsyslog_kept", "rsyslog_repaired" , "rsyslog_failed");

    !windows.rsyslogd.!policy_server::
      "${rsyslog_rudder_conffile}"
        edit_line => append_if_no_lines("#Rudder log system${const.n}if $syslogfacility-text == 'local6' and $programname startswith 'rudder' then ${rsyslog_rule_prefix}${server_info.cfserved}:&SYSLOGPORT&${const.n}if $syslogfacility-text == 'local6' and $programname startswith 'rudder' then ~"),
        create => "true",
        edit_defaults => empty_backup,
        classes => kept_if_else("rsyslog_kept", "rsyslog_repaired" , "rsyslog_failed");

    SuSE.rsyslogd.policy_server::
      # For SuSE, ensure that SYSLOG_DAEMON is set to 'rsyslogd' even if another syslog has been installed before
      "/etc/sysconfig/syslog"
        edit_line => ensure_rsyslogd_on_suse,
        edit_defaults => noempty_backup,
        classes   => kept_if_else("rsyslog_kept", "rsyslog_repaired" , "rsyslog_failed");

    !windows.syslogng.!policy_server::
      "${syslogng_conffile}"
        edit_line => edit_syslog_conf_file("${syslog_ng_conf}", "${syslog_ng_conf_regex}"),
        edit_defaults => noempty_backup,
        classes => kept_if_else("syslog_ng_kept", "syslog_ng_repaired" , "syslog_ng_failed");

    !windows.syslogd.!policy_server::
      "${syslog_conffile}"
        edit_line => fix_syslogd("@${server_info.cfserved}"),
        edit_defaults => noempty_backup,
        classes => kept_if_else("syslogd_kept", "syslogd_repaired" , "syslogd_failed");
      #Probably, we want to do something if it is repaired ?


  methods:

    pass2.syslogd::
      "any" usebundle => rudder_common_report("Common", "log_info", "&TRACKINGKEY&", "Log system for reports", "None", "Detected running syslog as syslogd");

    pass2.syslogng::
      "any" usebundle => rudder_common_report("Common", "log_info", "&TRACKINGKEY&", "Log system for reports", "None", "Detected running syslog as syslog-ng");

    pass2.rsyslogd::
      "any" usebundle => rudder_common_report("Common", "log_info", "&TRACKINGKEY&", "Log system for reports", "None", "Detected running syslog as rsyslog");

    pass3.(syslogd_failed|syslog_ng_failed|rsyslog_failed)::
      "any" usebundle => rudder_common_report("Common", "result_error", "&TRACKINGKEY&", "Log system for reports", "None", "Logging system could not be configured for report centralization");

    pass3.(syslogd_repaired|syslog_ng_repaired|rsyslog_repaired)::
      "any" usebundle => rudder_common_report("Common", "result_repaired", "&TRACKINGKEY&", "Log system for reports", "None", "Configured logging system for report centralization");

    pass3.((syslogd.syslogd_kept.!syslogd_failed.!syslogd_repaired)|(syslogng.syslog_ng_kept.!syslog_ng_failed.!syslog_ng_repaired)|(rsyslogd.rsyslog_kept.!rsyslog_failed.!rsyslog_repaired))::
      "any" usebundle => rudder_common_report("Common", "result_success", "&TRACKINGKEY&", "Log system for reports", "None", "Logging system for report centralization is already correctly configured");

    pass3.android::
      "any" usebundle => rudder_common_report("Common", "result_success", "&TRACKINGKEY&", "Log system for reports", "None", "This is an android machine: Logging system configuration skipped.");

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
      "check_rsyslog_version_present" expression => fileexists("${g.rudder_tools}/check-rsyslog-version");
      "rsyslogd" expression => fileexists("${check_log_system.rsyslog_conffile}");

    any::
      "pass3" expression => "pass2";
      "pass2" expression => "pass1";
      "pass1" expression => "any";

  files:
    rsyslogd.rsyslog_version_ok::
      "/etc/rsyslog.d/remove_limit.conf"
        edit_line => append_if_no_lines("$SystemLogRateLimitInterval 0"),
        edit_defaults => noempty_backup,
        create    => "true",
        comment   => "Add a config line in the rsyslog.conf file to prevent from dropping rudder messages",
        classes   => rudder_common_classes("rsyslog_limit");

  methods:

    pass3.(rsyslogd.!check_rsyslog_version_present)::
      "any" usebundle => rudder_common_report("Common", "result_error", "&TRACKINGKEY&", "Log system for reports", "None", "The file  ${g.rudder_tools}/check-rsyslog-version is missing");

    pass3.rsyslog_limit_error::
      "any" usebundle => rudder_common_report("Common", "result_error", "&TRACKINGKEY&", "Log system for reports", "None", "Could not remove limitation of message in rsyslog");

    pass3.rsyslog_limit_repaired::
      "any" usebundle => rudder_common_report("Common", "log_repaired", "&TRACKINGKEY&", "Log system for reports", "None", "Updated the rsyslog configuration to remove limitation of messages");

  commands:
    rsyslogd.check_rsyslog_version_present::
      "${g.rudder_tools}/check-rsyslog-version"
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
        "local6.notice\s+@${server_info.cfserved}",
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
      "reports"              usebundle  => rudder_common_reports_generic("Common", "remove_rudder_syslog_configuration_result", "&TRACKINGKEY&", "Log system for reports", "None", "Rudder syslog configuration removed");

}

###################################################################
# Trash every output report and modified files older than the TTL #
###################################################################

bundle agent garbage_collection
{

  files:

      "${sys.workdir}/outputs"

        delete => tidy,
        file_select => days_old("&CFENGINE_OUTPUTS_TTL&"),
        depth_search => recurse("inf");

      "${g.rudder_var}/modified-files"

        delete => tidy,
        file_select => days_old("&MODIFIED_FILES_TTL&"),
        depth_search => recurse("inf");

}

#######################################################
# Copy the CFengine binaries from the /opt repository
# to the CFengine working directory

bundle agent check_binaries_freshness
{

  vars:

    community_edition::

      "components" slist => { "cf-agent", "cf-serverd", "cf-execd", "cf-monitord", "cf-promises", "cf-runagent", "cf-key", "rpmvercmp" };

  files:

    !android.!nova_edition::
      "${sys.workdir}/bin/${components}"
        perms => u_p("755"),
        copy_from => cp("${g.rudder_bin}/${components}", "localhost"),
        classes => kept_if_else("binaries_fresh", "binaries_rotten", "binaries_missing"),
        action => immediate,
        comment => "Copying the CFengine binaries from ${g.rudder_sbin}/sbin to ${sys.workdir}/bin";

  methods:

    binaries_fresh.!binaries_rotten.!binaries_missing::
      "any" usebundle => rudder_common_report("Common", "result_success", "&TRACKINGKEY&", "Binaries update", "None", "The CFengine binaries in ${sys.workdir}/bin are up to date");

    binaries_rotten.!binaries_missing::
      "any" usebundle => rudder_common_report("Common", "result_repaired", "&TRACKINGKEY&", "Binaries update", "None", "The CFengine binaries have been updated in ${sys.workdir}/bin");

    binaries_missing::
      "any" usebundle => rudder_common_report("Common", "result_error", "&TRACKINGKEY&", "Binaries update", "None", "An error occurred while updating the CFengine binaries in ${sys.workdir}/bin");

    android::
      "any" usebundle => rudder_common_report("Common", "result_success", "&TRACKINGKEY&", "Binaries update", "None", "This is an android machine: no CFEngine binaries update needed");

    nova_edition::
      "any" usebundle => rudder_common_report("Common", "result_na", "&TRACKINGKEY&", "Binaries update", "None", "This is an CFEngine enterprise system: binaries update are handled differently");

}

#######################################################

body agent control
{
    # if default runtime is 5 mins we need this for long jobs
        ifelapsed => "1";

    #define here some environment variables
        environment => { "DEBIAN_FRONTEND=noninteractive" };

        abortclasses => { "should_not_continue" };

        agentfacility => "LOG_LOCAL6";

        skipidentify => "&SKIPIDENTIFY&";

  # Repository where to put the copy of modified files
        default_repository => "${g.rudder_var}/modified-files";

}

#######################################################

body executor control
{
        splaytime => "&AGENT_RUN_SPLAYTIME&";
        schedule         => { &AGENT_RUN_SCHEDULE& };
        executorfacility => "LOG_DAEMON";

   windows::
        # CFEngine best practice is to use full paths on Windows
        exec_command => "${sys.cf_agent} -f \"${sys.workdir}\inputs\failsafe.cf\" \& ${sys.cf_agent}";

   !windows::
        exec_command => "${sys.cf_agent} -f failsafe.cf \&\& ${sys.cf_agent}";

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
      "\$\(syslog_ng_conf\)";
      "\$\{syslog_ng_conf\}";

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
