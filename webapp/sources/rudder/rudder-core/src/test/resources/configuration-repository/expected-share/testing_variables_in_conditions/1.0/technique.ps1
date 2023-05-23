function Testing-Variables-In-Conditions {
  [CmdletBinding()]
  param (
      [parameter(Mandatory=$true)]
      [string]$reportId,
      [parameter(Mandatory=$true)]
      [string]$techniqueName,
      [parameter(Mandatory=$true)]
      [string]$my_custom_condition,
      [Rudder.PolicyMode]$policyMode
  )
  $techniqueParams = @{
    "my_custom_condition" = $my_custom_condition
  }
  BeginTechniqueCall -Name $techniqueName -Parameters $techniqueParams
  $reportIdBase = $reportId.Substring(0,$reportId.Length-1)
  $localContext = New-Object -TypeName "Rudder.Context" -ArgumentList @($techniqueName)
  $localContext.Merge($system_classes)
  $resources_dir = $PSScriptRoot + "\resources"

  $reportId=$reportIdBase+"c0f1c227-0b8c-4219-ac3d-3c30fb4870ad"
  $componentKey = "{return 1}"
  $reportParams = @{
    ClassPrefix = ([Rudder.Condition]::canonify(("command_execution_" + $componentKey)))
    ComponentKey = $componentKey
    ComponentName = "Command execution"
    PolicyMode = $policyMode
    ReportId = $reportId
    DisableReporting = $false
    TechniqueName = $techniqueName
  }
  $class = "" + ([Rudder.Condition]::canonify(${my_custom_condition})) + ""
  if ($localContext.Evaluate($class)) {
    $call = Command-Execution -Command "{return 1}" -PolicyMode $policyMode
    $methodContext = Compute-Method-Call @reportParams -MethodCall $call
    $localContext.merge($methodContext)
  } else {
    Rudder-Report-NA @reportParams
  }
  EndTechniqueCall -Name $techniqueName
}
