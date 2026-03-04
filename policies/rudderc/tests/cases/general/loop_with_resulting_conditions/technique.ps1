function Technique-Testing-Iteration {
    [CmdletBinding()]
    param (
        [parameter(Mandatory = $true)]
        [Alias('reportId')]
        [string]$reportIdentifier,
        [parameter(Mandatory = $true)]
        [string]$techniqueName,

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


    $identifier=$reportIdBase + '18e9e0a3-88d5-49e3-bd94-df769031d4c0-0'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-18e9e0a3-88d5-49e3-bd94-df769031d4c0-0'
    try {
        $componentKey = @'
/tmp/oui.txt
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
File present
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
          report_id_r = '18e9e0a3-88d5-49e3-bd94-df769031d4c0-0'
          report_id = '18e9e0a3_88d5_49e3_bd94_df769031d4c0_0'
          result_id = $resultId
          identifier = $identifier
        }
        
        $methodParams = @{
            Path = @'
/tmp/oui.txt
'@
            
            PolicyMode = $policyMode
        }
        $call = File-Present @methodParams
        Compute-Method-Call @reportParams -MethodCall $call
        
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

    $identifier=$reportIdBase + 'c1bd4ac6-f88b-47e2-be33-2a18ede114d7-0'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-c1bd4ac6-f88b-47e2-be33-2a18ede114d7-0'
    try {
        $componentKey = @'
echo /tmp/oui.txt
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("command_execution_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
Command execution
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
          report_id_r = 'c1bd4ac6-f88b-47e2-be33-2a18ede114d7-0'
          report_id = 'c1bd4ac6_f88b_47e2_be33_2a18ede114d7_0'
          result_id = $resultId
          identifier = $identifier
        }
        
        $class = (([Rudder.Condition]::Canonify([Rudder.Datastate]::Render('{{{' + @'
vars.something_undefined
'@ + '}}}'))) + '|file_present__tmp_oui_txt_repaired.any')
        if ([Rudder.Datastate]::Evaluate($class)) {
            $methodParams = @{
                Command = @'
echo /tmp/oui.txt
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

    $identifier=$reportIdBase + '18e9e0a3-88d5-49e3-bd94-df769031d4c0-1'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-18e9e0a3-88d5-49e3-bd94-df769031d4c0-1'
    try {
        $componentKey = @'
/tmp/non.txt
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
File present
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
          report_id_r = '18e9e0a3-88d5-49e3-bd94-df769031d4c0-1'
          report_id = '18e9e0a3_88d5_49e3_bd94_df769031d4c0_1'
          result_id = $resultId
          identifier = $identifier
        }
        
        $methodParams = @{
            Path = @'
/tmp/non.txt
'@
            
            PolicyMode = $policyMode
        }
        $call = File-Present @methodParams
        Compute-Method-Call @reportParams -MethodCall $call
        
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

    $identifier=$reportIdBase + 'c1bd4ac6-f88b-47e2-be33-2a18ede114d7-1'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-c1bd4ac6-f88b-47e2-be33-2a18ede114d7-1'
    try {
        $componentKey = @'
echo /tmp/non.txt
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("command_execution_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
Command execution
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
          report_id_r = 'c1bd4ac6-f88b-47e2-be33-2a18ede114d7-1'
          report_id = 'c1bd4ac6_f88b_47e2_be33_2a18ede114d7_1'
          result_id = $resultId
          identifier = $identifier
        }
        
        $class = (([Rudder.Condition]::Canonify([Rudder.Datastate]::Render('{{{' + @'
vars.something_undefined
'@ + '}}}'))) + '|file_present__tmp_non_txt_repaired.any')
        if ([Rudder.Datastate]::Evaluate($class)) {
            $methodParams = @{
                Command = @'
echo /tmp/non.txt
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