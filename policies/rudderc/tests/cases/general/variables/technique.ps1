function Technique-Test-Windows {
    [CmdletBinding()]
    param (
        [parameter(Mandatory = $true)]
        [string]$reportId,
        [parameter(Mandatory = $true)]
        [string]$techniqueName,

        [parameter(Mandatory = $true)]
        [string]$content,
        [Rudder.PolicyMode]$policyMode
    )
    $techniqueParams = @{

        "content" = $content
    }
    BeginTechniqueCall -Name $techniqueName -Parameters $techniqueParams
    $reportIdBase = $reportId.Substring(0, $reportId.Length - 1)

    $fallBackReportParams = @{
        ClassPrefix = 'skipped_method'
        ComponentKey = 'None'
        ComponentName = 'None'
        TechniqueName = $techniqueName
    }


    $reportId=$reportIdBase + "d982a7e6-494a-40a5-aea1-7d9a185eed61"
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
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            MethodId = 'd982a7e6-494a-40a5-aea1-7d9a185eed61'
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
            
        }
        $call = File-Content @methodParams -PolicyMode $policyMode
        Compute-Method-Call @reportParams -MethodCall $call
        
    } catch [Nustache.Core.NustacheDataContextMissException], [Nustache.Core.NustacheException] {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                $_.ToString()
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'd982a7e6-494a-40a5-aea1-7d9a185eed61' -MethodCall $failedCall
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'd982a7e6-494a-40a5-aea1-7d9a185eed61' -MethodCall $failedCall
    }

    EndTechniqueCall -Name $techniqueName
}