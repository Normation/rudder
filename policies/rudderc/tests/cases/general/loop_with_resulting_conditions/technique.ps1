function Technique-Testing-Iteration {
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


    $reportId=$reportIdBase + "18e9e0a3-88d5-49e3-bd94-df769031d4c0-0"
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
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            MethodId = '18e9e0a3-88d5-49e3-bd94-df769031d4c0-0'
        }
        
        $methodParams = @{
            Path = @'
/tmp/oui.txt
'@
            
        }
        $call = File-Present @methodParams -PolicyMode $policyMode
        Compute-Method-Call @reportParams -MethodCall $call
        
    } catch [Nustache.Core.NustacheDataContextMissException], [Nustache.Core.NustacheException] {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                $_.ToString()
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId '18e9e0a3-88d5-49e3-bd94-df769031d4c0-0' -MethodCall $failedCall
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId '18e9e0a3-88d5-49e3-bd94-df769031d4c0-0' -MethodCall $failedCall
    }

    $reportId=$reportIdBase + "c1bd4ac6-f88b-47e2-be33-2a18ede114d7-0"
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
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            MethodId = 'c1bd4ac6-f88b-47e2-be33-2a18ede114d7-0'
        }
        
        $class = (([Rudder.Condition]::Canonify([Rudder.Datastate]::Render('{{{' + @'
vars.something_undefined
'@ + '}}}'))) + '|file_present__tmp_oui_txt_repaired.any')
        if ([Rudder.Datastate]::Evaluate($class)) {
            $methodParams = @{
                Command = @'
echo /tmp/oui.txt
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
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'c1bd4ac6-f88b-47e2-be33-2a18ede114d7-0' -MethodCall $failedCall
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'c1bd4ac6-f88b-47e2-be33-2a18ede114d7-0' -MethodCall $failedCall
    }

    $reportId=$reportIdBase + "18e9e0a3-88d5-49e3-bd94-df769031d4c0-1"
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
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            MethodId = '18e9e0a3-88d5-49e3-bd94-df769031d4c0-1'
        }
        
        $methodParams = @{
            Path = @'
/tmp/non.txt
'@
            
        }
        $call = File-Present @methodParams -PolicyMode $policyMode
        Compute-Method-Call @reportParams -MethodCall $call
        
    } catch [Nustache.Core.NustacheDataContextMissException], [Nustache.Core.NustacheException] {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                $_.ToString()
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId '18e9e0a3-88d5-49e3-bd94-df769031d4c0-1' -MethodCall $failedCall
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId '18e9e0a3-88d5-49e3-bd94-df769031d4c0-1' -MethodCall $failedCall
    }

    $reportId=$reportIdBase + "c1bd4ac6-f88b-47e2-be33-2a18ede114d7-1"
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
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            MethodId = 'c1bd4ac6-f88b-47e2-be33-2a18ede114d7-1'
        }
        
        $class = (([Rudder.Condition]::Canonify([Rudder.Datastate]::Render('{{{' + @'
vars.something_undefined
'@ + '}}}'))) + '|file_present__tmp_non_txt_repaired.any')
        if ([Rudder.Datastate]::Evaluate($class)) {
            $methodParams = @{
                Command = @'
echo /tmp/non.txt
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
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'c1bd4ac6-f88b-47e2-be33-2a18ede114d7-1' -MethodCall $failedCall
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'c1bd4ac6-f88b-47e2-be33-2a18ede114d7-1' -MethodCall $failedCall
    }

    EndTechniqueCall -Name $techniqueName
}