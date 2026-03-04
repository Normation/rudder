function Technique-Unsupported-Methods {
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


    $identifier=$reportIdBase + '82a3d8ca-bf7c-4b5d-a8e6-4423ecb5f532'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-82a3d8ca-bf7c-4b5d-a8e6-4423ecb5f532'
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
            ReportId = $identifier
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        Add-RudderVar -Name 'report_data' -Value @{
          component_name = $reportParams['ComponentName']
          component_key = $reportParams['ComponentKey']
          report_id_r = '82a3d8ca-bf7c-4b5d-a8e6-4423ecb5f532'
          report_id = '82a3d8ca_bf7c_4b5d_a8e6_4423ecb5f532'
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

    $identifier=$reportIdBase + '0a9dcb32-e310-488c-b3e8-cbcfc6ae284a'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-0a9dcb32-e310-488c-b3e8-cbcfc6ae284a'
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
            ReportId = $identifier
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        Add-RudderVar -Name 'report_data' -Value @{
          component_name = $reportParams['ComponentName']
          component_key = $reportParams['ComponentKey']
          report_id_r = '0a9dcb32-e310-488c-b3e8-cbcfc6ae284a'
          report_id = '0a9dcb32_e310_488c_b3e8_cbcfc6ae284a'
          result_id = $resultId
          identifier = $identifier
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
                
                PolicyMode = $policyMode
            }
            $call = Powershell-Execution @methodParams
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