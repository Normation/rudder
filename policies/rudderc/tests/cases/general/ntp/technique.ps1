function Technique-Ntp-Technique {
    [CmdletBinding()]
    param (
        [parameter(Mandatory = $true)]
        [Alias('reportId')]
        [string]$reportIdentifier,
        [parameter(Mandatory = $true)]
        [string]$techniqueName,

        [parameter(Mandatory = $true)]
        [string]$server,
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
        
        $class = "false"
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

    $identifier=$reportIdBase + 'cf06e919-02b7-41a7-a03f-4239592f3c12'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-cf06e919-02b7-41a7-a03f-4239592f3c12'
    try {
        $componentKey = @'
/bin/true "# 
'@ + ([Rudder.Datastate]::Render('{{{' + @'
vars.node.inventory.os.fullName
'@ + '}}}')) + @'
"
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("package_install_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
NTP service
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
          report_id_r = 'cf06e919-02b7-41a7-a03f-4239592f3c12'
          report_id = 'cf06e919_02b7_41a7_a03f_4239592f3c12'
          result_id = $resultId
          identifier = $identifier
        }
        
        $class = "linux.fedora"
        if ([Rudder.Datastate]::Evaluate($class)) {
            $methodParams = @{
                Name = @'
/bin/true "# 
'@ + ([Rudder.Datastate]::Render('{{{' + @'
vars.node.inventory.os.fullName
'@ + '}}}')) + @'
"
'@
                
                PolicyMode = $policyMode
            }
            $call = Package-Install @methodParams
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