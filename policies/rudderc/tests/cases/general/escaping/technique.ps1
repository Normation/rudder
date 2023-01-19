function escaping
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



    $reportId=$reportIdBase + "a86ce2e5-d5b6-45cc-87e8-c11cca71d966"
    $componentKey = "${sys.host} ${sys.${host}} ' '' ''' $ $$ " "" \ \\"
    $reportParams = @{
    ClassPrefix = ([Rudder.Condition]::canonify(("package_present_" + $componentKey)))
    ComponentKey = $componentKey
    ComponentName = "${sys.host} ${sys.${host}} ' '' ''' $ $$ " "" \ \\"
    PolicyMode = $policyMode
    ReportId = $reportId
    DisableReporting = false
    TechniqueName = $techniqueName
    }
    
    $class = "debian"
    if ($localContext.Evaluate($class)) {
        $methodParams = @{
        architecture = ""
        name = "${sys.host} ${sys.${host}} ' '' ''' $ $$ " "" \ \\"
        provider = ""
        version = ""
        }
        $call = PackagePresent @methodParams
        $methodContext = Compute-Method-Call @reportParams -MethodCall $call
        $localContext.merge($methodContext)
    } else {
        Rudder-Report-NA @reportParams
    }


    EndTechniqueCall -Name $techniqueName
}