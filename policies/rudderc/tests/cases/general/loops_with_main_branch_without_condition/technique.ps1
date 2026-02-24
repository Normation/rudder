function Technique-Technique-With-Loops-With-Main-Branch-Without-Condition {
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


    $identifier=$reportIdBase + '845f731a-2800-41c8-967e-7d1ce89bd1b9-0'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-845f731a-2800-41c8-967e-7d1ce89bd1b9-0'
    try {
        $componentKey = @'
/home/bob/.vimrc
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_from_shared_folder_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
Do something
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
          report_id_r = '845f731a-2800-41c8-967e-7d1ce89bd1b9-0'
          report_id = '845f731a_2800_41c8_967e_7d1ce89bd1b9_0'
          result_id = $resultId
          identifier = $identifier
        }
        
        $methodParams = @{
            HashType = @'
sha256
'@
            Path = @'
/home/bob/.vimrc
'@
            Source = @'
.vimrc
'@
            
            PolicyMode = $policyMode
        }
        $call = File-From-Shared-Folder @methodParams
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

    $identifier=$reportIdBase + '845f731a-2800-41c8-967e-7d1ce89bd1b9-1'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-845f731a-2800-41c8-967e-7d1ce89bd1b9-1'
    try {
        $componentKey = @'
/home/bob/.bashrc
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_from_shared_folder_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
Do something
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
          report_id_r = '845f731a-2800-41c8-967e-7d1ce89bd1b9-1'
          report_id = '845f731a_2800_41c8_967e_7d1ce89bd1b9_1'
          result_id = $resultId
          identifier = $identifier
        }
        
        $class = "a_condition_evaluated_at_runtime"
        if ([Rudder.Datastate]::Evaluate($class)) {
            $methodParams = @{
                HashType = @'
sha256
'@
                Path = @'
/home/bob/.bashrc
'@
                Source = @'
.bashrc
'@
                
                PolicyMode = $policyMode
            }
            $call = File-From-Shared-Folder @methodParams
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