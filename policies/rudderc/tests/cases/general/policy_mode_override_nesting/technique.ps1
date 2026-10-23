function Technique-Nested-Policy-Mode {
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


    $identifier=$reportIdBase + '5e0d8a52-1c39-4c2f-9d3b-0a1f6c0b7a03'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-5e0d8a52-1c39-4c2f-9d3b-0a1f6c0b7a03'
    try {
        $componentKey = @'
/tmp/1
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_lines_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
Should resolve to audit
'@
            PolicyMode = ([Rudder.PolicyMode]::Audit)
            ReportIdentifier = $identifier
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        Add-RudderVar -Name 'report_data' -Value @{
          component_name = $reportParams['ComponentName']
          component_key = $reportParams['ComponentKey']
          report_id_r = '5e0d8a52-1c39-4c2f-9d3b-0a1f6c0b7a03'
          report_id = '5e0d8a52_1c39_4c2f_9d3b_0a1f6c0b7a03'
          result_id = $resultId
          identifier = $identifier
        }
        
        $methodParams = @{
            Enforce = @'
true
'@
            Lines = @'
foobar
'@
            Path = @'
/tmp/1
'@
            
            PolicyMode = ([Rudder.PolicyMode]::Audit)
        }
        $call = File-Content @methodParams
        Compute-Method-Call @reportParams -MethodCall $call
        
    } catch [Nustache.Core.NustacheDataContextMissException], [Nustache.Core.NustacheException] {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                $_.ToString()
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Audit) -ReportIdentifier $identifier -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    } catch {
        [Rudder.Logger]::Log.Debug($_)
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Audit) -ReportIdentifier $identifier -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    }

    EndTechniqueCall -Name $techniqueName
}