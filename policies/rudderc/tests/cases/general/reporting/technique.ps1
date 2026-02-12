function Technique-Reporting {
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
    $splitReportId = $reportId -Split '@@'
    $directiveId = if ($splitReportId.Count -ge 2) {
        $splitReportId[1]
    } else {
        [Rudder.Logger]::Log.Debug("The reportId '${reportId}' does not seem to contain any directive id")
        ''
    }

    $fallBackReportParams = @{
        ClassPrefix = 'skipped_method'
        ComponentKey = 'None'
        ComponentName = 'None'
        TechniqueName = $techniqueName
    }


    $reportId=$reportIdBase + "a86ce2e5-d5b6-45cc-87e8-c11cca71d908"
    $resultId=$directiveId + '-' + "a86ce2e5-d5b6-45cc-87e8-c11cca71d908"
    try {
        $componentKey = @'
htop
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("package_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
No block without condition
'@
            PolicyMode = $policyMode
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        
        $methodParams = @{
            Architecture = ''
            Name = @'
htop
'@
            Provider = ''
            Version = @'

'@
            
        }
        $call = Package-Present @methodParams -PolicyMode $policyMode
        Compute-Method-Call @reportParams -MethodCall $call
        
    } catch [Nustache.Core.NustacheDataContextMissException], [Nustache.Core.NustacheException] {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                $_.ToString()
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    }

    $reportId=$reportIdBase + "b86ce2e5-d5b6-45cc-87e8-c11cca71d907"
    $resultId=$directiveId + '-' + "b86ce2e5-d5b6-45cc-87e8-c11cca71d907"
    try {
        $componentKey = @'
htop
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("package_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
No block with condition
'@
            PolicyMode = $policyMode
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        
        $class = "debian"
        if ([Rudder.Datastate]::Evaluate($class)) {
            $methodParams = @{
                Architecture = ''
                Name = @'
htop
'@
                Provider = ''
                Version = @'

'@
                
            }
            $call = Package-Present @methodParams -PolicyMode $policyMode
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
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    }

    $reportId=$reportIdBase + "df06e919-02b7-41a7-a03f-4239592f3c12"
    $resultId=$directiveId + '-' + "df06e919-02b7-41a7-a03f-4239592f3c12"
    try {
        $componentKey = @'
ntp
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("package_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
NTP service
'@
            PolicyMode = $policyMode
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        
        $methodParams = @{
            Architecture = ''
            Name = @'
ntp
'@
            Provider = ''
            Version = @'

'@
            
        }
        $call = Package-Present @methodParams -PolicyMode $policyMode
        Compute-Method-Call @reportParams -MethodCall $call
        
    } catch [Nustache.Core.NustacheDataContextMissException], [Nustache.Core.NustacheException] {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                $_.ToString()
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    }

    $reportId=$reportIdBase + "df06e919-02b7-41a7-a03f-4239592f3c45"
    $resultId=$directiveId + '-' + "df06e919-02b7-41a7-a03f-4239592f3c45"
    try {
        $componentKey = @'
ntp
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("package_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
NTP service
'@
            PolicyMode = $policyMode
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        
        $methodParams = @{
            Architecture = ''
            Name = @'
ntp
'@
            Provider = ''
            Version = @'

'@
            
        }
        $call = Package-Present @methodParams -PolicyMode $policyMode
        Compute-Method-Call @reportParams -MethodCall $call
        
    } catch [Nustache.Core.NustacheDataContextMissException], [Nustache.Core.NustacheException] {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                $_.ToString()
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    }

    $reportId=$reportIdBase + "cf06e919-02b7-41a7-a03f-4239592f3c14"
    $resultId=$directiveId + '-' + "cf06e919-02b7-41a7-a03f-4239592f3c14"
    try {
        $componentKey = @'
ntp
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("package_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
NTP service
'@
            PolicyMode = $policyMode
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        
        $methodParams = @{
            Architecture = ''
            Name = @'
ntp
'@
            Provider = ''
            Version = @'

'@
            
        }
        $call = Package-Present @methodParams -PolicyMode $policyMode
        Compute-Method-Call @reportParams -MethodCall $call
        
    } catch [Nustache.Core.NustacheDataContextMissException], [Nustache.Core.NustacheException] {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                $_.ToString()
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    }

    $reportId=$reportIdBase + "cf06e919-02b7-41a7-a03f-4239592f3c13"
    $resultId=$directiveId + '-' + "cf06e919-02b7-41a7-a03f-4239592f3c13"
    try {
        $componentKey = @'
ntp
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("package_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
NTP service
'@
            PolicyMode = $policyMode
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        
        $methodParams = @{
            Architecture = ''
            Name = @'
ntp
'@
            Provider = ''
            Version = @'

'@
            
        }
        $call = Package-Present @methodParams -PolicyMode $policyMode
        Compute-Method-Call @reportParams -MethodCall $call
        
    } catch [Nustache.Core.NustacheDataContextMissException], [Nustache.Core.NustacheException] {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                $_.ToString()
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    }

    $reportId=$reportIdBase + "cf06e919-02b7-41a7-a03f-4239592f3c21"
    $resultId=$directiveId + '-' + "cf06e919-02b7-41a7-a03f-4239592f3c21"
    try {
        $componentKey = @'
ntp
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("package_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
Enabled reporting
'@
            PolicyMode = $policyMode
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        
        $methodParams = @{
            Architecture = ''
            Name = @'
ntp
'@
            Provider = ''
            Version = @'

'@
            
        }
        $call = Package-Present @methodParams -PolicyMode $policyMode
        Compute-Method-Call @reportParams -MethodCall $call
        
    } catch [Nustache.Core.NustacheDataContextMissException], [Nustache.Core.NustacheException] {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                $_.ToString()
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    }

    $reportId=$reportIdBase + "c76686bb-79ab-4ae5-b45f-108492ab4101"
    $resultId=$directiveId + '-' + "c76686bb-79ab-4ae5-b45f-108492ab4101"
    try {
        $componentKey = @'
ntp
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("package_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
Disabled reporting
'@
            PolicyMode = $policyMode
            ReportId = $reportId
            DisableReporting = $true
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        
        $methodParams = @{
            Architecture = ''
            Name = @'
ntp
'@
            Provider = ''
            Version = @'

'@
            
        }
        $call = Package-Present @methodParams -PolicyMode $policyMode
        Compute-Method-Call @reportParams -MethodCall $call
        
    } catch [Nustache.Core.NustacheDataContextMissException], [Nustache.Core.NustacheException] {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                $_.ToString()
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$true -MethodCall $failedCall -ResultId $resultId
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$true -MethodCall $failedCall -ResultId $resultId
    }

    $reportId=$reportIdBase + "df06e919-02b7-41a7-a03f-4239592f3c21"
    $resultId=$directiveId + '-' + "df06e919-02b7-41a7-a03f-4239592f3c21"
    try {
        $componentKey = @'
ntp
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("package_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
Enabled reporting
'@
            PolicyMode = $policyMode
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        
        $methodParams = @{
            Architecture = ''
            Name = @'
ntp
'@
            Provider = ''
            Version = @'

'@
            
        }
        $call = Package-Present @methodParams -PolicyMode $policyMode
        Compute-Method-Call @reportParams -MethodCall $call
        
    } catch [Nustache.Core.NustacheDataContextMissException], [Nustache.Core.NustacheException] {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                $_.ToString()
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    }

    $reportId=$reportIdBase + "d76686bb-79ab-4ae5-b45f-108492ab4101"
    $resultId=$directiveId + '-' + "d76686bb-79ab-4ae5-b45f-108492ab4101"
    try {
        $componentKey = @'
ntp
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("package_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
Disabled reporting
'@
            PolicyMode = $policyMode
            ReportId = $reportId
            DisableReporting = $true
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        
        $methodParams = @{
            Architecture = ''
            Name = @'
ntp
'@
            Provider = ''
            Version = @'

'@
            
        }
        $call = Package-Present @methodParams -PolicyMode $policyMode
        Compute-Method-Call @reportParams -MethodCall $call
        
    } catch [Nustache.Core.NustacheDataContextMissException], [Nustache.Core.NustacheException] {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                $_.ToString()
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$true -MethodCall $failedCall -ResultId $resultId
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$true -MethodCall $failedCall -ResultId $resultId
    }

    EndTechniqueCall -Name $techniqueName
}