function Technique-Test-Windows {
    [CmdletBinding()]
    param (
        [parameter(Mandatory = $true)]
        [Alias('reportId')]
        [string]$reportIdentifier,
        [parameter(Mandatory = $true)]
        [string]$techniqueName,

        [parameter(Mandatory = $true)]
        [string]$content,
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


    $identifier=$reportIdBase + 'd982a7e6-494a-40a5-aea1-7d9a185eed61'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-d982a7e6-494a-40a5-aea1-7d9a185eed61'
    try {
        $componentKey = @'
/some/path
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_lines_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
File content
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
          report_id_r = 'd982a7e6-494a-40a5-aea1-7d9a185eed61'
          report_id = 'd982a7e6_494a_40a5_aea1_7d9a185eed61'
          result_id = $resultId
          identifier = $identifier
        }
        
        $methodParams = @{
            Enforce = @'
true
'@
            Lines = @'
# Raw string
foo foobar
# With parameter
foo 
'@ + ([Rudder.Datastate]::Render('{{{' + @'
vars.test_windows.content
'@ + '}}}')) + @'
 foobar
# With a var looking like a parameter
foo 
'@ + ([Rudder.Datastate]::Render('{{{' + @'
vars.contentbis
'@ + '}}}')) + @'

# With a const

'@ + ([Rudder.Datastate]::Render('{{{' + @'
vars.const.n
'@ + '}}}')) + @'

# With node properties

'@ + ([Rudder.Datastate]::Render('{{{' + @'
vars.node.properties.name.key
'@ + '}}}'))
            Path = @'
/some/path
'@
            
            PolicyMode = $policyMode
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