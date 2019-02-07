#####################################################################################
# Copyright 2015 Normation SAS
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
# Rudder system directives. It is actually a list of method calls, and not
# a bundle sequence, which allows to add the technique or directives 
# names as promiser. 

body file control {

  inputs => {
    &RUDDER_SYSTEM_DIRECTIVES_INPUTS&
  };

}


bundle agent rudder_system_directives {
  # fully generated in bundle common va for iinitial promises
  methods:
      &RUDDER_SYSTEM_DIRECTIVES_SEQUENCE&

}

