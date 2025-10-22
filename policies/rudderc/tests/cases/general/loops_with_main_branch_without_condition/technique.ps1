function Technique-Technique-With-Loops-With-Main-Branch-Without-Condition {
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

    $fallBackReportParams = @{
        ClassPrefix = 'skipped_method'
        ComponentKey = 'None'
        ComponentName = 'None'
        TechniqueName = $techniqueName
    }


    $reportId=$reportIdBase + "845f731a-2800-41c8-967e-7d1ce89bd1b9-0"
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
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            MethodId = '845f731a-2800-41c8-967e-7d1ce89bd1b9-0'
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
            
        }
        $call = File-From-Shared-Folder @methodParams -PolicyMode $policyMode
        Compute-Method-Call @reportParams -MethodCall $call
        
    } catch [Nustache.Core.NustacheDataContextMissException], [Nustache.Core.NustacheException] {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                $_.ToString()
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId '845f731a-2800-41c8-967e-7d1ce89bd1b9-0' -MethodCall $failedCall
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId '845f731a-2800-41c8-967e-7d1ce89bd1b9-0' -MethodCall $failedCall
    }

    $reportId=$reportIdBase + "845f731a-2800-41c8-967e-7d1ce89bd1b9-1"
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
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            MethodId = '845f731a-2800-41c8-967e-7d1ce89bd1b9-1'
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
                
            }
            $call = File-From-Shared-Folder @methodParams -PolicyMode $policyMode
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
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId '845f731a-2800-41c8-967e-7d1ce89bd1b9-1' -MethodCall $failedCall
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId '845f731a-2800-41c8-967e-7d1ce89bd1b9-1' -MethodCall $failedCall
    }

    EndTechniqueCall -Name $techniqueName
}