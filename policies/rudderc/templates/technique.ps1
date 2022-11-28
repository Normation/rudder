function {{ id }}
{
    [CmdletBinding()]
    param (
        [parameter(Mandatory = $true)]
        [string]$reportId,
        [parameter(Mandatory = $true)]
        [string]$techniqueName,
        [parameter(Mandatory = $true)]
        [string]$TechniqueParameter,
        [Rudder.PolicyMode]$policyMode
    )
    BeginTechniqueCall -Name $techniqueName
    $reportIdBase = $reportId.Substring(0, $reportId.Length - 1)
    $localContext = [Rudder.Context]::new($techniqueName)
    $localContext.Merge($system_classes)

{% if has_modules %}
    $modules_dir = $PSScriptRoot + "\modules"
{% endif %}

{% for m in methods %}
    $reportId=$reportIdBase+{{ m.index }}
    $componentKey = "{{ m.component_key }}"
    $reportParams = @{
    ClassPrefix = ([Rudder.Condition]::canonify(("{{ m.class_prefix }}_" + $componentKey)))
    ComponentKey = $componentKey
    ComponentName = "{{ m.component_name }}"
    PolicyMode = $policyMode
    ReportId = $reportId
    DisableReporting = {{ m.disable_reporting }}
    TechniqueName = $techniqueName
    }
    {% match m.condition %}
    {% when Some with (cond) %}
    $class = "{{ cond }}"
    if ($localContext.Evaluate($class)) {
        $methodParams = @{
            {{ m.args }}
        }
        $call = {{ m.name }} @methodParams
        $methodContext = Compute-Method-Call @reportParams -MethodCall $call
        $localContext.merge($methodContext)
    } else {
        Rudder-Report-NA @reportParams
    }
    {% when None %}
    $methodParams = @{
        {{ m.args }}
    }
    $call = {{ m.name }} @methodParams
    $methodContext = Compute-Method-Call @reportParams -MethodCall $call
    $localContext.merge($methodContext)
    {% endmatch %}
{% endfor %}

    EndTechniqueCall -Name $techniqueName
}