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

bundle common generic_variable_definition
{

  vars:

&GENERIC_VARIABLE_NAME, GENERIC_VARIABLE_CONTENT:{name, content |"&name&" string => "&content&",
        policy => "overridable";
}&

}

bundle agent generic_variable_def_report
{
  vars:
                &TRACKINGKEY:{directiveId |"generic_name_uuid[&i&]" string => "&directiveId&";
}&
                &GENERIC_VARIABLE_NAME:{name |"generic_variable_name[&i&]" string => "&name&";
}&

      "generic_variable_name_index" slist => getindices("generic_name_uuid");

  classes:
    any::
      "pass3" expression => "pass2";
      "pass2" expression => "pass1";
      "pass1" expression => "any";

  methods:

    pass3.((linux|!linux))::

      "any" usebundle => rudder_common_report("genericVariableDefinition", "result_success", "${generic_name_uuid[${generic_variable_name_index}]}", "Variable definition", "${generic_variable_name[${generic_variable_name_index}]}", "A generic variable definition is set for variable ${generic_variable_name[${generic_variable_name_index}]}");

}

