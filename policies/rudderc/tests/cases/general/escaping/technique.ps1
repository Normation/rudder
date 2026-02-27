function Technique-Escaping {
    [CmdletBinding()]
    param (
        [parameter(Mandatory = $true)]
        [Alias('reportId')]
        [string]$reportIdentifier,
        [parameter(Mandatory = $true)]
        [string]$techniqueName,

        [parameter(Mandatory = $true)]
        [string]$server,
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


    $identifier=$reportIdBase + 'a86ce2e5-d5b6-45cc-87e8-c11cca71d966'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-a86ce2e5-d5b6-45cc-87e8-c11cca71d966'
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
            ReportId = $identifier
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        Add-RudderVar -Name 'report_data' -Value @{
          component_name = $reportParams['ComponentName']
          component_key = $reportParams['ComponentKey']
          report_id_r = 'a86ce2e5-d5b6-45cc-87e8-c11cca71d966'
          report_id = 'a86ce2e5_d5b6_45cc_87e8_c11cca71d966'
          result_id = $resultId
          identifier = $identifier
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

    $identifier=$reportIdBase + 'a86ce2e5-d5b6-45cc-87e8-c11cca71d977'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-a86ce2e5-d5b6-45cc-87e8-c11cca71d977'
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
            ReportId = $identifier
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        Add-RudderVar -Name 'report_data' -Value @{
          component_name = $reportParams['ComponentName']
          component_key = $reportParams['ComponentKey']
          report_id_r = 'a86ce2e5-d5b6-45cc-87e8-c11cca71d977'
          report_id = 'a86ce2e5_d5b6_45cc_87e8_c11cca71d977'
          result_id = $resultId
          identifier = $identifier
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

    $identifier=$reportIdBase + 'a86ce2e5-d5b6-45cc-87e8-c11cca71d978'
    $resultId=([Rudder.Datastate]::GetVar(@('report_data', 'directive_id'))) + '-a86ce2e5-d5b6-45cc-87e8-c11cca71d978'
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
            ReportId = $identifier
            DisableReporting = $false
            TechniqueName = $techniqueName
            ResultId = $resultId
        }
        Add-RudderVar -Name 'report_data' -Value @{
          component_name = $reportParams['ComponentName']
          component_key = $reportParams['ComponentKey']
          report_id_r = 'a86ce2e5-d5b6-45cc-87e8-c11cca71d978'
          report_id = 'a86ce2e5_d5b6_45cc_87e8_c11cca71d978'
          result_id = $resultId
          identifier = $identifier
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

    EndTechniqueCall -Name $techniqueName
}