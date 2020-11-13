function Parameters-Mult-Err {
  [CmdletBinding()]
  param (
      [parameter(Mandatory=$true)]
      [string]$reportId,
      [parameter(Mandatory=$true)]
      [string]$techniqueName,
      [parameter(Mandatory=$true)]
      [string]$${coucou},
      [switch]$auditOnly
  )

  $local_classes = New-ClassContext
  $resources_dir = $PSScriptRoot + "\resources"

  _rudder_common_report_na -componentName "Package absent" -componentKey "vpn" -message "Not applicable" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly

}
