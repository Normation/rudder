function Technique-Param-In-Condition {
    [CmdletBinding()]
    param (
        [parameter(Mandatory = $true)]
        [Alias('reportId')]
        [string]$reportIdentifier,
        [parameter(Mandatory = $true)]
        [string]$techniqueName,

        [parameter(Mandatory = $true)]
        [string]$file,
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


    $identifier=$reportIdBase + '9e763779-9f33-44bc-ad73-1c5d5732301c'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-9e763779-9f33-44bc-ad73-1c5d5732301c'
    try {
        $componentKey = @'
/tmp/
'@ + ([Rudder.Datastate]::Render('{{{' + @'
vars.param_in_condition.file
'@ + '}}}'))
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_check_exists_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
Check if a file exists
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
          report_id_r = '9e763779-9f33-44bc-ad73-1c5d5732301c'
          report_id = '9e763779_9f33_44bc_ad73_1c5d5732301c'
          result_id = $resultId
          identifier = $identifier
        }
        Rudder-Report-NA @reportParams
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

    $identifier=$reportIdBase + 'e8362340-dc50-4231-9b7f-748b51e9fa07'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-e8362340-dc50-4231-9b7f-748b51e9fa07'
    try {
        $componentKey = @'
echo "May be executed or not"
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("command_execution_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
Execute only if...
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
          report_id_r = 'e8362340-dc50-4231-9b7f-748b51e9fa07'
          report_id = 'e8362340_dc50_4231_9b7f_748b51e9fa07'
          result_id = $resultId
          identifier = $identifier
        }
        
        $class = ('file_check_exists__tmp_' + ([Rudder.Condition]::Canonify([Rudder.Datastate]::Render('{{{' + @'
vars.param_in_condition.file
'@ + '}}}'))) + '_kept')
        if ([Rudder.Datastate]::Evaluate($class)) {
            $methodParams = @{
                Command = @'
echo "May be executed or not"
'@
                
                PolicyMode = $policyMode
            }
            $call = Command-Execution @methodParams
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