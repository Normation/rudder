function Technique-Windows-Long-Param-Names {
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



    $reportId=$reportIdBase + "d86ce2e5-d5b6-45cc-87e8-c11cca71d907"
    $componentKey = "This should be ReportMessage"
    $reportParams = @{
        ClassPrefix = ([Rudder.Condition]::canonify(("report_if_condition_" + $componentKey)))
        ComponentKey = $componentKey
        ComponentName = "Report if condition"
        PolicyMode = $policyMode
        ReportId = $reportId
        DisableReporting = $false
        TechniqueName = $techniqueName
    }
    Rudder-Report-NA @reportParams


    EndTechniqueCall -Name $techniqueName
}
