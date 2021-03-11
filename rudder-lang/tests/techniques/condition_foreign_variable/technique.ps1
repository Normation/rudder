function Condition-Foreign-Variable-Technique {
  [CmdletBinding()]
  param (
    [Parameter(Mandatory=$True)]
    [String]$ReportId,
    [Parameter(Mandatory=$True)]
    [String]$TechniqueName,
    [Switch]$AuditOnly
  )

  $LocalClasses = New-ClassContext
  $ResourcesDir = $PSScriptRoot + "\resources"
  $Class = "server_machine|group_sbu_cmcs__sles12_"
  if (Evaluate-Class $Class $LocalClasses $SystemClasses) {
    $LocalClasses = Merge-ClassContext $LocalClasses $(Command-Execution -Command "chown -R ${owner}:${owner} ${path}" -ComponentName "Command execution" -ReportId $ReportId -TechniqueName $TechniqueName -AuditOnly:$AuditOnly).get_item("classes")
  }
  else {
    _rudder_common_report_na -componentName "Command execution" -componentKey "chown -R ${owner}:${owner} ${path}" -message "Not applicable" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly
  }
}
