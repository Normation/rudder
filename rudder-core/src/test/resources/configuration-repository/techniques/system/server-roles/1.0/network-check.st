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

bundle agent root_networks_check
{

  vars:
      "policy_server_ip" string => host2ip("${def.policy_server}");
      "policy_server_acl" slist => { "127.0.0.0/8", "::1",  "${policy_server_ip}" };

      "nodes_generate_22"  string => join("${const.n}Allow from ","def.acl");
      "nodes_generate_24"  string => join("${const.n}Require ip ","def.acl");
      "policy_server_generate_22"  string => join("${const.n}Allow from ","policy_server_acl");

      "network_file[nodes_22]" string => "${g.rudder_base}/etc/rudder-networks.conf";
      "network_acl[nodes_22]"  string => "Allow from ${nodes_generate_22}";

      "network_file[nodes_24]" string => "${g.rudder_base}/etc/rudder-networks-24.conf";
      "network_acl[nodes_24]"  string => "Require ip ${nodes_generate_24}";

      "network_file[policy_server_22]" string => "${g.rudder_base}/etc/rudder-networks-policy-server.conf";
      "network_acl[policy_server_22]"  string => "Allow from ${policy_server_generate_22}";

      "network_file[policy_server_24]" string => "${g.rudder_base}/etc/rudder-networks-policy-server-24.conf";
      "network_acl[policy_server_24]"  string => "Require local${const.n}Require ip ${policy_server_ip}";

      "index"             slist => getindices("network_file");

    redhat::
      "apache_service" string => "httpd";

    !redhat::
      "apache_service" string => "apache2";

  classes:
    any::
      "pass3" expression => "pass2";
      "pass2" expression => "pass1";
      "pass1" expression => "any";

  files:

      "${network_file[${index}]}"
        create        => "true",
        perms         => mog("600", "root", "0"),
        edit_defaults => empty_backup,
        edit_line     => insert_lines("${network_acl[${index}]}"),
        classes       => kept_if_else("rudder_networks_ok", "rudder_networks_repaired","rudder_networks_failed"),
        comment       => "Writing rudder apache configuration";

  methods:

    pass3.rudder_networks_ok.!rudder_networks_repaired.!rudder_networks_failed::
      "any" usebundle => rudder_common_report("server-roles", "result_success", "&TRACKINGKEY&", "Check allowed networks configuration", "None", "Allowed networks configuration is correct");

    pass3.rudder_networks_repaired.!rudder_networks_failed::
      "any" usebundle => rudder_common_report("server-roles", "result_repaired", "&TRACKINGKEY&", "Check allowed networks configuration", "None", "Allowed networks configuration has been repaired");

    pass3.rudder_networks_failed::
      "any" usebundle => rudder_common_report("server-roles", "result_error", "&TRACKINGKEY&", "Check allowed networks configuration", "None", "Allowed networks configuration could not be edited");

    pass3.apache_reloaded.!apache_reload_failed::
      "any" usebundle => rudder_common_report("server-roles", "log_info", "&TRACKINGKEY&", "Check allowed networks configuration", "None", "Apache has been reloaded successfully");

    pass3.apache_reload_failed::
      "any" usebundle => rudder_common_report("server-roles", "result_error", "&TRACKINGKEY&", "Check allowed networks configuration", "None", "Apache failed to reload");

  commands:

    rudder_networks_repaired::

      "${paths.path[service]}"
        args => "${apache_service} reload",
        classes => if_else("apache_reloaded", "apache_reload_failed");

}
