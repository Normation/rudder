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

bundle agent setup_cronjob
{
  vars:

  files:

    linux::

      # Temporary cron file added by rudder-agent postinst to prevent from UUID removal.
      # When this promise will be generated, this cron will be useless then removed.
      # (see http://www.rudder-project.org/redmine/issues/3925 and http://www.rudder-project.org/redmine/issues/3930).
      "/etc/cron.d/rudder-agent-uuid"
        delete => tidy;

    community_edition.!aix::

      "/etc/cron.d/rudder-agent"
        create        => "true",
        perms         => mog("644", "root", "0"),
        edit_defaults => empty_backup,
        edit_line     => expand_template("${sys.workdir}/inputs/common/cron/rudder_agent_community_cron");
&if(NOVA)&

    nova_edition.!aix.!windows::

      "/etc/cron.d/rudder-agent-nova"
        create        => "true",
        perms         => mog("644", "root", "0"),
        edit_defaults => empty_backup,
        edit_line     => expand_template("${sys.workdir}/inputs/common/cron/rudder_agent_nova_cron");
&endif&

    aix::

      # Cleanup the crontab
      "/var/spool/cron/crontabs/root"
        edit_defaults => noempty_backup,
        edit_line => delete_lines_matching("0,5,10,15,20,25,30,35,40,45,50,55 \* \* \* \* if \[ -x /opt/rudder/bin/check-rudder-agent \]; then /opt/rudder/bin/check-rudder-agent( >/dev/null)?; fi");

      # Add Rudder entry
      "/var/spool/cron/crontabs/root"
        create        => "true",
        perms         => mog("600", "root", "cron"),
        edit_line     => insert_lines("0,5,10,15,20,25,30,35,40,45,50,55 * * * * /usr/bin/test -x /opt/rudder/bin/check-rudder-agent \&\& /opt/rudder/bin/check-rudder-agent >/dev/null"),
        classes       => rudder_common_classes("rudder_aix_crontab_insertion"),
        comment       => "Insert an AIX-compatible user crontab to run /opt/rudder/bin/check-rudder-agent";

}

#######################################################
# Check if the cron daemon is running
# This only works with unix flavoured systems too

bundle agent check_cron_daemon
{
  vars:

    redhat|fedora::
      "service_name" string => "crond";
    !(redhat|fedora)::
      "service_name" string => "cron";

  classes:

    any::
      "pass3" expression => "pass2";
      "pass2" expression => "pass1";
      "pass1" expression => "any";

  methods:

    pass2.restart_crond::
      "restart_cron" usebundle => service_restart("${service_name}");

    # Fake success result, since we don't actually call service_restart unless it's needed
    pass2.!(android|windows|aix).!restart_crond::
      "any" usebundle => _classes_success("service_restart_${service_name}");

    pass3.!(android|windows|aix)::
      "any" usebundle => rudder_common_reports_generic("Common", "service_restart_${service_name}", "&TRACKINGKEY&", "CRON Daemon", "None", "Cron daemon status");

    (android|windows|aix)::
      "any" usebundle => rudder_common_report("Common", "result_na", "&TRACKINGKEY&", "CRON Daemon", "None", "This is a system without CRON: CRON verifications skipped !");

  processes:

    !android.!windows.!aix::
      "cron"
        process_select => cron_bin,
        restart_class => "restart_crond";

}
