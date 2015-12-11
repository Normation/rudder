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
