function 6-2-Cis-Updated {
  [CmdletBinding()]
  param (
      [parameter(Mandatory=$true)]
      [string]$reportId,
      [parameter(Mandatory=$true)]
      [string]$techniqueName,
      [parameter(Mandatory=$true)]
      [string]$Module,
      [switch]$auditOnly
  )

  $local_classes = New-ClassContext
  $resources_dir = $PSScriptRoot + "\resources"

  $local_classes = Merge-ClassContext $local_classes $(Condition-From-Variable-Existence -Condition "skip_item_${report_data.canonified_directive_id}" -VariableName "node.properties[skip][${report_data.directive_id}]" -componentName "condition_from_variable_existence" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly).get_item("classes")

  _rudder_common_report_na -componentName "kernel_module_configuration" -componentKey "${module}" -message "Not applicable" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly

  _rudder_common_report_na -componentName "kernel_module_not_loaded" -componentKey "${module}" -message "Not applicable" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly

}
