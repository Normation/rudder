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

# Define module script to read environment variables, in the module format
# Store them in the node.env context
bundle agent get_environment_variables
{
  vars:
    # These scripts output the environment variables, in the format
    # =node.env[ENV_NAME]=ENV_VALUE
    # Using the module protocole, the agent will read it, and the variable
    # will be available using the syntax ${node.env[ENV_NAME]}

    !windows::
      "script" string => "#! /bin/sh
env | sed 's/=/]=/' |sed 's/^/=node.env[/'";

    !windows.linux::
      "env_vars" slist => splitstring( execresult("(${paths.printf} '\0'; ${paths.env} -0) | ${paths.grep} -aoP '\x00([^=]+)' | ${paths.tr} -d '\000' | ${paths.tr} '\n' ',' | ${paths.sed} 's/,$//'","useshell"), "," , 2000);
      "node.env[${env_vars}]" string => getenv( "${env_vars}", 5000);

    windows::
     "script" string => "@echo off
for /F  \"tokens=1,2* delims==\" %%G IN ('SET') DO ECHO =node.env[%%G]=%%H";

  files:
    !windows.!linux::
      "${sys.workdir}/modules/env"
        create        => "true",
        perms         => mog("755", "root", "0"),
        edit_line     => insert_lines("${script}"),
        edit_defaults => empty;

    windows::
      "\"${sys.workdir}/modules/env.bat\""
        create        => "true",
        edit_line     => insert_lines("${script}"),
        edit_defaults => empty;


  commands:
    !windows.!linux::
      "${sys.workdir}/modules/env"
        module => "true";
    windows::
      "\"${sys.workdir}/modules/env.bat\""
        module => "true";

}
