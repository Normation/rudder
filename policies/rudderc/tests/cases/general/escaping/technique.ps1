function Escaping {
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



    $reportId=$reportIdBase + "a86ce2e5-d5b6-45cc-87e8-c11cca71d966"
    $componentKey = "${sys.host} . | / ${sys.${host}} ' '' ''' $ $$ " "" \ \\😋aà3"
    $reportParams = @{
        ClassPrefix = ([Rudder.Condition]::canonify(("package_present_" + $componentKey)))
        ComponentKey = $componentKey
        ComponentName = "${sys.host} . | / ${sys.${host}} ' '' ''' $ $$ `" `"`" \ \\😋aà3"
        PolicyMode = $policyMode
        ReportId = $reportId
        DisableReporting = false
        TechniqueName = $techniqueName
    }
    
    $class = """ + ("
    if ($localContext.Evaluate($class)) {
        $methodParams = @{
            Architecture = ""
            Name = "${sys.host} . | / ${sys.${host}} ' '' ''' "
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


    EndTechniqueCall -Name $techniqueName
}