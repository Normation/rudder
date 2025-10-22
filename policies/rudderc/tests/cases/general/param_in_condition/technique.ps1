function Technique-Param-In-Condition {
    [CmdletBinding()]
    param (
        [parameter(Mandatory = $true)]
        [string]$reportId,
        [parameter(Mandatory = $true)]
        [string]$techniqueName,

        [parameter(Mandatory = $true)]
        [string]$file,
        [Rudder.PolicyMode]$policyMode
    )
    $techniqueParams = @{

        "file" = $file
    }
    BeginTechniqueCall -Name $techniqueName -Parameters $techniqueParams
    $reportIdBase = $reportId.Substring(0, $reportId.Length - 1)

    $fallBackReportParams = @{
        ClassPrefix = 'skipped_method'
        ComponentKey = 'None'
        ComponentName = 'None'
        TechniqueName = $techniqueName
    }


    $reportId=$reportIdBase + "9e763779-9f33-44bc-ad73-1c5d5732301c"
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
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            MethodId = '9e763779-9f33-44bc-ad73-1c5d5732301c'
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
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId '9e763779-9f33-44bc-ad73-1c5d5732301c' -MethodCall $failedCall
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId '9e763779-9f33-44bc-ad73-1c5d5732301c' -MethodCall $failedCall
    }

    $reportId=$reportIdBase + "e8362340-dc50-4231-9b7f-748b51e9fa07"
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
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            MethodId = 'e8362340-dc50-4231-9b7f-748b51e9fa07'
        }
        
        $class = ('file_check_exists__tmp_' + ([Rudder.Condition]::Canonify([Rudder.Datastate]::Render('{{{' + @'
vars.param_in_condition.file
'@ + '}}}'))) + '_kept')
        if ([Rudder.Datastate]::Evaluate($class)) {
            $methodParams = @{
                Command = @'
echo "May be executed or not"
'@
                
            }
            $call = Command-Execution @methodParams -PolicyMode $policyMode
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
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'e8362340-dc50-4231-9b7f-748b51e9fa07' -MethodCall $failedCall
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'e8362340-dc50-4231-9b7f-748b51e9fa07' -MethodCall $failedCall
    }

    EndTechniqueCall -Name $techniqueName
}