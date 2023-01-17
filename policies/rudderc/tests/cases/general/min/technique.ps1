function min
{
    [CmdletBinding()]
    param (
        [parameter(Mandatory = $true)]
        [string]$reportId,
        [parameter(Mandatory = $true)]
        [string]$techniqueName,

        [Rudder.PolicyMode]$policyMode
    )
    BeginTechniqueCall -Name $techniqueName
    $reportIdBase = $reportId.Substring(0, $reportId.Length - 1)
    $localContext = New-Object -TypeName "Rudder.Context" -ArgumentList @($techniqueName)
    $localContext.Merge($system_classes)



    $reportId=$reportIdBase + "d86ce2e5-d5b6-45cc-87e8-c11cca71d907"
    $componentKey = "TODO"
    $reportParams = @{
    ClassPrefix = ([Rudder.Condition]::canonify(("package_present_" + $componentKey)))
    ComponentKey = $componentKey
    ComponentName = "TODO"
    PolicyMode = $policyMode
    ReportId = $reportId
    DisableReporting = false
    TechniqueName = $techniqueName
    }
    
    $class = "true"
    if ($localContext.Evaluate($class)) {
        $methodParams = @{
            TODO
        }
        $call = PackagePresent @methodParams
        $methodContext = Compute-Method-Call @reportParams -MethodCall $call
        $localContext.merge($methodContext)
    } else {
        Rudder-Report-NA @reportParams
    }


    EndTechniqueCall -Name $techniqueName
}