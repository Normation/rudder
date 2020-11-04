function Normal {
  [CmdletBinding()]
  param (
      [parameter(Mandatory=$true)]
      [string]$reportId,
      [parameter(Mandatory=$true)]
      [string]$techniqueName,
      [parameter(Mandatory=$true)]
      [string]$Parameter wdd,
      [parameter(Mandatory=$true)]
      [string]$Paramtest,
      [switch]$auditOnly
  )

  $local_classes = New-ClassContext
  $resources_dir = $PSScriptRoot + "\resources"

  _rudder_common_report_na -componentName "Condition once" -componentKey "mycond" -message "Not applicable" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly

}
