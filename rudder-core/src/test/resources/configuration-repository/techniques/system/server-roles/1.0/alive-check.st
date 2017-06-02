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

bundle agent generic_alive_check(app)
{

  vars:

      "site_to_check" string => "http://localhost:8080/${app}/api/status";
      "sitename" string => canonify("${site_to_check}");

      "cleanup_failed_classes" slist => { "site_down_once_${sitename}", "site_alivecheck_restart_jetty_${sitename}" };

      "failed_result_class" string => "site_down_once_${sitename}",
        ifvarclass => "!site_down_once_${sitename}.!first_iteration_passed";

      "site_failure_persist_time" string => "10",
        ifvarclass => "!site_down_once_${sitename}.!first_iteration_passed";

      "failed_result_class" string => "site_alivecheck_restart_jetty_${sitename}",
        ifvarclass => "site_down_once_${sitename}.!first_iteration_passed";

      "site_failure_persist_time" string => "0",
        ifvarclass => "site_down_once_${sitename}.!first_iteration_passed";

  classes:

      "first_iteration_passed" expression => "any";

    any::
      "pass3" expression => "pass2";
      "pass2" expression => "pass1";
      "pass1" expression => "any";

  methods:

    root_server::

      "any" usebundle => service_restart("rudder-jetty"),
        ifvarclass => "site_alivecheck_restart_jetty_${sitename}",
        classes => set_persist_classes("site_alivecheck_jetty_restarted_${sitename}", "site_down_once_${sitename}" ,"0");

    pass3.root_server::

      "any" usebundle => rudder_common_report("server-roles", "result_success", "&TRACKINGKEY&", "Check ${app} status", "None", "The ${site_to_check} web interface is running"),
        ifvarclass => "site_ok.!site_down_once_${sitename}";

      "any" usebundle => rudder_common_report("server-roles", "result_error", "&TRACKINGKEY&", "Check ${app} status", "None", "This is the first time the ${site_to_check} web interface failed to respond. Deferring the restart."),
        ifvarclass => "site_down_once_${sitename}.!site_alivecheck_restart_jetty_${sitename}";

      "any" usebundle => rudder_common_report("server-roles", "result_error", "&TRACKINGKEY&", "Check ${app} status", "None", "The ${site_to_check} web interface failed to respond for the second time. Restarting jetty NOW!"),
        ifvarclass => "site_alivecheck_restart_jetty_${sitename}";

    pass3.(policy_server.!root_server)::

      "any" usebundle => rudder_common_report("server-roles", "result_success", "&TRACKINGKEY&", "Check ${app} status", "None", "The ${site_to_check} web interface check do not need to be done on relay servers. Skipping.");

  commands:

    root_server::

      "/usr/bin/curl --proxy '' --max-time 240 -s ${site_to_check} |/bin/grep -q OK"
        contain => in_shell_silent,
        classes => set_persist_classes_alivecheck("site_ok", "${failed_result_class}", "@{generic_alive_check.cleanup_failed_classes}", "${site_failure_persist_time}"),
        comment => "Checking if ${site_to_check} is alive";

}

body classes set_persist_classes_alivecheck(repaired, failed, cancelifok, length)
{
        promise_kept     => { "${repaired}" };
        promise_repaired => { "${repaired}" };
        repair_failed    => { "${failed}" };
        repair_denied    => { "${failed}" };
        repair_timeout   => { "${failed}" };

        cancel_kept => {"@{cancelifok}"};
        cancel_repaired => {"@{cancelifok}"};
        cancel_notkept => {"${repaired}"};

        persist_time => "${length}";
}
