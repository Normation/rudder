function Ntp {
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
    $techniqueParams = @{

        "server" = $server
    }
    BeginTechniqueCall -Name $techniqueName -Parameters $techniqueParams
    $reportIdBase = $reportId.Substring(0, $reportId.Length - 1)
    $localContext = New-Object -TypeName "Rudder.Context" -ArgumentList @($techniqueName)
    $localContext.Merge($system_classes)

    $modules_dir = $PSScriptRoot + "\modules"



    $reportId=$reportIdBase + "d86ce2e5-d5b6-45cc-87e8-c11cca71d907"
    $componentKey = "htop"
    $reportParams = @{
        ClassPrefix = ([Rudder.Condition]::canonify(("package_present_" + $componentKey)))
        ComponentKey = $componentKey
        ComponentName = "Ensure correct ntp configuration"
        PolicyMode = $policyMode
        ReportId = $reportId
        DisableReporting = false
        TechniqueName = $techniqueName
    }
    
    $class = "false"
    if ($localContext.Evaluate($class)) {
        $methodParams = @{
            Architecture = ""
            Name = "htop"
            Provider = ""
            Version = @'
2.3.4
'@
            
        }
        $call = PackagePresent $methodParams -PolicyMode $policyMode
        $methodContext = Compute-Method-Call @reportParams -MethodCall $call
        $localContext.merge($methodContext)
    } else {
        Rudder-Report-NA @reportParams
    }

    $reportId=$reportIdBase + "cf06e919-02b7-41a7-a03f-4239592f3c12"
    $componentKey = "ntp"
    $reportParams = @{
        ClassPrefix = ([Rudder.Condition]::canonify(("package_install_" + $componentKey)))
        ComponentKey = $componentKey
        ComponentName = "NTP service"
        PolicyMode = $policyMode
        ReportId = $reportId
        DisableReporting = false
        TechniqueName = $techniqueName
    }
    
    $class = "linux.fedora"
    if ($localContext.Evaluate($class)) {
        $methodParams = @{
            Name = "ntp"
            
        }
        $call = PackageInstall $methodParams -PolicyMode $policyMode
        $methodContext = Compute-Method-Call @reportParams -MethodCall $call
        $localContext.merge($methodContext)
    } else {
        Rudder-Report-NA @reportParams
    }


    EndTechniqueCall -Name $techniqueName
}