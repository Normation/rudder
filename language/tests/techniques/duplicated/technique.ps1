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

  $LocalClasses = Merge-ClassContext $LocalClasses $(Command-Execution -Command "/usr/sbin/usermod -a -G vboxusers nwcyrille" -ComponentName "Command execution" -ReportId $ReportId -TechniqueName $TechniqueName -AuditOnly:$AuditOnly).get_item("classes")
  
  $LocalClasses = Merge-ClassContext $LocalClasses $(Command-Execution -Command "/usr/sbin/usermod -a -G vboxusers nweric" -ComponentName "Command execution" -ReportId $ReportId -TechniqueName $TechniqueName -AuditOnly:$AuditOnly).get_item("classes")
  
  $LocalClasses = Merge-ClassContext $LocalClasses $(Command-Execution -Command "/usr/sbin/usermod -a -G vboxusers nwantoine" -ComponentName "Command execution" -ReportId $ReportId -TechniqueName $TechniqueName -AuditOnly:$AuditOnly).get_item("classes")
}
