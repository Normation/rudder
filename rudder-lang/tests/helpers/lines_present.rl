# Generated from json technique
@format = 0
@name = "CIS redhat7 - Ensure lines present in file"
@description = ""
@category = "ncf_technique"
@version = 0
@parameters = [
@  { "name" = "lines", "id" = "7e9a3aa5-a697-4f88-8d3d-a02e27e1b5f8", "description" = "" },
@  { "name" = "file", "id" = "9e79f7f8-3ca6-4376-a510-11e00e134c91", "description" = "" },
@  { "name" = "extra_condition", "id" = "1cf9a017-7fd9-49b8-bdaa-0c22365fa988", "description" = "" }
@]

resource CIS_redhat7___Ensure_lines_present_in_file(lines, file, extra_condition)

CIS_redhat7___Ensure_lines_present_in_file state technique() {
  let p0 = "skip_item_${report_data.canonified_directive_id}"
  let p1 = "node.properties[skip][${report_data.directive_id}]"
  @component = "condition_from_variable_existence"
  condition(p0).from_variable_existence(p1) as condition_from_variable_existence_skip_item___report_data_canonified_directive_id_

  let p0 = "${file}"
  let p1 = "${lines}"
  @component = "File content"
  if (skip_item_${report_data.canonified_directive_id}_false.${extra_condition}) => file(p0).content(p1, "false") as file_lines_present___file_
}
