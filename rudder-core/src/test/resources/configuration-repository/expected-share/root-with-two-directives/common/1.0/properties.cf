#####################################################################################
# Copyright 2016 Normation SAS
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

# Read node properties and put them into the node namespace
#
# Properties are available using the node.namespace container that contains the values
# Those values are read from /var/rudder/cfengine-community/properties/*.json
# All files are taken in order and overrides the previous one, the last one wins.
# 
# Each file must contain at least a 2 levels JSON content, the first level is the namespace level
# and the second level is the key level.
# The namespace name must comply with CFEngine variable names rules (ie [a-zA-Z_]+)
#
# The merge is a first level merge done at the namespace level.
# This means that:
# - a key in a namespace is fully overridden by the same key in the same namespace in a later file.
# - a key in a namespace is never overriden by the same key in a different namespace
# - a key that is overriden never retain original data even if it is a data container itself
# 

# The result key is available in the node.<namespace> data variable
# usage example: ${node.properties[my_key]}
#
# To get the original data (for debug only) there is the properties.property_<fileid> variable
# usage example: ${properties.property__var_rudder_cfengine_community_inputs_properties_properties_json[properties][my_key]}
#
bundle agent properties
{
  vars:
    # The files to read
    "properties_files" slist => findfiles("${this.promise_dirname}/../../properties.d/*.json");

    # The sorted file list
    "_sorted_files" slist => sort("properties_files", "lex");
    # cfengine iterates over lists in promises in the reverse order, so we need the inverted list
    "sorted_files" slist => reverse("_sorted_files");

    # Canonified sorted file list
    "file_id[${sorted_files}]" string => canonify("${sorted_files}");

    # Read the files, 10MB maximum
    # Read into a property_<canonified filename> because cfengine doesn't support to read data into property[<filename>]
    "property_${file_id[${sorted_files}]}" data => readjson("${sorted_files}", "10000000");

    # Get namespace names
    "namespace[${sorted_files}]" slist => getindices("property_${file_id[${sorted_files}]}");

    # Initialize all namespace variables (mergedata refuses to merge undefined variables)
    "node.${namespace[${sorted_files}]}" data => parsejson('{}');

    # First level merge within each namespace, in file name order 
    "node.${namespace[${sorted_files}]}" data => mergedata("node.${namespace[${sorted_files}]}",
                                                           "property_${file_id[${sorted_files}]}[${namespace[${sorted_files}]}]");

    # That's all, now we have a node.namespace data that contains the overriden values
}
