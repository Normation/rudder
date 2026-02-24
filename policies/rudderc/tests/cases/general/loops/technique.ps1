function Technique-Technique-With-Loops {
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


    $identifier=$reportIdBase + 'd86ce2e5-d5b6-45cc-87e8-c11cca71d907-0'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-d86ce2e5-d5b6-45cc-87e8-c11cca71d907-0'
    try {
        $componentKey = @'
vim
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("package_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
Install a package
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
          report_id_r = 'd86ce2e5-d5b6-45cc-87e8-c11cca71d907-0'
          report_id = 'd86ce2e5_d5b6_45cc_87e8_c11cca71d907_0'
          result_id = $resultId
          identifier = $identifier
        }
        
        $class = "false"
        if ([Rudder.Datastate]::Evaluate($class)) {
            $methodParams = @{
                Architecture = ''
                Name = @'
vim
'@
                Provider = ''
                Version = @'
latest
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

    $identifier=$reportIdBase + 'd86ce2e5-d5b6-45cc-87e8-c11cca71d907-1'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-d86ce2e5-d5b6-45cc-87e8-c11cca71d907-1'
    try {
        $componentKey = @'
htop
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("package_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
Install a package
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
          report_id_r = 'd86ce2e5-d5b6-45cc-87e8-c11cca71d907-1'
          report_id = 'd86ce2e5_d5b6_45cc_87e8_c11cca71d907_1'
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
2.3.4
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

    $identifier=$reportIdBase + 'b461df26-f0b8-44ec-b3b9-6bb278e0f3a5-0-0'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-b461df26-f0b8-44ec-b3b9-6bb278e0f3a5-0-0'
    try {
        $componentKey = @'
bob
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("user_group_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
Create home
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
          report_id_r = 'b461df26-f0b8-44ec-b3b9-6bb278e0f3a5-0-0'
          report_id = 'b461df26_f0b8_44ec_b3b9_6bb278e0f3a5_0_0'
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

    $identifier=$reportIdBase + 'b461df26-f0b8-44ec-b3b9-6bb278e0f3a5-0-1'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-b461df26-f0b8-44ec-b3b9-6bb278e0f3a5-0-1'
    try {
        $componentKey = @'
bob
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("user_group_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
Create home
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
          report_id_r = 'b461df26-f0b8-44ec-b3b9-6bb278e0f3a5-0-1'
          report_id = 'b461df26_f0b8_44ec_b3b9_6bb278e0f3a5_0_1'
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

    $identifier=$reportIdBase + '20676b22-2de2-4029-a4e2-e0be2453e78e-0-0'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-20676b22-2de2-4029-a4e2-e0be2453e78e-0-0'
    try {
        $componentKey = @'
/home/bob/.vimrc
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_from_shared_folder_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
Deploy file ~/.vimrc
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
          report_id_r = '20676b22-2de2-4029-a4e2-e0be2453e78e-0-0'
          report_id = '20676b22_2de2_4029_a4e2_e0be2453e78e_0_0'
          result_id = $resultId
          identifier = $identifier
        }
        
        $class = "bob"
        if ([Rudder.Datastate]::Evaluate($class)) {
            $methodParams = @{
                HashType = @'
sha256
'@
                Path = @'
/home/bob/.vimrc
'@
                Source = @'
.vimrc
'@
                
                PolicyMode = $policyMode
            }
            $call = File-From-Shared-Folder @methodParams
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

    $identifier=$reportIdBase + '20676b22-2de2-4029-a4e2-e0be2453e78e-0-1'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-20676b22-2de2-4029-a4e2-e0be2453e78e-0-1'
    try {
        $componentKey = @'
/home/bob/.bashrc
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_from_shared_folder_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
Deploy file ~/.bashrc
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
          report_id_r = '20676b22-2de2-4029-a4e2-e0be2453e78e-0-1'
          report_id = '20676b22_2de2_4029_a4e2_e0be2453e78e_0_1'
          result_id = $resultId
          identifier = $identifier
        }
        
        $class = "bob"
        if ([Rudder.Datastate]::Evaluate($class)) {
            $methodParams = @{
                HashType = @'
sha256
'@
                Path = @'
/home/bob/.bashrc
'@
                Source = @'
.bashrc
'@
                
                PolicyMode = $policyMode
            }
            $call = File-From-Shared-Folder @methodParams
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

    $identifier=$reportIdBase + 'b461df26-f0b8-44ec-b3b9-6bb278e0f3a5-1-0'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-b461df26-f0b8-44ec-b3b9-6bb278e0f3a5-1-0'
    try {
        $componentKey = @'
alice
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("user_group_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
Create home
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
          report_id_r = 'b461df26-f0b8-44ec-b3b9-6bb278e0f3a5-1-0'
          report_id = 'b461df26_f0b8_44ec_b3b9_6bb278e0f3a5_1_0'
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

    $identifier=$reportIdBase + 'b461df26-f0b8-44ec-b3b9-6bb278e0f3a5-1-1'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-b461df26-f0b8-44ec-b3b9-6bb278e0f3a5-1-1'
    try {
        $componentKey = @'
alice
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("user_group_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
Create home
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
          report_id_r = 'b461df26-f0b8-44ec-b3b9-6bb278e0f3a5-1-1'
          report_id = 'b461df26_f0b8_44ec_b3b9_6bb278e0f3a5_1_1'
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

    $identifier=$reportIdBase + '20676b22-2de2-4029-a4e2-e0be2453e78e-1-0'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-20676b22-2de2-4029-a4e2-e0be2453e78e-1-0'
    try {
        $componentKey = @'
/home/alice/.vimrc
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_from_shared_folder_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
Deploy file ~/.vimrc
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
          report_id_r = '20676b22-2de2-4029-a4e2-e0be2453e78e-1-0'
          report_id = '20676b22_2de2_4029_a4e2_e0be2453e78e_1_0'
          result_id = $resultId
          identifier = $identifier
        }
        
        $class = "alice"
        if ([Rudder.Datastate]::Evaluate($class)) {
            $methodParams = @{
                HashType = @'
sha256
'@
                Path = @'
/home/alice/.vimrc
'@
                Source = @'
.vimrc
'@
                
                PolicyMode = $policyMode
            }
            $call = File-From-Shared-Folder @methodParams
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

    $identifier=$reportIdBase + '20676b22-2de2-4029-a4e2-e0be2453e78e-1-1'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-20676b22-2de2-4029-a4e2-e0be2453e78e-1-1'
    try {
        $componentKey = @'
/home/alice/.bashrc
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_from_shared_folder_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
Deploy file ~/.bashrc
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
          report_id_r = '20676b22-2de2-4029-a4e2-e0be2453e78e-1-1'
          report_id = '20676b22_2de2_4029_a4e2_e0be2453e78e_1_1'
          result_id = $resultId
          identifier = $identifier
        }
        
        $class = "alice"
        if ([Rudder.Datastate]::Evaluate($class)) {
            $methodParams = @{
                HashType = @'
sha256
'@
                Path = @'
/home/alice/.bashrc
'@
                Source = @'
.bashrc
'@
                
                PolicyMode = $policyMode
            }
            $call = File-From-Shared-Folder @methodParams
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