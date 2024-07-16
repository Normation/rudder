function Technique-Min {
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


    $reportId=$reportIdBase + "d86ce2e5-d5b6-45cc-87e8-c11cca71d907"
    $componentKey = 'htop'
    $reportParams = try {
        @{
            ClassPrefix = ([Rudder.Condition]::canonify(("package_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = 'Package present'
            PolicyMode = $policyMode
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
        }
        
        $methodParams = @{
            Architecture = ''
            Name = @'
htop
'@
            Provider = ''
            Version = @'
2.3.4
'@
            
        }
        $call = Package-Present @methodParams -PolicyMode $policyMode
        Compute-Method-Call @reportParams -MethodCall $call
        
    } catch [Nustache.Core.NustacheDataContextMissException] {
        $failedCall = New-Object -TypeName "Rudder.MethodResult" -ArgumentList @(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall
    } catch {
        $failedCall = New-Object -TypeName "Rudder.MethodResult" -ArgumentList @(
            [Rudder.MethodStatus]::Error,
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall
    }

    EndTechniqueCall -Name $techniqueName
}