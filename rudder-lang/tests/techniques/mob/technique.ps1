function Add-GSN-Users-To-Vboxusers-Group {
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

  _rudder_common_report_na -componentName "Command execution" -componentKey "/usr/sbin/usermod -a -G vboxusers nwcyrille" -message "Not applicable" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly

  _rudder_common_report_na -componentName "Command execution" -componentKey "/usr/sbin/usermod -a -G vboxusers nweric" -message "Not applicable" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly

  _rudder_common_report_na -componentName "Command execution" -componentKey "/usr/sbin/usermod -a -G vboxusers nwantoine" -message "Not applicable" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly

}