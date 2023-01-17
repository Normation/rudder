function ntp
{
    [CmdletBinding()]
    param (
        [parameter(Mandatory = $true)]
        [string]$reportId,
        [parameter(Mandatory = $true)]
        [string]$techniqueName,

        [parameter(Mandatory = $true)]
        [string]$server,
        [Rudder.PolicyMode]$policyMode
    )
    BeginTechniqueCall -Name $techniqueName
    $reportIdBase = $reportId.Substring(0, $reportId.Length - 1)
    $localContext = New-Object -TypeName "Rudder.Context" -ArgumentList @($techniqueName)
    $localContext.Merge($system_classes)

    $modules_dir = $PSScriptRoot + "\modules"



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
    
    $class = "debian"
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

    $reportId=$reportIdBase + "cf06e919-02b7-41a7-a03f-4239592f3c12"
    $componentKey = "TODO"
    $reportParams = @{
    ClassPrefix = ([Rudder.Condition]::canonify(("package_install_" + $componentKey)))
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
        $call = PackageInstall @methodParams
        $methodContext = Compute-Method-Call @reportParams -MethodCall $call
        $localContext.merge($methodContext)
    } else {
        Rudder-Report-NA @reportParams
    }


    EndTechniqueCall -Name $techniqueName
}