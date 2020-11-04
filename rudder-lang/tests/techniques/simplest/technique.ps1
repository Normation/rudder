function Simplest {
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

  $local_classes = Merge-ClassContext $local_classes $(File-Absent -Target "tmp" -componentName "File absent" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly).get_item("classes")

}
