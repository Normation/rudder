function Technique-With-Parameters {
  [CmdletBinding()]
  param (
      [parameter(Mandatory=$true)]
      [string]$reportId,
      [parameter(Mandatory=$true)]
      [string]$techniqueName,
      [parameter(Mandatory=$true)]
      [string]$The File To Check,
      [Rudder.PolicyMode]$policyMode
  )
  $techniqueParams = @{
    "The File To Check" = $The File To Check
  }
  BeginTechniqueCall -Name $techniqueName -Parameters $techniqueParams
  $reportIdBase = $reportId.Substring(0,$reportId.Length-1)
  $localContext = New-Object -TypeName "Rudder.Context" -ArgumentList @($techniqueName)
  $localContext.Merge($system_classes)
  $resources_dir = $PSScriptRoot + "\resources"

  $reportId=$reportIdBase+"66744459-5829-43e0-8b1c-5fece7c0df7f"
  $componentKey = "ls ${The_File_To_Check}"
  $reportParams = @{
    ClassPrefix = ([Rudder.Condition]::canonify(("command_execution_" + $componentKey)))
    ComponentKey = $componentKey
    ComponentName = "Trivial command"
    PolicyMode = $policyMode
    ReportId = $reportId
    DisableReporting = $false
    TechniqueName = $techniqueName
  }
  $call = Command-Execution -Command "ls ${The_File_To_Check}" -PolicyMode $policyMode
  $methodContext = Compute-Method-Call @reportParams -MethodCall $call
  $localContext.merge($methodContext)
  EndTechniqueCall -Name $techniqueName
}