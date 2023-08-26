function A-Simple-Yaml-Technique {
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



    $reportId=$reportIdBase + "bfe1978f-b0e7-4da3-9544-06d53eb985fa"
    $componentKey = "/tmp/test-rudder.txt"
    $reportParams = @{
        ClassPrefix = ([Rudder.Condition]::canonify(("file_lines_present_" + $componentKey)))
        ComponentKey = $componentKey
        ComponentName = "File content"
        PolicyMode = $policyMode
        ReportId = $reportId
        DisableReporting = $false
        TechniqueName = $techniqueName
    }
    
    $methodParams = @{
        Enforce = "true"
        Lines = "Hello World!"
        Path = "/tmp/test-rudder.txt"
        
    }
    $call = File-Content @methodParams -PolicyMode $policyMode
    $methodContext = Compute-Method-Call @reportParams -MethodCall $call
    $localContext.merge($methodContext)
    


    EndTechniqueCall -Name $techniqueName
}