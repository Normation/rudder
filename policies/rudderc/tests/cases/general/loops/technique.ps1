function Technique-Technique-With-Loops {
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


    $reportId=$reportIdBase + "d86ce2e5-d5b6-45cc-87e8-c11cca71d907-0"
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
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            MethodId = 'd86ce2e5-d5b6-45cc-87e8-c11cca71d907-0'
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
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'd86ce2e5-d5b6-45cc-87e8-c11cca71d907-0' -MethodCall $failedCall
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'd86ce2e5-d5b6-45cc-87e8-c11cca71d907-0' -MethodCall $failedCall
    }

    $reportId=$reportIdBase + "d86ce2e5-d5b6-45cc-87e8-c11cca71d907-1"
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
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            MethodId = 'd86ce2e5-d5b6-45cc-87e8-c11cca71d907-1'
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
        
    } catch [Nustache.Core.NustacheDataContextMissException], [Nustache.Core.NustacheException] {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped because it references an undefined variable "{0}"',
                $_.ToString()
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'd86ce2e5-d5b6-45cc-87e8-c11cca71d907-1' -MethodCall $failedCall
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'd86ce2e5-d5b6-45cc-87e8-c11cca71d907-1' -MethodCall $failedCall
    }

    $reportId=$reportIdBase + "b461df26-f0b8-44ec-b3b9-6bb278e0f3a5-0-0"
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
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            MethodId = 'b461df26-f0b8-44ec-b3b9-6bb278e0f3a5-0-0'
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
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'b461df26-f0b8-44ec-b3b9-6bb278e0f3a5-0-0' -MethodCall $failedCall
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'b461df26-f0b8-44ec-b3b9-6bb278e0f3a5-0-0' -MethodCall $failedCall
    }

    $reportId=$reportIdBase + "b461df26-f0b8-44ec-b3b9-6bb278e0f3a5-0-1"
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
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            MethodId = 'b461df26-f0b8-44ec-b3b9-6bb278e0f3a5-0-1'
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
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'b461df26-f0b8-44ec-b3b9-6bb278e0f3a5-0-1' -MethodCall $failedCall
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'b461df26-f0b8-44ec-b3b9-6bb278e0f3a5-0-1' -MethodCall $failedCall
    }

    $reportId=$reportIdBase + "20676b22-2de2-4029-a4e2-e0be2453e78e-0-0"
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
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            MethodId = '20676b22-2de2-4029-a4e2-e0be2453e78e-0-0'
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
                
            }
            $call = File-From-Shared-Folder @methodParams -PolicyMode $policyMode
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
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId '20676b22-2de2-4029-a4e2-e0be2453e78e-0-0' -MethodCall $failedCall
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId '20676b22-2de2-4029-a4e2-e0be2453e78e-0-0' -MethodCall $failedCall
    }

    $reportId=$reportIdBase + "20676b22-2de2-4029-a4e2-e0be2453e78e-0-1"
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
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            MethodId = '20676b22-2de2-4029-a4e2-e0be2453e78e-0-1'
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
                
            }
            $call = File-From-Shared-Folder @methodParams -PolicyMode $policyMode
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
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId '20676b22-2de2-4029-a4e2-e0be2453e78e-0-1' -MethodCall $failedCall
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId '20676b22-2de2-4029-a4e2-e0be2453e78e-0-1' -MethodCall $failedCall
    }

    $reportId=$reportIdBase + "b461df26-f0b8-44ec-b3b9-6bb278e0f3a5-1-0"
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
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            MethodId = 'b461df26-f0b8-44ec-b3b9-6bb278e0f3a5-1-0'
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
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'b461df26-f0b8-44ec-b3b9-6bb278e0f3a5-1-0' -MethodCall $failedCall
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'b461df26-f0b8-44ec-b3b9-6bb278e0f3a5-1-0' -MethodCall $failedCall
    }

    $reportId=$reportIdBase + "b461df26-f0b8-44ec-b3b9-6bb278e0f3a5-1-1"
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
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            MethodId = 'b461df26-f0b8-44ec-b3b9-6bb278e0f3a5-1-1'
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
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'b461df26-f0b8-44ec-b3b9-6bb278e0f3a5-1-1' -MethodCall $failedCall
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId 'b461df26-f0b8-44ec-b3b9-6bb278e0f3a5-1-1' -MethodCall $failedCall
    }

    $reportId=$reportIdBase + "20676b22-2de2-4029-a4e2-e0be2453e78e-1-0"
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
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            MethodId = '20676b22-2de2-4029-a4e2-e0be2453e78e-1-0'
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
                
            }
            $call = File-From-Shared-Folder @methodParams -PolicyMode $policyMode
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
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId '20676b22-2de2-4029-a4e2-e0be2453e78e-1-0' -MethodCall $failedCall
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId '20676b22-2de2-4029-a4e2-e0be2453e78e-1-0' -MethodCall $failedCall
    }

    $reportId=$reportIdBase + "20676b22-2de2-4029-a4e2-e0be2453e78e-1-1"
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
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            MethodId = '20676b22-2de2-4029-a4e2-e0be2453e78e-1-1'
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
                
            }
            $call = File-From-Shared-Folder @methodParams -PolicyMode $policyMode
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
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId '20676b22-2de2-4029-a4e2-e0be2453e78e-1-1' -MethodCall $failedCall
    } catch {
        $failedCall = [Rudder.MethodResult]::Error(
            ([String]::Format(
                'The method call was skipped as an unexpected error was thrown "{0}"',
                (Format-Exception $_)[1]
            )),
            $techniqueName
        )
        Compute-Method-Call @fallBackReportParams -PolicyMode $policyMode -ReportId $reportId -DisableReporting:$false -MethodId '20676b22-2de2-4029-a4e2-e0be2453e78e-1-1' -MethodCall $failedCall
    }

    EndTechniqueCall -Name $techniqueName
}