function Technique-Any {
  [CmdletBinding()]
  param (
      [parameter(Mandatory=$true)]
      [string]$reportId,
      [parameter(Mandatory=$true)]
      [string]$techniqueName,
      [parameter(Mandatory=$true)]
      [string]$Version,
      [switch]$auditOnly
  )

  $local_classes = New-ClassContext

  $local_classes = Merge-ClassContext $local_classes $(Package-Install-Version -PackageName "$($node.properties[apache_package_name])" -PackageVersion "2.2.11" -componentName "Test component$&é)à\'`"" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly).get_item("classes")

}
