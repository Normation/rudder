function Technique-Unsupported-Methods {
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


    $reportId=$reportIdBase + "82a3d8ca-bf7c-4b5d-a8e6-4423ecb5f532"
    try {
        $componentKey = @'
bob
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("user_group_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
Linux user group on Windows
'@
            PolicyMode = $policyMode
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            MethodId = '82a3d8ca-bf7c-4b5d-a8e6-4423ecb5f532'
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
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId '82a3d8ca-bf7c-4b5d-a8e6-4423ecb5f532' -MethodCall $failedCall
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId '82a3d8ca-bf7c-4b5d-a8e6-4423ecb5f532' -MethodCall $failedCall
    }

    $reportId=$reportIdBase + "0a9dcb32-e310-488c-b3e8-cbcfc6ae284a"
    try {
        $componentKey = @'
Write-Host "hello world"
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("powershell_execution_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
Powershell exec on Linux
'@
            PolicyMode = $policyMode
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            MethodId = '0a9dcb32-e310-488c-b3e8-cbcfc6ae284a'
        }
        
        $class = "linux"
        if ([Rudder.Datastate]::Evaluate($class)) {
            $methodParams = @{
                Command = @'
Write-Host "hello world"
'@
                RepairedRegex = @'
.*
'@
                SuccessRegex = @'
success
'@
                
            }
            $call = Powershell-Execution @methodParams -PolicyMode $policyMode
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
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId '0a9dcb32-e310-488c-b3e8-cbcfc6ae284a' -MethodCall $failedCall
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId '0a9dcb32-e310-488c-b3e8-cbcfc6ae284a' -MethodCall $failedCall
    }

    EndTechniqueCall -Name $techniqueName
}