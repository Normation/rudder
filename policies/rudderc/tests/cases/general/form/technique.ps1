function Technique-Form {
    [CmdletBinding()]
    param (
        [parameter(Mandatory = $true)]
        [Alias('reportId')]
        [string]$reportIdentifier,
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
    BeginTechniqueCall -Name $techniqueName -Parameters $PSBoundParameters
    $reportIdBase = $reportIdentifier.Substring(0, $reportIdentifier.Length - 1)
    Add-RudderVar -Name 'resources_dir' -Value ($PSScriptRoot + '\resources')
    $fallBackReportParams = @{
        ClassPrefix = 'skipped_method'
        ComponentKey = 'None'
        ComponentName = 'None'
        TechniqueName = $techniqueName
    }


    $identifier=$reportIdBase + 'd86ce2e5-d5b6-45cc-87e8-c11cca71d907'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-d86ce2e5-d5b6-45cc-87e8-c11cca71d907'
    try {
        $componentKey = @'
htop
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("package_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
Ensure correct ntp configuration
'@
            PolicyMode = $policyMode
            ReportId = $identifier
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        Add-RudderVar -Name 'report_data' -Value @{
          component_name = $reportParams['ComponentName']
          component_key = $reportParams['ComponentKey']
          report_id_r = 'd86ce2e5-d5b6-45cc-87e8-c11cca71d907'
          report_id = 'd86ce2e5_d5b6_45cc_87e8_c11cca71d907'
          result_id = $resultId
          identifier = $identifier
        }
        
        $class = "debian"
        if ([Rudder.Datastate]::Evaluate($class)) {
            $methodParams = @{
                Architecture = ''
                Name = @'
htop
'@
                Provider = ''
                Version = @'
2.3.4
'@
                
                PolicyMode = $policyMode
            }
            $call = Package-Present @methodParams
            Compute-Method-Call @reportParams -MethodCall $call
        } else {
            Rudder-Report-NA @reportParams
        }
    } catch [Nustache.Core.NustacheDataContextMissException], [Nustache.Core.NustacheException] {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                $_.ToString()
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $identifier -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    } catch {
        [Rudder.Logger]::Log.Debug($_)
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $identifier -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    }

    EndTechniqueCall -Name $techniqueName
}