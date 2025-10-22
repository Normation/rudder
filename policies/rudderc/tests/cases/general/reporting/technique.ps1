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

    $fallBackReportParams = @{
        ClassPrefix = 'skipped_method'
        ComponentKey = 'None'
        ComponentName = 'None'
        TechniqueName = $techniqueName
    }


    $reportId=$reportIdBase + "a86ce2e5-d5b6-45cc-87e8-c11cca71d908"
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
            MethodId = 'a86ce2e5-d5b6-45cc-87e8-c11cca71d908'
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
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'a86ce2e5-d5b6-45cc-87e8-c11cca71d908' -MethodCall $failedCall
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'a86ce2e5-d5b6-45cc-87e8-c11cca71d908' -MethodCall $failedCall
    }

    $reportId=$reportIdBase + "b86ce2e5-d5b6-45cc-87e8-c11cca71d907"
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
            MethodId = 'b86ce2e5-d5b6-45cc-87e8-c11cca71d907'
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
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'b86ce2e5-d5b6-45cc-87e8-c11cca71d907' -MethodCall $failedCall
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'b86ce2e5-d5b6-45cc-87e8-c11cca71d907' -MethodCall $failedCall
    }

    $reportId=$reportIdBase + "df06e919-02b7-41a7-a03f-4239592f3c12"
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
            MethodId = 'df06e919-02b7-41a7-a03f-4239592f3c12'
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
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'df06e919-02b7-41a7-a03f-4239592f3c12' -MethodCall $failedCall
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'df06e919-02b7-41a7-a03f-4239592f3c12' -MethodCall $failedCall
    }

    $reportId=$reportIdBase + "df06e919-02b7-41a7-a03f-4239592f3c45"
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
            MethodId = 'df06e919-02b7-41a7-a03f-4239592f3c45'
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
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'df06e919-02b7-41a7-a03f-4239592f3c45' -MethodCall $failedCall
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'df06e919-02b7-41a7-a03f-4239592f3c45' -MethodCall $failedCall
    }

    $reportId=$reportIdBase + "cf06e919-02b7-41a7-a03f-4239592f3c14"
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
            MethodId = 'cf06e919-02b7-41a7-a03f-4239592f3c14'
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
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'cf06e919-02b7-41a7-a03f-4239592f3c14' -MethodCall $failedCall
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'cf06e919-02b7-41a7-a03f-4239592f3c14' -MethodCall $failedCall
    }

    $reportId=$reportIdBase + "cf06e919-02b7-41a7-a03f-4239592f3c13"
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
            MethodId = 'cf06e919-02b7-41a7-a03f-4239592f3c13'
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
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'cf06e919-02b7-41a7-a03f-4239592f3c13' -MethodCall $failedCall
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'cf06e919-02b7-41a7-a03f-4239592f3c13' -MethodCall $failedCall
    }

    $reportId=$reportIdBase + "cf06e919-02b7-41a7-a03f-4239592f3c21"
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
            MethodId = 'cf06e919-02b7-41a7-a03f-4239592f3c21'
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
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'cf06e919-02b7-41a7-a03f-4239592f3c21' -MethodCall $failedCall
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'cf06e919-02b7-41a7-a03f-4239592f3c21' -MethodCall $failedCall
    }

    $reportId=$reportIdBase + "c76686bb-79ab-4ae5-b45f-108492ab4101"
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
            MethodId = 'c76686bb-79ab-4ae5-b45f-108492ab4101'
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
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$true -MethodId 'c76686bb-79ab-4ae5-b45f-108492ab4101' -MethodCall $failedCall
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$true -MethodId 'c76686bb-79ab-4ae5-b45f-108492ab4101' -MethodCall $failedCall
    }

    $reportId=$reportIdBase + "df06e919-02b7-41a7-a03f-4239592f3c21"
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
            MethodId = 'df06e919-02b7-41a7-a03f-4239592f3c21'
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
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'df06e919-02b7-41a7-a03f-4239592f3c21' -MethodCall $failedCall
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'df06e919-02b7-41a7-a03f-4239592f3c21' -MethodCall $failedCall
    }

    $reportId=$reportIdBase + "d76686bb-79ab-4ae5-b45f-108492ab4101"
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
            MethodId = 'd76686bb-79ab-4ae5-b45f-108492ab4101'
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
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$true -MethodId 'd76686bb-79ab-4ae5-b45f-108492ab4101' -MethodCall $failedCall
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$true -MethodId 'd76686bb-79ab-4ae5-b45f-108492ab4101' -MethodCall $failedCall
    }

    EndTechniqueCall -Name $techniqueName
}