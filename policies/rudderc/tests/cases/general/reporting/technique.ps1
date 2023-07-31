function Reporting {
    [CmdletBinding()]
    param (
        [parameter(Mandatory = $true)]
        [string]$reportId,
        [parameter(Mandatory = $true)]
        [string]$techniqueName,

        [Rudder.PolicyMode]$policyMode
    )
    $techniqueParams = @{

    }
    BeginTechniqueCall -Name $techniqueName -Parameters $techniqueParams
    $reportIdBase = $reportId.Substring(0, $reportId.Length - 1)
    $localContext = New-Object -TypeName "Rudder.Context" -ArgumentList @($techniqueName)
    $localContext.Merge($system_classes)



    $reportId=$reportIdBase + "a86ce2e5-d5b6-45cc-87e8-c11cca71d908"
    $componentKey = "htop"
    $reportParams = @{
        ClassPrefix = ([Rudder.Condition]::canonify(("package_present_" + $componentKey)))
        ComponentKey = $componentKey
        ComponentName = "No block without condition"
        PolicyMode = $policyMode
        ReportId = $reportId
        DisableReporting = false
        TechniqueName = $techniqueName
    }
    
    $methodParams = @{
        Architecture = ""
        Name = "htop"
        Provider = ""
        Version = ""
        
    }
    $call = PackagePresent $methodParams -PolicyMode $policyMode
    $methodContext = Compute-Method-Call @reportParams -MethodCall $call
    $localContext.merge($methodContext)
    

    $reportId=$reportIdBase + "b86ce2e5-d5b6-45cc-87e8-c11cca71d907"
    $componentKey = "htop"
    $reportParams = @{
        ClassPrefix = ([Rudder.Condition]::canonify(("package_present_" + $componentKey)))
        ComponentKey = $componentKey
        ComponentName = "No block with condition"
        PolicyMode = $policyMode
        ReportId = $reportId
        DisableReporting = false
        TechniqueName = $techniqueName
    }
    
    $class = "debian"
    if ($localContext.Evaluate($class)) {
        $methodParams = @{
            Architecture = ""
            Name = "htop"
            Provider = ""
            Version = @'

'@
            
        }
        $call = PackagePresent $methodParams -PolicyMode $policyMode
        $methodContext = Compute-Method-Call @reportParams -MethodCall $call
        $localContext.merge($methodContext)
    } else {
        Rudder-Report-NA @reportParams
    }

    $reportId=$reportIdBase + "df06e919-02b7-41a7-a03f-4239592f3c12"
    $componentKey = "ntp"
    $reportParams = @{
        ClassPrefix = ([Rudder.Condition]::canonify(("package_present_" + $componentKey)))
        ComponentKey = $componentKey
        ComponentName = "NTP service"
        PolicyMode = $policyMode
        ReportId = $reportId
        DisableReporting = false
        TechniqueName = $techniqueName
    }
    
    $methodParams = @{
        Architecture = ""
        Name = "ntp"
        Provider = ""
        Version = ""
        
    }
    $call = PackagePresent $methodParams -PolicyMode $policyMode
    $methodContext = Compute-Method-Call @reportParams -MethodCall $call
    $localContext.merge($methodContext)
    

    $reportId=$reportIdBase + "df06e919-02b7-41a7-a03f-4239592f3c45"
    $componentKey = "ntp"
    $reportParams = @{
        ClassPrefix = ([Rudder.Condition]::canonify(("package_present_" + $componentKey)))
        ComponentKey = $componentKey
        ComponentName = "NTP service"
        PolicyMode = $policyMode
        ReportId = $reportId
        DisableReporting = false
        TechniqueName = $techniqueName
    }
    
    $methodParams = @{
        Architecture = ""
        Name = "ntp"
        Provider = ""
        Version = ""
        
    }
    $call = PackagePresent $methodParams -PolicyMode $policyMode
    $methodContext = Compute-Method-Call @reportParams -MethodCall $call
    $localContext.merge($methodContext)
    

    $reportId=$reportIdBase + "cf06e919-02b7-41a7-a03f-4239592f3c14"
    $componentKey = "ntp"
    $reportParams = @{
        ClassPrefix = ([Rudder.Condition]::canonify(("package_present_" + $componentKey)))
        ComponentKey = $componentKey
        ComponentName = "NTP service"
        PolicyMode = $policyMode
        ReportId = $reportId
        DisableReporting = false
        TechniqueName = $techniqueName
    }
    
    $methodParams = @{
        Architecture = ""
        Name = "ntp"
        Provider = ""
        Version = ""
        
    }
    $call = PackagePresent $methodParams -PolicyMode $policyMode
    $methodContext = Compute-Method-Call @reportParams -MethodCall $call
    $localContext.merge($methodContext)
    

    $reportId=$reportIdBase + "cf06e919-02b7-41a7-a03f-4239592f3c13"
    $componentKey = "ntp"
    $reportParams = @{
        ClassPrefix = ([Rudder.Condition]::canonify(("package_present_" + $componentKey)))
        ComponentKey = $componentKey
        ComponentName = "NTP service"
        PolicyMode = $policyMode
        ReportId = $reportId
        DisableReporting = false
        TechniqueName = $techniqueName
    }
    
    $methodParams = @{
        Architecture = ""
        Name = "ntp"
        Provider = ""
        Version = ""
        
    }
    $call = PackagePresent $methodParams -PolicyMode $policyMode
    $methodContext = Compute-Method-Call @reportParams -MethodCall $call
    $localContext.merge($methodContext)
    

    $reportId=$reportIdBase + "cf06e919-02b7-41a7-a03f-4239592f3c21"
    $componentKey = "ntp"
    $reportParams = @{
        ClassPrefix = ([Rudder.Condition]::canonify(("package_present_" + $componentKey)))
        ComponentKey = $componentKey
        ComponentName = "Enabled reporting"
        PolicyMode = $policyMode
        ReportId = $reportId
        DisableReporting = false
        TechniqueName = $techniqueName
    }
    
    $methodParams = @{
        Architecture = ""
        Name = "ntp"
        Provider = ""
        Version = ""
        
    }
    $call = PackagePresent $methodParams -PolicyMode $policyMode
    $methodContext = Compute-Method-Call @reportParams -MethodCall $call
    $localContext.merge($methodContext)
    

    $reportId=$reportIdBase + "c76686bb-79ab-4ae5-b45f-108492ab4101"
    $componentKey = "ntp"
    $reportParams = @{
        ClassPrefix = ([Rudder.Condition]::canonify(("package_present_" + $componentKey)))
        ComponentKey = $componentKey
        ComponentName = "Disabled reporting"
        PolicyMode = $policyMode
        ReportId = $reportId
        DisableReporting = true
        TechniqueName = $techniqueName
    }
    
    $methodParams = @{
        Architecture = ""
        Name = "ntp"
        Provider = ""
        Version = ""
        
    }
    $call = PackagePresent $methodParams -PolicyMode $policyMode
    $methodContext = Compute-Method-Call @reportParams -MethodCall $call
    $localContext.merge($methodContext)
    


    EndTechniqueCall -Name $techniqueName
}