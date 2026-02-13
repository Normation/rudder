function Technique-Escaping {
    [CmdletBinding()]
    param (
        [parameter(Mandatory = $true)]
        [string]$reportId,
        [parameter(Mandatory = $true)]
        [string]$techniqueName,

        [parameter(Mandatory = $true)]
        [string]$server,
        [Rudder.PolicyMode]$policyMode
    )
    $techniqueParams = @{

        "server" = $server
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


    $reportId=$reportIdBase + "a86ce2e5-d5b6-45cc-87e8-c11cca71d966"
    $resultId=$directiveId + '-' + "a86ce2e5-d5b6-45cc-87e8-c11cca71d966"
    try {
        $componentKey = ([Rudder.Datastate]::Render('{{{' + @'
vars.sys.host
'@ + '}}}')) + @'
 . | / 
'@ + ([Rudder.Datastate]::Render('{{{' + @'
vars.sys.
'@ + [Rudder.Datastate]::Render('{{{' + @'
vars.host
'@ + '}}}') + '}}}')) + @'
 ' '' ''' $ $$ " "" \ \\😋aà3
	
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("package_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = ([Rudder.Datastate]::Render('{{{' + @'
vars.sys.host
'@ + '}}}')) + @'
 . | / 
'@ + ([Rudder.Datastate]::Render('{{{' + @'
vars.sys.
'@ + [Rudder.Datastate]::Render('{{{' + @'
vars.host
'@ + '}}}') + '}}}')) + @'
 ' '' ''' $ $$ " "" \ \\😋aà3
	
'@
            PolicyMode = $policyMode
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        
        $class = (([Rudder.Condition]::Canonify([Rudder.Datastate]::Render('{{{' + @'
vars.my_cond
'@ + '}}}'))) + '.debian|' + ([Rudder.Condition]::Canonify([Rudder.Datastate]::Render('{{{' + @'
vars.sys.
'@ + [Rudder.Datastate]::Render('{{{' + @'
vars.plouf
'@ + '}}}') + '}}}'))))
        if ([Rudder.Datastate]::Evaluate($class)) {
            $methodParams = @{
                Architecture = ''
                Name = ([Rudder.Datastate]::Render('{{{' + @'
vars.sys.host
'@ + '}}}')) + @'
 . | / 
'@ + ([Rudder.Datastate]::Render('{{{' + @'
vars.sys.
'@ + [Rudder.Datastate]::Render('{{{' + @'
vars.host
'@ + '}}}') + '}}}')) + @'
 ' '' ''' $ $$ " "" \ \\😋aà3
	
'@
                Provider = ''
                Version = @'
if(Get-Service "Zabbix agent") { write-output "exists" }
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

    $reportId=$reportIdBase + "a86ce2e5-d5b6-45cc-87e8-c11cca71d977"
    $resultId=$directiveId + '-' + "a86ce2e5-d5b6-45cc-87e8-c11cca71d977"
    try {
        $componentKey = ([Rudder.Datastate]::Render('{{{' + @'
vars.sys.host
'@ + '}}}')) + @'
 . | / 
'@ + ([Rudder.Datastate]::Render('{{{' + @'
vars.sys.
'@ + [Rudder.Datastate]::Render('{{{' + @'
vars.host
'@ + '}}}') + '}}}')) + @'
 ' '' ''' $ $$ " "" \ \\😋aà3
	
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("package_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = ([Rudder.Datastate]::Render('{{{' + @'
vars.sys.host
'@ + '}}}')) + @'
 . | / 
'@ + ([Rudder.Datastate]::Render('{{{' + @'
vars.sys.
'@ + [Rudder.Datastate]::Render('{{{' + @'
vars.host
'@ + '}}}') + '}}}')) + @'
 ' '' ''' $ $$ " "" \ \\😋aà3
	
'@
            PolicyMode = $policyMode
            ReportId = $reportId
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        
        $methodParams = @{
            Architecture = ''
            Name = ([Rudder.Datastate]::Render('{{{' + @'
vars.sys.host
'@ + '}}}')) + @'
 . | / 
'@ + ([Rudder.Datastate]::Render('{{{' + @'
vars.sys.
'@ + [Rudder.Datastate]::Render('{{{' + @'
vars.host
'@ + '}}}') + '}}}')) + @'
 ' '' ''' $ $$ " "" \ \\😋aà3
	
'@
            Provider = ''
            Version = @'
if(Get-Service "Zabbix agent") { write-output "exists" }
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

    $reportId=$reportIdBase + "a86ce2e5-d5b6-45cc-87e8-c11cca71d978"
    $resultId=$directiveId + '-' + "a86ce2e5-d5b6-45cc-87e8-c11cca71d978"
    try {
        $componentKey = @'
cache_prefix="zapache-$UID-
'@ + @'
${STATUS_URL//[^a-zA-Z0-9_-]/_}" 
'@
        $reportParams = @{
            ClassPrefix = ([Rudder.Condition]::canonify(("package_present_" + $componentKey)))
            ComponentKey = $componentKey
            ComponentName = @'
cache_prefix="zapache-$UID-
'@ + @'
${STATUS_URL//[^a-zA-Z0-9_-]/_}" 
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
cache_prefix="zapache-$UID-
'@ + @'
${STATUS_URL//[^a-zA-Z0-9_-]/_}" 
'@
            Provider = ''
            Version = @'
plop
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

    EndTechniqueCall -Name $techniqueName
}