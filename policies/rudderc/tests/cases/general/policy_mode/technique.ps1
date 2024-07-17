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

    $fallBackReportParams = @{
        ClassPrefix = 'skipped_method'
        ComponentKey = 'None'
        ComponentName = 'None'
        TechniqueName = $techniqueName
    }


    $reportId=$reportIdBase + "46b8025a-0b06-485c-9127-50e4258ee7e6"
    $componentKey = '/tmp/1'
    $reportParams = try {
        @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_lines_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = 'In audit mode'
            PolicyMode = ([Rudder.PolicyMode]::Audit)
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
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
        
    } catch [Nustache.Core.NustacheDataContextMissException] {
        $failedCall = New-Object -TypeName "Rudder.MethodResult" -ArgumentList @(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Audit) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall
    } catch {
        $failedCall = New-Object -TypeName "Rudder.MethodResult" -ArgumentList @(
            [Rudder.MethodStatus]::Error,
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Audit) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall
    }

    $reportId=$reportIdBase + "1eedce7b-3441-4251-bdd6-706fda3ec7a8"
    $componentKey = '/tmp/1'
    $reportParams = try {
        @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_lines_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = 'In omit mode'
            PolicyMode = $policyMode
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
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

    $reportId=$reportIdBase + "dbd5ba50-8dfc-11ee-a57e-84a938c470d4"
    $componentKey = '/tmp/1'
    $reportParams = try {
        @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_lines_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = 'In enforce mode'
            PolicyMode = ([Rudder.PolicyMode]::Enforce)
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
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
        
    } catch [Nustache.Core.NustacheDataContextMissException] {
        $failedCall = New-Object -TypeName "Rudder.MethodResult" -ArgumentList @(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Enforce) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall
    } catch {
        $failedCall = New-Object -TypeName "Rudder.MethodResult" -ArgumentList @(
            [Rudder.MethodStatus]::Error,
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Enforce) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall
    }

    $reportId=$reportIdBase + "1d809592-808e-4177-8351-8b7b7769af69"
    $componentKey = '/tmp/1'
    $reportParams = try {
        @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_lines_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = 'In default mode'
            PolicyMode = $policyMode
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
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

    $reportId=$reportIdBase + "ea274579-40fc-4545-b384-8d5576a7c69b"
    $componentKey = '/tmp/1'
    $reportParams = try {
        @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_lines_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = 'Resolve to audit'
            PolicyMode = ([Rudder.PolicyMode]::Audit)
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
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
        
    } catch [Nustache.Core.NustacheDataContextMissException] {
        $failedCall = New-Object -TypeName "Rudder.MethodResult" -ArgumentList @(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Audit) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall
    } catch {
        $failedCall = New-Object -TypeName "Rudder.MethodResult" -ArgumentList @(
            [Rudder.MethodStatus]::Error,
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Audit) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall
    }

    $reportId=$reportIdBase + "85659b7e-968c-458c-b566-c90108c50833"
    $componentKey = '/tmp/1'
    $reportParams = try {
        @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_lines_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = 'Resolve to enforce'
            PolicyMode = ([Rudder.PolicyMode]::Enforce)
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
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
        
    } catch [Nustache.Core.NustacheDataContextMissException] {
        $failedCall = New-Object -TypeName "Rudder.MethodResult" -ArgumentList @(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Enforce) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall
    } catch {
        $failedCall = New-Object -TypeName "Rudder.MethodResult" -ArgumentList @(
            [Rudder.MethodStatus]::Error,
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Enforce) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall
    }

    $reportId=$reportIdBase + "d8def455-cd43-441f-8dba-1ebae3a29389"
    $componentKey = '/tmp/1'
    $reportParams = try {
        @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_lines_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = 'Resolve to audit'
            PolicyMode = ([Rudder.PolicyMode]::Audit)
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
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
        
    } catch [Nustache.Core.NustacheDataContextMissException] {
        $failedCall = New-Object -TypeName "Rudder.MethodResult" -ArgumentList @(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Audit) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall
    } catch {
        $failedCall = New-Object -TypeName "Rudder.MethodResult" -ArgumentList @(
            [Rudder.MethodStatus]::Error,
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Audit) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall
    }

    $reportId=$reportIdBase + "f9417d97-3a18-4db6-85c3-72e28618bff1"
    $componentKey = '/tmp/1'
    $reportParams = try {
        @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_lines_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = 'Resolve to audit'
            PolicyMode = ([Rudder.PolicyMode]::Audit)
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
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
        
    } catch [Nustache.Core.NustacheDataContextMissException] {
        $failedCall = New-Object -TypeName "Rudder.MethodResult" -ArgumentList @(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Audit) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall
    } catch {
        $failedCall = New-Object -TypeName "Rudder.MethodResult" -ArgumentList @(
            [Rudder.MethodStatus]::Error,
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Audit) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall
    }

    $reportId=$reportIdBase + "c4b4faa1-85e5-4922-b713-c198bf99226e"
    $componentKey = '/tmp/1'
    $reportParams = try {
        @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_lines_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = 'Resolve to enforce'
            PolicyMode = ([Rudder.PolicyMode]::Enforce)
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
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
        
    } catch [Nustache.Core.NustacheDataContextMissException] {
        $failedCall = New-Object -TypeName "Rudder.MethodResult" -ArgumentList @(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Enforce) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall
    } catch {
        $failedCall = New-Object -TypeName "Rudder.MethodResult" -ArgumentList @(
            [Rudder.MethodStatus]::Error,
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Enforce) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall
    }

    $reportId=$reportIdBase + "cce62a59-bd17-4858-ba06-6ae41f39b15a"
    $componentKey = '/tmp/1'
    $reportParams = try {
        @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_lines_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = 'Resolve to enforce'
            PolicyMode = ([Rudder.PolicyMode]::Enforce)
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
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
        
    } catch [Nustache.Core.NustacheDataContextMissException] {
        $failedCall = New-Object -TypeName "Rudder.MethodResult" -ArgumentList @(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Enforce) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall
    } catch {
        $failedCall = New-Object -TypeName "Rudder.MethodResult" -ArgumentList @(
            [Rudder.MethodStatus]::Error,
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Enforce) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall
    }

    $reportId=$reportIdBase + "0a4299dd-0902-48b2-85ee-13dfe6fc3af6"
    $componentKey = '/tmp/1'
    $reportParams = try {
        @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_lines_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = 'Resolve to audit'
            PolicyMode = ([Rudder.PolicyMode]::Audit)
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
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
        
    } catch [Nustache.Core.NustacheDataContextMissException] {
        $failedCall = New-Object -TypeName "Rudder.MethodResult" -ArgumentList @(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Audit) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall
    } catch {
        $failedCall = New-Object -TypeName "Rudder.MethodResult" -ArgumentList @(
            [Rudder.MethodStatus]::Error,
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Audit) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall
    }

    $reportId=$reportIdBase + "3b8352df-1329-4956-a019-bb9c072bc830"
    $componentKey = '/tmp/1'
    $reportParams = try {
        @{
            ClassPrefix = ([Rudder.Condition]::canonify(("file_lines_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = 'Resolve to enforce'
            PolicyMode = ([Rudder.PolicyMode]::Enforce)
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
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
        
    } catch [Nustache.Core.NustacheDataContextMissException] {
        $failedCall = New-Object -TypeName "Rudder.MethodResult" -ArgumentList @(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Enforce) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall
    } catch {
        $failedCall = New-Object -TypeName "Rudder.MethodResult" -ArgumentList @(
            [Rudder.MethodStatus]::Error,
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode ([Rudder.PolicyMode]::Enforce) -ReportId $reportId -DisableReporting:$false -MethodCall $failedCall
    }

    EndTechniqueCall -Name $techniqueName
}