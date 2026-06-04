# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2021 Normation SAS

# This file is the complementary entry points for the bundle sequence for
# Rudder system directives defined in rudder-system-directives.st.
# It is used for system directives that needs to run at the end of the agent run.
# These directive's technique metadata must have the policy type "rudder.system,post-action".

body file control {

  inputs => {
    &RUDDER_SYSTEM_DIRECTIVES_INPUTS_END&
  };

}


bundle agent rudder_system_directives_end {
  # fully generated in bundle common for initial promises
  # The directive sequence will be correctly indented by the generated variable so we can support multiline variable
  methods:
&RUDDER_SYSTEM_DIRECTIVES_SEQUENCE_END&

}

