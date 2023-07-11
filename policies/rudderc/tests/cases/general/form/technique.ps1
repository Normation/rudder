function Form {
    [CmdletBinding()]
    param (
        [parameter(Mandatory = $true)]
        [string]$reportId,
        [parameter(Mandatory = $true)]
        [string]$techniqueName,

        [parameter(Mandatory = $true)]
        [string]$server_a,
        [parameter(Mandatory = $false)]
        [string]$server_b,
        [parameter(Mandatory = $true)]
        [string]$server_c,
        [parameter(Mandatory = $true)]
        [string]$server_d,
        [parameter(Mandatory = $true)]
        [string]$server_e,
        [parameter(Mandatory = $true)]
        [string]$server_f,
        [parameter(Mandatory = $true)]
        [string]$server_g,
        [parameter(Mandatory = $true)]
        [string]$server_h,
        [parameter(Mandatory = $true)]
        [string]$server_i,
        [parameter(Mandatory = $true)]
        [string]$server_j,
        [parameter(Mandatory = $true)]
        [string]$server_k,
        [parameter(Mandatory = $true)]
        [string]$server_l,
        [parameter(Mandatory = $true)]
        [string]$server_m,
        [parameter(Mandatory = $true)]
        [string]$server_n,
        [Rudder.PolicyMode]$policyMode
    )
    $techniqueParams = @{

        "server_a" = $server_a
        "server_b" = $server_b
        "server_c" = $server_c
        "server_d" = $server_d
        "server_e" = $server_e
        "server_f" = $server_f
        "server_g" = $server_g
        "server_h" = $server_h
        "server_i" = $server_i
        "server_j" = $server_j
        "server_k" = $server_k
        "server_l" = $server_l
        "server_m" = $server_m
        "server_n" = $server_n
    }
    BeginTechniqueCall -Name $techniqueName -Parameters $techniqueParams
    $reportIdBase = $reportId.Substring(0, $reportId.Length - 1)
    $localContext = New-Object -TypeName "Rudder.Context" -ArgumentList @($techniqueName)
    $localContext.Merge($system_classes)



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
    
    $class = ""debian""
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


    EndTechniqueCall -Name $techniqueName
}