function Condition-Alias {
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

  $class = "any.(SLES12)"
  if (Evaluate-Class $class $local_classes $system_classes) {
    $local_classes = Merge-ClassContext $local_classes $(Command-Execution -Command "pwd" -componentName "Command execution" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly).get_item("classes")
  }
  else {
    _rudder_common_report_na -ComponentName "Command execution" -ComponentKey "pwd" -Message "Not applicable" -ReportId $ReportId -TechniqueName $TechniqueName -AuditOnly:$AuditOnly
  }
  $class = "any.(ubuntu_10_04)"
  if (Evaluate-Class $class $local_classes $system_classes) {
    $local_classes = Merge-ClassContext $local_classes $(Command-Execution -Command "ls" -componentName "Command execution" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly).get_item("classes")
  }
  else {
    _rudder_common_report_na -ComponentName "Command execution" -ComponentKey "ls" -Message "Not applicable" -ReportId $ReportId -TechniqueName $TechniqueName -AuditOnly:$AuditOnly
  }
}
