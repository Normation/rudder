function Technique-Test-Audit {
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


    $reportId=$reportIdBase + "46b8025a-0b06-485c-9127-50e4258ee7e6"
    $resultId=$directiveId + '-' + "46b8025a-0b06-485c-9127-50e4258ee7e6"
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
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
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
            
        }
        $call = File-Content @methodParams -PolicyMode ([Rudder.PolicyMode]::Audit)
        Compute-Method-Call @reportParams -MethodCall $call
        
    } catch [Nustache.Core.NustacheDataContextMissException], [Nustache.Core.NustacheException] {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                $_.ToString()
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Audit) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Audit) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    }

    $reportId=$reportIdBase + "1eedce7b-3441-4251-bdd6-706fda3ec7a8"
    $resultId=$directiveId + '-' + "1eedce7b-3441-4251-bdd6-706fda3ec7a8"
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
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
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

    $reportId=$reportIdBase + "dbd5ba50-8dfc-11ee-a57e-84a938c470d4"
    $resultId=$directiveId + '-' + "dbd5ba50-8dfc-11ee-a57e-84a938c470d4"
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
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
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
            
        }
        $call = File-Content @methodParams -PolicyMode ([Rudder.PolicyMode]::Enforce)
        Compute-Method-Call @reportParams -MethodCall $call
        
    } catch [Nustache.Core.NustacheDataContextMissException], [Nustache.Core.NustacheException] {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                $_.ToString()
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Enforce) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Enforce) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    }

    $reportId=$reportIdBase + "1d809592-808e-4177-8351-8b7b7769af69"
    $resultId=$directiveId + '-' + "1d809592-808e-4177-8351-8b7b7769af69"
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
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
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

    $reportId=$reportIdBase + "ea274579-40fc-4545-b384-8d5576a7c69b"
    $resultId=$directiveId + '-' + "ea274579-40fc-4545-b384-8d5576a7c69b"
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
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
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
            
        }
        $call = File-Content @methodParams -PolicyMode ([Rudder.PolicyMode]::Audit)
        Compute-Method-Call @reportParams -MethodCall $call
        
    } catch [Nustache.Core.NustacheDataContextMissException], [Nustache.Core.NustacheException] {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                $_.ToString()
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Audit) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Audit) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    }

    $reportId=$reportIdBase + "85659b7e-968c-458c-b566-c90108c50833"
    $resultId=$directiveId + '-' + "85659b7e-968c-458c-b566-c90108c50833"
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
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
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
            
        }
        $call = File-Content @methodParams -PolicyMode ([Rudder.PolicyMode]::Enforce)
        Compute-Method-Call @reportParams -MethodCall $call
        
    } catch [Nustache.Core.NustacheDataContextMissException], [Nustache.Core.NustacheException] {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                $_.ToString()
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Enforce) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Enforce) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    }

    $reportId=$reportIdBase + "d8def455-cd43-441f-8dba-1ebae3a29389"
    $resultId=$directiveId + '-' + "d8def455-cd43-441f-8dba-1ebae3a29389"
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
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
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
            
        }
        $call = File-Content @methodParams -PolicyMode ([Rudder.PolicyMode]::Audit)
        Compute-Method-Call @reportParams -MethodCall $call
        
    } catch [Nustache.Core.NustacheDataContextMissException], [Nustache.Core.NustacheException] {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                $_.ToString()
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Audit) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Audit) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    }

    $reportId=$reportIdBase + "f9417d97-3a18-4db6-85c3-72e28618bff1"
    $resultId=$directiveId + '-' + "f9417d97-3a18-4db6-85c3-72e28618bff1"
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
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
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
            
        }
        $call = File-Content @methodParams -PolicyMode ([Rudder.PolicyMode]::Audit)
        Compute-Method-Call @reportParams -MethodCall $call
        
    } catch [Nustache.Core.NustacheDataContextMissException], [Nustache.Core.NustacheException] {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                $_.ToString()
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Audit) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Audit) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    }

    $reportId=$reportIdBase + "c4b4faa1-85e5-4922-b713-c198bf99226e"
    $resultId=$directiveId + '-' + "c4b4faa1-85e5-4922-b713-c198bf99226e"
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
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
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
            
        }
        $call = File-Content @methodParams -PolicyMode ([Rudder.PolicyMode]::Enforce)
        Compute-Method-Call @reportParams -MethodCall $call
        
    } catch [Nustache.Core.NustacheDataContextMissException], [Nustache.Core.NustacheException] {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                $_.ToString()
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Enforce) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Enforce) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    }

    $reportId=$reportIdBase + "cce62a59-bd17-4858-ba06-6ae41f39b15a"
    $resultId=$directiveId + '-' + "cce62a59-bd17-4858-ba06-6ae41f39b15a"
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
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
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
            
        }
        $call = File-Content @methodParams -PolicyMode ([Rudder.PolicyMode]::Enforce)
        Compute-Method-Call @reportParams -MethodCall $call
        
    } catch [Nustache.Core.NustacheDataContextMissException], [Nustache.Core.NustacheException] {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                $_.ToString()
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Enforce) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Enforce) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    }

    $reportId=$reportIdBase + "0a4299dd-0902-48b2-85ee-13dfe6fc3af6"
    $resultId=$directiveId + '-' + "0a4299dd-0902-48b2-85ee-13dfe6fc3af6"
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
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
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
            
        }
        $call = File-Content @methodParams -PolicyMode ([Rudder.PolicyMode]::Audit)
        Compute-Method-Call @reportParams -MethodCall $call
        
    } catch [Nustache.Core.NustacheDataContextMissException], [Nustache.Core.NustacheException] {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                $_.ToString()
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Audit) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Audit) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    }

    $reportId=$reportIdBase + "3b8352df-1329-4956-a019-bb9c072bc830"
    $resultId=$directiveId + '-' + "3b8352df-1329-4956-a019-bb9c072bc830"
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
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
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
            
        }
        $call = File-Content @methodParams -PolicyMode ([Rudder.PolicyMode]::Enforce)
        Compute-Method-Call @reportParams -MethodCall $call
        
    } catch [Nustache.Core.NustacheDataContextMissException], [Nustache.Core.NustacheException] {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                $_.ToString()
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Enforce) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Enforce) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall -ResultId $resultId
    }

    EndTechniqueCall -Name $techniqueName
}