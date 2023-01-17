function reporting
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



    $reportId=$reportIdBase + "a86ce2e5-d5b6-45cc-87e8-c11cca71d908"
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

    $reportId=$reportIdBase + "b86ce2e5-d5b6-45cc-87e8-c11cca71d907"
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

    $reportId=$reportIdBase + "df06e919-02b7-41a7-a03f-4239592f3c12"
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

    $reportId=$reportIdBase + "df06e919-02b7-41a7-a03f-4239592f3c45"
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

    $reportId=$reportIdBase + "cf06e919-02b7-41a7-a03f-4239592f3c14"
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

    $reportId=$reportIdBase + "cf06e919-02b7-41a7-a03f-4239592f3c13"
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

    $reportId=$reportIdBase + "cf06e919-02b7-41a7-a03f-4239592f3d12"
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

    $reportId=$reportIdBase + "c76686bb-79ab-4ae5-b45f-108492ab4101"
    $componentKey = "TODO"
    $reportParams = @{
    ClassPrefix = ([Rudder.Condition]::canonify(("package_present_" + $componentKey)))
    ComponentKey = $componentKey
    ComponentName = "TODO"
    PolicyMode = $policyMode
    ReportId = $reportId
    DisableReporting = true
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