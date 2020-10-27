function Condition-Dyn {
  [CmdletBinding()]
  param (
      [parameter(Mandatory=$true)]
      [string]$reportId,
      [parameter(Mandatory=$true)]
      [string]$techniqueName,
      [switch]$auditOnly
  )

  $local_classes = New-ClassContext
  $resources_dir = $PSScriptRoot + "\resources"

  $class = "any.(skip_item_${report_data.canonified_directive_id}_false)"
  if (Evaluate-Class $class $local_classes $system_classes) {
    $local_classes = Merge-ClassContext $local_classes $(Command-Execution -Command "pwd" -componentName "Command execution" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly).get_item("classes")
  } else {
    _rudder_common_report_na -componentName "Command execution" -componentKey "pwd" -message "Not applicable" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly
  }

}
