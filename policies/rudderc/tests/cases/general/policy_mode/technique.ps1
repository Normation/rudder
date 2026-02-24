function Technique-Test-Audit {
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


    $identifier=$reportIdBase + '46b8025a-0b06-485c-9127-50e4258ee7e6'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-46b8025a-0b06-485c-9127-50e4258ee7e6'
    try {
        $componentKey = @'
/tmp/1
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_lines_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
In audit mode
'@
            PolicyMode = ([Rudder.PolicyMode]::Audit)
            ReportId = $identifier
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        Add-RudderVar -Name 'report_data' -Value @{
          component_name = $reportParams['ComponentName']
          component_key = $reportParams['ComponentKey']
          report_id_r = '46b8025a-0b06-485c-9127-50e4258ee7e6'
          report_id = '46b8025a_0b06_485c_9127_50e4258ee7e6'
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
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Audit) -ReportId $identifier -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    } catch {
        [Rudder.Logger]::Log.Debug($_)
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Audit) -ReportId $identifier -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    }

    $identifier=$reportIdBase + '1eedce7b-3441-4251-bdd6-706fda3ec7a8'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-1eedce7b-3441-4251-bdd6-706fda3ec7a8'
    try {
        $componentKey = @'
/tmp/1
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_lines_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
In omit mode
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
          report_id_r = '1eedce7b-3441-4251-bdd6-706fda3ec7a8'
          report_id = '1eedce7b_3441_4251_bdd6_706fda3ec7a8'
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

    $identifier=$reportIdBase + 'dbd5ba50-8dfc-11ee-a57e-84a938c470d4'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-dbd5ba50-8dfc-11ee-a57e-84a938c470d4'
    try {
        $componentKey = @'
/tmp/1
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_lines_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
In enforce mode
'@
            PolicyMode = ([Rudder.PolicyMode]::Enforce)
            ReportId = $identifier
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        Add-RudderVar -Name 'report_data' -Value @{
          component_name = $reportParams['ComponentName']
          component_key = $reportParams['ComponentKey']
          report_id_r = 'dbd5ba50-8dfc-11ee-a57e-84a938c470d4'
          report_id = 'dbd5ba50_8dfc_11ee_a57e_84a938c470d4'
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
            
            PolicyMode = ([Rudder.PolicyMode]::Enforce)
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
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Enforce) -ReportId $identifier -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    } catch {
        [Rudder.Logger]::Log.Debug($_)
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Enforce) -ReportId $identifier -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    }

    $identifier=$reportIdBase + '1d809592-808e-4177-8351-8b7b7769af69'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-1d809592-808e-4177-8351-8b7b7769af69'
    try {
        $componentKey = @'
/tmp/1
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_lines_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
In default mode
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
          report_id_r = '1d809592-808e-4177-8351-8b7b7769af69'
          report_id = '1d809592_808e_4177_8351_8b7b7769af69'
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

    $identifier=$reportIdBase + 'ea274579-40fc-4545-b384-8d5576a7c69b'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-ea274579-40fc-4545-b384-8d5576a7c69b'
    try {
        $componentKey = @'
/tmp/1
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_lines_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
Resolve to audit
'@
            PolicyMode = ([Rudder.PolicyMode]::Audit)
            ReportId = $identifier
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        Add-RudderVar -Name 'report_data' -Value @{
          component_name = $reportParams['ComponentName']
          component_key = $reportParams['ComponentKey']
          report_id_r = 'ea274579-40fc-4545-b384-8d5576a7c69b'
          report_id = 'ea274579_40fc_4545_b384_8d5576a7c69b'
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
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Audit) -ReportId $identifier -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    } catch {
        [Rudder.Logger]::Log.Debug($_)
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Audit) -ReportId $identifier -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    }

    $identifier=$reportIdBase + '85659b7e-968c-458c-b566-c90108c50833'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-85659b7e-968c-458c-b566-c90108c50833'
    try {
        $componentKey = @'
/tmp/1
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_lines_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
Resolve to enforce
'@
            PolicyMode = ([Rudder.PolicyMode]::Enforce)
            ReportId = $identifier
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        Add-RudderVar -Name 'report_data' -Value @{
          component_name = $reportParams['ComponentName']
          component_key = $reportParams['ComponentKey']
          report_id_r = '85659b7e-968c-458c-b566-c90108c50833'
          report_id = '85659b7e_968c_458c_b566_c90108c50833'
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
            
            PolicyMode = ([Rudder.PolicyMode]::Enforce)
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
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Enforce) -ReportId $identifier -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    } catch {
        [Rudder.Logger]::Log.Debug($_)
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Enforce) -ReportId $identifier -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    }

    $identifier=$reportIdBase + 'd8def455-cd43-441f-8dba-1ebae3a29389'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-d8def455-cd43-441f-8dba-1ebae3a29389'
    try {
        $componentKey = @'
/tmp/1
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_lines_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
Resolve to audit
'@
            PolicyMode = ([Rudder.PolicyMode]::Audit)
            ReportId = $identifier
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        Add-RudderVar -Name 'report_data' -Value @{
          component_name = $reportParams['ComponentName']
          component_key = $reportParams['ComponentKey']
          report_id_r = 'd8def455-cd43-441f-8dba-1ebae3a29389'
          report_id = 'd8def455_cd43_441f_8dba_1ebae3a29389'
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
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Audit) -ReportId $identifier -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    } catch {
        [Rudder.Logger]::Log.Debug($_)
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Audit) -ReportId $identifier -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    }

    $identifier=$reportIdBase + 'f9417d97-3a18-4db6-85c3-72e28618bff1'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-f9417d97-3a18-4db6-85c3-72e28618bff1'
    try {
        $componentKey = @'
/tmp/1
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_lines_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
Resolve to audit
'@
            PolicyMode = ([Rudder.PolicyMode]::Audit)
            ReportId = $identifier
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        Add-RudderVar -Name 'report_data' -Value @{
          component_name = $reportParams['ComponentName']
          component_key = $reportParams['ComponentKey']
          report_id_r = 'f9417d97-3a18-4db6-85c3-72e28618bff1'
          report_id = 'f9417d97_3a18_4db6_85c3_72e28618bff1'
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
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Audit) -ReportId $identifier -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    } catch {
        [Rudder.Logger]::Log.Debug($_)
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Audit) -ReportId $identifier -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    }

    $identifier=$reportIdBase + 'c4b4faa1-85e5-4922-b713-c198bf99226e'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-c4b4faa1-85e5-4922-b713-c198bf99226e'
    try {
        $componentKey = @'
/tmp/1
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_lines_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
Resolve to enforce
'@
            PolicyMode = ([Rudder.PolicyMode]::Enforce)
            ReportId = $identifier
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        Add-RudderVar -Name 'report_data' -Value @{
          component_name = $reportParams['ComponentName']
          component_key = $reportParams['ComponentKey']
          report_id_r = 'c4b4faa1-85e5-4922-b713-c198bf99226e'
          report_id = 'c4b4faa1_85e5_4922_b713_c198bf99226e'
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
            
            PolicyMode = ([Rudder.PolicyMode]::Enforce)
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
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Enforce) -ReportId $identifier -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    } catch {
        [Rudder.Logger]::Log.Debug($_)
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Enforce) -ReportId $identifier -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    }

    $identifier=$reportIdBase + 'cce62a59-bd17-4858-ba06-6ae41f39b15a'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-cce62a59-bd17-4858-ba06-6ae41f39b15a'
    try {
        $componentKey = @'
/tmp/1
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_lines_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
Resolve to enforce
'@
            PolicyMode = ([Rudder.PolicyMode]::Enforce)
            ReportId = $identifier
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        Add-RudderVar -Name 'report_data' -Value @{
          component_name = $reportParams['ComponentName']
          component_key = $reportParams['ComponentKey']
          report_id_r = 'cce62a59-bd17-4858-ba06-6ae41f39b15a'
          report_id = 'cce62a59_bd17_4858_ba06_6ae41f39b15a'
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
            
            PolicyMode = ([Rudder.PolicyMode]::Enforce)
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
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Enforce) -ReportId $identifier -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    } catch {
        [Rudder.Logger]::Log.Debug($_)
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Enforce) -ReportId $identifier -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    }

    $identifier=$reportIdBase + '0a4299dd-0902-48b2-85ee-13dfe6fc3af6'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-0a4299dd-0902-48b2-85ee-13dfe6fc3af6'
    try {
        $componentKey = @'
/tmp/1
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_lines_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
Resolve to audit
'@
            PolicyMode = ([Rudder.PolicyMode]::Audit)
            ReportId = $identifier
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        Add-RudderVar -Name 'report_data' -Value @{
          component_name = $reportParams['ComponentName']
          component_key = $reportParams['ComponentKey']
          report_id_r = '0a4299dd-0902-48b2-85ee-13dfe6fc3af6'
          report_id = '0a4299dd_0902_48b2_85ee_13dfe6fc3af6'
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
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Audit) -ReportId $identifier -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    } catch {
        [Rudder.Logger]::Log.Debug($_)
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Audit) -ReportId $identifier -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    }

    $identifier=$reportIdBase + '3b8352df-1329-4956-a019-bb9c072bc830'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-3b8352df-1329-4956-a019-bb9c072bc830'
    try {
        $componentKey = @'
/tmp/1
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_lines_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
Resolve to enforce
'@
            PolicyMode = ([Rudder.PolicyMode]::Enforce)
            ReportId = $identifier
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        Add-RudderVar -Name 'report_data' -Value @{
          component_name = $reportParams['ComponentName']
          component_key = $reportParams['ComponentKey']
          report_id_r = '3b8352df-1329-4956-a019-bb9c072bc830'
          report_id = '3b8352df_1329_4956_a019_bb9c072bc830'
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
            
            PolicyMode = ([Rudder.PolicyMode]::Enforce)
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
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Enforce) -ReportId $identifier -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    } catch {
        [Rudder.Logger]::Log.Debug($_)
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Enforce) -ReportId $identifier -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    }

    EndTechniqueCall -Name $techniqueName
}