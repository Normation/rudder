# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2021 Normation SAS

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
  # The directive sequence will be correctly indented by the generated variable so we can support multiline variable
  methods:
&RUDDER_SYSTEM_DIRECTIVES_SEQUENCE&

}

