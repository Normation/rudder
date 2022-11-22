function Technique-Any {
  [CmdletBinding()]
  param (
      [parameter(Mandatory=$true)]
      [string]$reportId,
      [parameter(Mandatory=$true)]
      [string]$techniqueName,
      [parameter(Mandatory=$true)]
      [string]$version,
      [switch]$auditOnly
  )
  $reportIdBase = $reportId.Substring(0,$reportId.Length-1)
  $local_classes = New-ClassContext
  $resources_dir = $PSScriptRoot + "\resources"

  $reportId=$reportIdBase+"id"

  $local_classes = Merge-ClassContext $local_classes $(Package-Install-Version -PackageName "$($node.properties[apache_package_name])" -PackageVersion "2.2.11" -componentName "Test component$&é)à\'`"" -Report:$true -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly).get_item("classes")

}
