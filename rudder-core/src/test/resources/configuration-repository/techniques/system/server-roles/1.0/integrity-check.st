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

bundle agent root_integrity_check
{

  classes:
    any::
      "pass3" expression => "pass2";
      "pass2" expression => "pass1";
      "pass1" expression => "any";

  files:

    root_server::

      "${g.rudder_configuration_repository}"
        create => "true",
        action => WarnOnly,
        classes => if_else("rudder_integrity_ok", "rudder_integrity_failed");

      "${g.rudder_configuration_repository}/.git/index.lock"
        delete       => tidy,
        file_select  => rudder_common_minutes_old("5"),
        classes      => rudder_common_classes("rudder_git_lock"),
        comment      => "Delete the git locking file in the configuration-repository if older than 5 minutes";

  methods:

    pass3.root_server.rudder_integrity_ok.!rudder_integrity_failed::
      "any" usebundle => rudder_common_report("server-roles", "result_success", "&TRACKINGKEY&", "Check configuration-repository folder", "None", "The ${g.rudder_configuration_repository} directory is present");

    pass3.root_server.(!rudder_integrity_ok|rudder_integrity_failed)::
      "any" usebundle => rudder_common_report("server-roles", "result_error", "&TRACKINGKEY&", "Check configuration-repository folder", "None", "EMERGENCY: THE ${g.rudder_configuration_repository} DIRECTORY IS *ABSENT*. THIS ORCHESTRATOR WILL *NOT* OPERATE CORRECTLY.");

    pass3.(root_server.!rudder_git_lock_repaired.!rudder_git_lock_failed)::
      "any" usebundle => rudder_common_report("server-roles", "result_success", "&TRACKINGKEY&", "Check configuration-repository GIT lock", "None", "The ${g.rudder_configuration_repository} GIT lock file is not present or not older than 5 minutes");

    pass3.(root_server.rudder_git_lock_repaired.!rudder_git_lock_failed)::
      "any" usebundle => rudder_common_report("server-roles", "result_repaired", "&TRACKINGKEY&", "Check configuration-repository GIT lock", "None", "WARNING: THE ${g.rudder_configuration_repository} GIT LOCK FILE WAS OLDER THAN 5 MINUTES AND HAS BEEN DELETED");

    pass3.(root_server.rudder_git_lock_failed)::
      "any" usebundle => rudder_common_report("server-roles", "result_error", "&TRACKINGKEY&", "Check configuration-repository GIT lock", "None", "TheEMERGENCY: THE ${g.rudder_configuration_repository} GIT LOCK FILE IS OLDER THAN 5 MINUTES AND COULD NOT BE DELETED. THIS ORCHESTRATOR WILL *NOT* OPERATE CORRECTLY.");

    pass3.(policy_server.!root_server)::
      "any" usebundle => rudder_common_report("server-roles", "result_success", "&TRACKINGKEY&", "Check configuration-repository folder", "None", "Nothing to check about the configuration-repository on relay servers");
      "any" usebundle => rudder_common_report("server-roles", "result_success", "&TRACKINGKEY&", "Check configuration-repository GIT lock", "None", "Nothing to check about configuration-repository GIT locking on relay servers");

}
