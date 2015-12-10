#####################################################################################
# Copyright 2014 Normation SAS
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

bundle agent generic_process_check_process(binary, initscript, name, force_restart, check_on_relay_server) {

  vars:

      "canoname"     string => canonify("${binary}");
      "c_initscript" string => canonify("${initscript}");

  classes:

      "forced_trigger_${canoname}" expression => strcmp("${force_restart}", "true");

      # Raise this class if we are handling a process that should be checked even on
      # relay servers.
      "process_exception_on_relay" expression => strcmp("${check_on_relay_server}", "true");

    any::

      "pass3" expression => "pass2";
      "pass2" expression => "pass1";
      "pass1" expression => "any";

  methods:

    pass2.(root_server|process_exception_on_relay)::

      "start_process" usebundle => service_ensure_running_path("${initscript}", "${binary}"),
                     ifvarclass => "!forced_trigger_${canoname}";

      "start_process" usebundle => service_restart("${initscript}"),
                     ifvarclass => "forced_trigger_${canoname}";

    pass3.(root_server|process_exception_on_relay)::

      "any" usebundle => rudder_common_report("server-roles", "result_success", "&TRACKINGKEY&", "Check ${name} process", "None", "The ${name} process is already running"),
        ifvarclass => "!process_restart_${canoname}.!forced_trigger_${canoname}";

      "any" usebundle => rudder_common_report("server-roles", "result_repaired", "&TRACKINGKEY&", "Check ${name} process", "None", "The ${name} process was not running and has been restarted"),
        ifvarclass => "process_restart_${canoname}.service_restart_${c_initscript}_repaired.!forced_trigger_${canoname}";

      "any" usebundle => rudder_common_report("server-roles", "result_repaired", "&TRACKINGKEY&", "Check ${name} process", "None", "The ${name} process has been restarted"),
        ifvarclass => "service_restart_${c_initscript}_repaired.forced_trigger_${canoname}";

      "any" usebundle => rudder_common_report("server-roles", "result_error", "&TRACKINGKEY&", "Check ${name} process", "None", "The ${name} process couldn't be restarted"),
        ifvarclass => "service_restart_${c_initscript}_not_ok";

    pass3.(policy_server.!root_server.!process_exception_on_relay)::

      "any" usebundle => rudder_common_report("server-roles", "result_success", "&TRACKINGKEY&", "Check ${name} process", "None", "The ${name} process do not need to be checked on relay servers. Skipping...");

  processes:

    root_server|process_exception_on_relay::

    # check the service status
      "${binary}"
        comment => "Check the process status",
        restart_class => "process_restart_${canoname}",
        classes => kept_if_else("service_running_${canoname}", "service_anomaly_${canoname}", "service_error_${canoname}");

}

bundle agent generic_process_check_bootstart(binary, initscript, name) {

  vars:

      "canoname"     string => canonify("${binary}");
      "c_initscript" string => canonify("${initscript}");

  classes:
    any::
      "pass3" expression => "pass2";
      "pass2" expression => "pass1";
      "pass1" expression => "any";

  methods:

    pass2::

      "bootstart_service" usebundle => service_ensure_started_at_boot("${initscript}");

    pass3::

      "any" usebundle => rudder_common_reports_generic("server-roles", "service_ensure_started_at_boot_${c_initscript}", "&TRACKINGKEY&", "Check ${name} boot script", "None", "Check ${name} boot starting parameters");

}
