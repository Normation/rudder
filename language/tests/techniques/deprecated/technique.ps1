function Deprecated {
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

  $local_classes = Merge-ClassContext $local_classes $(Directory-Create -Path "tmp" -componentName "Directory create" -reportId $reportId -techniqueName $techniqueName -Report:$true -AuditOnly:$auditOnly).get_item("classes")
}
