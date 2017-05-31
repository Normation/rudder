#####################################################################################
# Copyright 2017 Normation SAS
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

# This file is the main entry points for the bundle sequence for
# Rudder system directives. It is actually a list of method calls.

function Call_System_Directives(
  [string] $class = $null
  ) {
  $system_techniques_path = "$rudder_path\system-techniques"

  Write-Verbose ("Loading all system techniques scripts in $system_techniques_path")

  # Get list of all files in ncf folders, and load them
  $system_techniques_files = Get-ChildItem -recurse $system_techniques_path -Filter *.ps1

  $system_techniques_files | ForEach {
    . $_.FullName
  }

  Write-Verbose ("Loaded all system technique ps1 scripts")

  configure_logger -ReportId dsc-agent@@dsc-agent@@42
}
