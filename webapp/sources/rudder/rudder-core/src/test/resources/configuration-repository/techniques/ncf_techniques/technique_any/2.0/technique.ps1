function Technique-Any {
  [CmdletBinding()]
  param (
      [parameter(Mandatory=$true)]
      [string]$reportId,
      [parameter(Mandatory=$true)]
      [string]$techniqueName,
      [parameter(Mandatory=$true)]
      [string]$Version,
      [Rudder.PolicyMode]$policyMode
  )
  BeginTechniqueCall -Name $techniqueName
  $reportIdBase = $reportId.Substring(0,$reportId.Length-1)
  $localContext = New-Object -TypeName "Rudder.Context" -ArgumentList @($techniqueName)
  $localContext.Merge($system_classes)
  $resources_dir = $PSScriptRoot + "\resources"

  $reportId=$reportIdBase+"id"
  $componentKey = "$($node.properties[apache_package_name])"
  $reportParams = @{
    ClassPrefix = ([Rudder.Condition]::canonify(("package_install_version_" + $componentKey)))
    ComponentKey = $componentKey
    ComponentName = "Test component$&é)à\'`""
    PolicyMode = $policyMode
    ReportId = $reportId
    DisableReporting = $false
    TechniqueName = $techniqueName
  }
  $call = Package-Install-Version -PackageName "$($node.properties[apache_package_name])" -PackageVersion "2.2.11" -PolicyMode $policyMode
  $methodContext = Compute-Method-Call @reportParams -MethodCall $call
  $localContext.merge($methodContext)
  EndTechniqueCall -Name $techniqueName
}
