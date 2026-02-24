function Technique-Reporting {
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


    $identifier=$reportIdBase + 'a86ce2e5-d5b6-45cc-87e8-c11cca71d908'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-a86ce2e5-d5b6-45cc-87e8-c11cca71d908'
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
            ReportId = $identifier
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        Add-RudderVar -Name 'report_data' -Value @{
          component_name = $reportParams['ComponentName']
          component_key = $reportParams['ComponentKey']
          report_id_r = 'a86ce2e5-d5b6-45cc-87e8-c11cca71d908'
          report_id = 'a86ce2e5_d5b6_45cc_87e8_c11cca71d908'
          result_id = $resultId
          identifier = $identifier
        }
        
        $methodParams = @{
            Architecture = ''
            Name = @'
htop
'@
            Provider = ''
            Version = @'

'@
            
            PolicyMode = $policyMode
        }
        $call = Package-Present @methodParams
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

    $identifier=$reportIdBase + 'b86ce2e5-d5b6-45cc-87e8-c11cca71d907'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-b86ce2e5-d5b6-45cc-87e8-c11cca71d907'
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
            ReportId = $identifier
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        Add-RudderVar -Name 'report_data' -Value @{
          component_name = $reportParams['ComponentName']
          component_key = $reportParams['ComponentKey']
          report_id_r = 'b86ce2e5-d5b6-45cc-87e8-c11cca71d907'
          report_id = 'b86ce2e5_d5b6_45cc_87e8_c11cca71d907'
          result_id = $resultId
          identifier = $identifier
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
                
                PolicyMode = $policyMode
            }
            $call = Package-Present @methodParams
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

    $identifier=$reportIdBase + 'df06e919-02b7-41a7-a03f-4239592f3c12'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-df06e919-02b7-41a7-a03f-4239592f3c12'
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
            ReportId = $identifier
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        Add-RudderVar -Name 'report_data' -Value @{
          component_name = $reportParams['ComponentName']
          component_key = $reportParams['ComponentKey']
          report_id_r = 'df06e919-02b7-41a7-a03f-4239592f3c12'
          report_id = 'df06e919_02b7_41a7_a03f_4239592f3c12'
          result_id = $resultId
          identifier = $identifier
        }
        
        $methodParams = @{
            Architecture = ''
            Name = @'
ntp
'@
            Provider = ''
            Version = @'

'@
            
            PolicyMode = $policyMode
        }
        $call = Package-Present @methodParams
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

    $identifier=$reportIdBase + 'df06e919-02b7-41a7-a03f-4239592f3c45'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-df06e919-02b7-41a7-a03f-4239592f3c45'
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
            ReportId = $identifier
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        Add-RudderVar -Name 'report_data' -Value @{
          component_name = $reportParams['ComponentName']
          component_key = $reportParams['ComponentKey']
          report_id_r = 'df06e919-02b7-41a7-a03f-4239592f3c45'
          report_id = 'df06e919_02b7_41a7_a03f_4239592f3c45'
          result_id = $resultId
          identifier = $identifier
        }
        
        $methodParams = @{
            Architecture = ''
            Name = @'
ntp
'@
            Provider = ''
            Version = @'

'@
            
            PolicyMode = $policyMode
        }
        $call = Package-Present @methodParams
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

    $identifier=$reportIdBase + 'cf06e919-02b7-41a7-a03f-4239592f3c14'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-cf06e919-02b7-41a7-a03f-4239592f3c14'
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
            ReportId = $identifier
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        Add-RudderVar -Name 'report_data' -Value @{
          component_name = $reportParams['ComponentName']
          component_key = $reportParams['ComponentKey']
          report_id_r = 'cf06e919-02b7-41a7-a03f-4239592f3c14'
          report_id = 'cf06e919_02b7_41a7_a03f_4239592f3c14'
          result_id = $resultId
          identifier = $identifier
        }
        
        $methodParams = @{
            Architecture = ''
            Name = @'
ntp
'@
            Provider = ''
            Version = @'

'@
            
            PolicyMode = $policyMode
        }
        $call = Package-Present @methodParams
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

    $identifier=$reportIdBase + 'cf06e919-02b7-41a7-a03f-4239592f3c13'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-cf06e919-02b7-41a7-a03f-4239592f3c13'
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
            ReportId = $identifier
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        Add-RudderVar -Name 'report_data' -Value @{
          component_name = $reportParams['ComponentName']
          component_key = $reportParams['ComponentKey']
          report_id_r = 'cf06e919-02b7-41a7-a03f-4239592f3c13'
          report_id = 'cf06e919_02b7_41a7_a03f_4239592f3c13'
          result_id = $resultId
          identifier = $identifier
        }
        
        $methodParams = @{
            Architecture = ''
            Name = @'
ntp
'@
            Provider = ''
            Version = @'

'@
            
            PolicyMode = $policyMode
        }
        $call = Package-Present @methodParams
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

    $identifier=$reportIdBase + 'cf06e919-02b7-41a7-a03f-4239592f3c21'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-cf06e919-02b7-41a7-a03f-4239592f3c21'
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
            ReportId = $identifier
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        Add-RudderVar -Name 'report_data' -Value @{
          component_name = $reportParams['ComponentName']
          component_key = $reportParams['ComponentKey']
          report_id_r = 'cf06e919-02b7-41a7-a03f-4239592f3c21'
          report_id = 'cf06e919_02b7_41a7_a03f_4239592f3c21'
          result_id = $resultId
          identifier = $identifier
        }
        
        $methodParams = @{
            Architecture = ''
            Name = @'
ntp
'@
            Provider = ''
            Version = @'

'@
            
            PolicyMode = $policyMode
        }
        $call = Package-Present @methodParams
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

    $identifier=$reportIdBase + 'c76686bb-79ab-4ae5-b45f-108492ab4101'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-c76686bb-79ab-4ae5-b45f-108492ab4101'
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
            ReportId = $identifier
            DisableReporting = $true
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        Add-RudderVar -Name 'report_data' -Value @{
          component_name = $reportParams['ComponentName']
          component_key = $reportParams['ComponentKey']
          report_id_r = 'c76686bb-79ab-4ae5-b45f-108492ab4101'
          report_id = 'c76686bb_79ab_4ae5_b45f_108492ab4101'
          result_id = $resultId
          identifier = $identifier
        }
        
        $methodParams = @{
            Architecture = ''
            Name = @'
ntp
'@
            Provider = ''
            Version = @'

'@
            
            PolicyMode = $policyMode
        }
        $call = Package-Present @methodParams
        Compute-Method-Call @reportParams -MethodCall $call
        
    } catch [Nustache.Core.NustacheDataContextMissException], [Nustache.Core.NustacheException] {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                $_.ToString()
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $identifier -DisableReporting:$true -MethodCall $failedCall -ResultId $resultId
    } catch {
        [Rudder.Logger]::Log.Debug($_)
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $identifier -DisableReporting:$true -MethodCall $failedCall -ResultId $resultId
    }

    $identifier=$reportIdBase + 'df06e919-02b7-41a7-a03f-4239592f3c21'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-df06e919-02b7-41a7-a03f-4239592f3c21'
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
            ReportId = $identifier
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        Add-RudderVar -Name 'report_data' -Value @{
          component_name = $reportParams['ComponentName']
          component_key = $reportParams['ComponentKey']
          report_id_r = 'df06e919-02b7-41a7-a03f-4239592f3c21'
          report_id = 'df06e919_02b7_41a7_a03f_4239592f3c21'
          result_id = $resultId
          identifier = $identifier
        }
        
        $methodParams = @{
            Architecture = ''
            Name = @'
ntp
'@
            Provider = ''
            Version = @'

'@
            
            PolicyMode = $policyMode
        }
        $call = Package-Present @methodParams
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

    $identifier=$reportIdBase + 'd76686bb-79ab-4ae5-b45f-108492ab4101'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-d76686bb-79ab-4ae5-b45f-108492ab4101'
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
            ReportId = $identifier
            DisableReporting = $true
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        Add-RudderVar -Name 'report_data' -Value @{
          component_name = $reportParams['ComponentName']
          component_key = $reportParams['ComponentKey']
          report_id_r = 'd76686bb-79ab-4ae5-b45f-108492ab4101'
          report_id = 'd76686bb_79ab_4ae5_b45f_108492ab4101'
          result_id = $resultId
          identifier = $identifier
        }
        
        $methodParams = @{
            Architecture = ''
            Name = @'
ntp
'@
            Provider = ''
            Version = @'

'@
            
            PolicyMode = $policyMode
        }
        $call = Package-Present @methodParams
        Compute-Method-Call @reportParams -MethodCall $call
        
    } catch [Nustache.Core.NustacheDataContextMissException], [Nustache.Core.NustacheException] {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                $_.ToString()
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $identifier -DisableReporting:$true -MethodCall $failedCall -ResultId $resultId
    } catch {
        [Rudder.Logger]::Log.Debug($_)
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $identifier -DisableReporting:$true -MethodCall $failedCall -ResultId $resultId
    }

    EndTechniqueCall -Name $techniqueName
}