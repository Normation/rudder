function Technique-Param-In-Condition {
    [CmdletBinding()]
    param (
        [parameter(Mandatory = $true)]
        [string]$reportId,
        [parameter(Mandatory = $true)]
        [string]$techniqueName,

        [parameter(Mandatory = $true)]
        [string]$file,
        [Rudder.PolicyMode]$policyMode
    )
    $techniqueParams = @{

        "file" = $file
    }
    BeginTechniqueCall -Name $techniqueName -Parameters $techniqueParams
    $reportIdBase = $reportId.Substring(0, $reportId.Length - 1)
    $localContext = New-Object -TypeName "Rudder.Context" -ArgumentList @($techniqueName)
    $localContext.Merge($system_classes)



    $reportId=$reportIdBase + "9e763779-9f33-44bc-ad73-1c5d5732301c"
    $componentKey = "/tmp/${file}"
    $reportParams = @{
        ClassPrefix = ([Rudder.Condition]::canonify(("file_check_exists_" + $componentKey)))
        ComponentKey = $componentKey
        ComponentName = "Check if a file exists"
        PolicyMode = $policyMode
        ReportId = $reportId
        DisableReporting = $false
        TechniqueName = $techniqueName
    }
    Rudder-Report-NA @reportParams

    $reportId=$reportIdBase + "e8362340-dc50-4231-9b7f-748b51e9fa07"
    $componentKey = "echo `"May be executed or not`""
    $reportParams = @{
        ClassPrefix = ([Rudder.Condition]::canonify(("command_execution_" + $componentKey)))
        ComponentKey = $componentKey
        ComponentName = "Execute only if..."
        PolicyMode = $policyMode
        ReportId = $reportId
        DisableReporting = $false
        TechniqueName = $techniqueName
    }
    
    $class = "file_check_exists__tmp_" + ([Rudder.Condition]::canonify(${file})) + "_kept"
    if ($localContext.Evaluate($class)) {
        $methodParams = @{
            Command = "echo `"May be executed or not`""
            
        }
        $call = Command-Execution @methodParams -PolicyMode $policyMode
        $methodContext = Compute-Method-Call @reportParams -MethodCall $call
        $localContext.merge($methodContext)
    } else {
        Rudder-Report-NA @reportParams
    }


    EndTechniqueCall -Name $techniqueName
}