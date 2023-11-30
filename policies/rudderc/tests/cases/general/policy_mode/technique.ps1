function Test-Audit {
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



    $reportId=$reportIdBase + "46b8025a-0b06-485c-9127-50e4258ee7e6"
    $componentKey = "/tmp/1"
    $reportParams = @{
        ClassPrefix = ([Rudder.Condition]::canonify(("file_lines_present_" + $componentKey)))
        ComponentKey = $componentKey
        ComponentName = "In audit mode"
        PolicyMode = ([Rudder.PolicyMode]::Audit)
        ReportId = $reportId
        DisableReporting = $false
        TechniqueName = $techniqueName
    }
    
    $methodParams = @{
        Enforce = "true"
        Lines = "foobar"
        Path = "/tmp/1"
        
    }
    $call = File-Content @methodParams -PolicyMode ([Rudder.PolicyMode]::Audit)
    $methodContext = Compute-Method-Call @reportParams -MethodCall $call
    $localContext.merge($methodContext)
    

    $reportId=$reportIdBase + "1eedce7b-3441-4251-bdd6-706fda3ec7a8"
    $componentKey = "/tmp/1"
    $reportParams = @{
        ClassPrefix = ([Rudder.Condition]::canonify(("file_lines_present_" + $componentKey)))
        ComponentKey = $componentKey
        ComponentName = "In omit mode"
        PolicyMode = $policyMode
        ReportId = $reportId
        DisableReporting = $false
        TechniqueName = $techniqueName
    }
    
    $methodParams = @{
        Enforce = "true"
        Lines = "foobar"
        Path = "/tmp/1"
        
    }
    $call = File-Content @methodParams -PolicyMode $policyMode
    $methodContext = Compute-Method-Call @reportParams -MethodCall $call
    $localContext.merge($methodContext)
    

    $reportId=$reportIdBase + "dbd5ba50-8dfc-11ee-a57e-84a938c470d4"
    $componentKey = "/tmp/1"
    $reportParams = @{
        ClassPrefix = ([Rudder.Condition]::canonify(("file_lines_present_" + $componentKey)))
        ComponentKey = $componentKey
        ComponentName = "In enforce mode"
        PolicyMode = ([Rudder.PolicyMode]::Enforce)
        ReportId = $reportId
        DisableReporting = $false
        TechniqueName = $techniqueName
    }
    
    $methodParams = @{
        Enforce = "true"
        Lines = "foobar"
        Path = "/tmp/1"
        
    }
    $call = File-Content @methodParams -PolicyMode ([Rudder.PolicyMode]::Enforce)
    $methodContext = Compute-Method-Call @reportParams -MethodCall $call
    $localContext.merge($methodContext)
    

    $reportId=$reportIdBase + "1d809592-808e-4177-8351-8b7b7769af69"
    $componentKey = "/tmp/1"
    $reportParams = @{
        ClassPrefix = ([Rudder.Condition]::canonify(("file_lines_present_" + $componentKey)))
        ComponentKey = $componentKey
        ComponentName = "In default mode"
        PolicyMode = $policyMode
        ReportId = $reportId
        DisableReporting = $false
        TechniqueName = $techniqueName
    }
    
    $methodParams = @{
        Enforce = "true"
        Lines = "foobar"
        Path = "/tmp/1"
        
    }
    $call = File-Content @methodParams -PolicyMode $policyMode
    $methodContext = Compute-Method-Call @reportParams -MethodCall $call
    $localContext.merge($methodContext)
    


    EndTechniqueCall -Name $techniqueName
}