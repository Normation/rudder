# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2021 Normation SAS

# This file contains all the groups where the node belongs to. The groups are available on two ways:
# 1/ two classes are added for each groups:
#    - group_UUID with UUID replaced by the group unique identifier
#    - group_NAME with NAME replaced by the transliteration of the group name to lower-case ascii, and the
#      canonification of the result
# 2/ an array "by_uuid" of UUID -> ORIG_NAME, available as a global variable (for example for processing
#    all node groups to use in templates)
#
#  For example, if node belongs to group: (id: 64f85ba8-39c7-418a-a099-24c2c2909dfd ; name: "Serveurs pré-prod")
#  we will have the following classes:
#  - group_64f85ba8_39c7_418a_a099_24c2c2909dfd
#  - group_serveurs_pre_prod
#  and vars:
#  - by_uuid["64f85ba8-39c7-418a-a099-24c2c2909dfd"] = "Serveurs pré-prod"
# More over, each value of the array will have an attribute "meta": { "inventory", "attribute_name=rudder_groups" }
#
bundle common rudder_groups {

  vars:
      &RUDDER_NODE_GROUPS_VARS&

  classes:
      &RUDDER_NODE_GROUPS_CLASSES&

}
