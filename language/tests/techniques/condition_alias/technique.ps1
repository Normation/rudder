# generated by rudderc
# @name conditional method that includes aliases for condition
# @version 1.0

function Condition-Alias {
  [CmdletBinding()]
  param (
    [Parameter(Mandatory=$True)]
    [String]$ReportId,
    [Parameter(Mandatory=$True)]
    [String]$TechniqueName,
    [Switch]$AuditOnly
  )

  $ReportIdBase = $reportId.Substring(0,$reportId.Length-1)
  $LocalClasses = New-ClassContext
  $ResourcesDir = $PSScriptRoot + "\resources"
  $ReportId = $ReportIdBase+"8b575191-0ab0-4851-b05c-e7cde3726f05"

  $class = "any.(SLES12)"
  if (Evaluate-Class $class $local_classes $system_classes) {
    $local_classes = Merge-ClassContext $local_classes $(Command-Execution -Command "pwd" -componentName "Command execution" -reportId $reportId -techniqueName $techniqueName -Report:$true -AuditOnly:$auditOnly).get_item("classes")
  }
  else {
    _rudder_common_report_na -ComponentName "Command execution" -ComponentKey "pwd" -Message "Not applicable" -ReportId $ReportId -TechniqueName $TechniqueName -Report:$true -AuditOnly:$AuditOnly
  }
  $ReportId = $ReportIdBase+"3a8f5707-a86a-4599-8c68-3dbbfd6f70a1"
  $class = "any.(ubuntu_10_04)"
  if (Evaluate-Class $class $local_classes $system_classes) {
    $local_classes = Merge-ClassContext $local_classes $(Command-Execution -Command "ls" -componentName "Command execution" -reportId $reportId -techniqueName $techniqueName -Report:$true -AuditOnly:$auditOnly).get_item("classes")
  }
  else {
    _rudder_common_report_na -ComponentName "Command execution" -ComponentKey "ls" -Message "Not applicable" -ReportId $ReportId -TechniqueName $TechniqueName -Report:$true -AuditOnly:$AuditOnly
  }
}