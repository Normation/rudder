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

# Read the rudder-server-roles.conf, and extract for each roles which server has it
# It defines two arrays:
# roles[role_name]: contains the prefered server with this role
# roles[role_name][all]: contains the list of servers with this role
bundle common rudder_servers_by_role
{

  vars:
      "dim_array" int => readstringarray("roles", "${sys.workdir}/inputs/rudder-server-roles.conf", "#[^\n]*", ":", 15, 4000);

      "key" slist => getindices("roles");

      # roles[role_name][all] contains ALL the servers with the role hostname
      "roles[${key}][all]" slist => splitstring("${roles[${key}][1]}", ",", "100");

      # roles[role_name][shuffled] contains ALL the servers with the role hostname, in a random (but deterministic)
      # order for the node
      # This step is mandatory, as we can't make nth of shuffle in one step (it is considered a syntax error)
      "roles[${key}][shuffled]" slist => shuffle("roles[${key}][all]", "${sys.ipv4}${sys.fqhost}");

      # role[role_name] contains only one server with this role, defined deterministically
      # We shuffle the list, based on the ip and hostname seed, and take the first element of the
      # shuffled list
      "roles[${key}]" string => nth("roles[${key}][shuffled]", 0);

  classes:
      "role_${key}_server_defined" expression => "any";

}
