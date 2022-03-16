function Param-Mult {
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

  $class = "any.(linux)"
  if (Evaluate-Class $class $local_classes $system_classes) {
    $local_classes = Merge-ClassContext $local_classes $(File-Absent -Path "target" -componentName "File absent" -reportId $reportId -techniqueName $techniqueName -Report:$true -AuditOnly:$auditOnly).get_item("classes")
  } else {
    _rudder_common_report_na -componentName "File absent" -componentKey "target" -message "Not applicable" -reportId $reportId -techniqueName $techniqueName -Report:$true -AuditOnly:$auditOnly
  }

  _rudder_common_report_na -componentName "Package absent" -componentKey "openvpn" -message "Not applicable" -reportId $reportId -techniqueName $techniqueName -Report:$true -AuditOnly:$auditOnly

}
