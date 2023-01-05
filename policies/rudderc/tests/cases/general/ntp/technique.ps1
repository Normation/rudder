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
    $localContext = [Rudder.Context]::new($techniqueName)
    $localContext.Merge($system_classes)


    $modules_dir = $PSScriptRoot + "\modules"




    EndTechniqueCall -Name $techniqueName
}