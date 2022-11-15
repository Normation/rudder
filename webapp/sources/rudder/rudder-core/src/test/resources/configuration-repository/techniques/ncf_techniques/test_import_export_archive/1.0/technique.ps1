function Toto {
  [CmdletBinding()]
  param (
      [parameter(Mandatory=$true)]
      [string]$reportId,
      [parameter(Mandatory=$true)]
      [string]$techniqueName,
      [Rudder.PolicyMode]$policyMode
  )
  BeginTechniqueCall -Name $techniqueName
  $reportIdBase = $reportId.Substring(0,$reportId.Length-1)
  $localContext = [Rudder.Context]::new($techniqueName)
  $localContext.Merge($system_classes)
  $resources_dir = $PSScriptRoot + "\resources"

  $reportId=$reportIdBase+"483b4b60-f940-4b65-834a-4d8ddd085c34"
  $componentKey = "touch /tmp/toto"
  $reportParams = @{
    ClassPrefix = ([Rudder.Condition]::canonify(("command_execution_" + $componentKey)))
    ComponentKey = $componentKey
    ComponentName = "Command execution"
    PolicyMode = $policyMode
    ReportId = $reportId
    DisableReporting = $false
    TechniqueName = $techniqueName
  }
  Rudder-Report-NA @reportParams
  EndTechniqueCall -Name $techniqueName
}