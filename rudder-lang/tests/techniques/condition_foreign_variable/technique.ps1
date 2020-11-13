function Condition-Foreign-Variable-Technique {
  [CmdletBinding()]
  param (
    [Parameter(Mandatory=$False)] [Switch] $AuditOnly,
    [Parameter(Mandatory=$True)]  [String] $ReportId,
    [Parameter(Mandatory=$True)]  [String] $TechniqueName
  )

  $LocalClasses = New-ClassContext
  $ResourcesDir = $PSScriptRoot + "\resources"
  $Condition = "server_machine|group_sbu_cmcs__sles12_"
  if (Evaluate-Class $Condition $LocalClasses $SystemClasses) {
    $LocalClasses = Merge-ClassContext $LocalClasses $(Command-Execution -Command "chown -R ${owner}:${owner} ${path}" -ComponentName "Command-Execution" -ReportId $ReportId -AuditOnly $AuditOnly -TechniqueName $TechniqueName).get_item("classes")
  }
  else {
    _rudder_common_report_na -componentName "Command execution" -componentKey "chown -R ${owner}:${owner} ${path}" -message "Not applicable" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly
  }
}
