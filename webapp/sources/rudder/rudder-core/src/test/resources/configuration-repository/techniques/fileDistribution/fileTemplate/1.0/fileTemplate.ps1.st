function fileTemplate_&RudderUniqueID& {
  [CmdletBinding()]
  param (
      [parameter(Mandatory=$true)]  [string]$reportId,
      [parameter(Mandatory=$true)]  [string]$techniqueName,
      [switch]$auditOnly
  )
    
    $trackingkey = @(
    &TRACKINGKEY:{directiveId |
    "&directiveId&" };separator=","& )

    $sources = @(
    &FILE_TEMPLATE_TEMPLATE:{source |
    "&source&" };separator=","& )

    $isRaws = @(
    &FILE_TEMPLATE_RAW_OR_NOT:{isRaw |
    "&isRaw&" };separator=","& )

    $rawSources = @(
    &FILE_TEMPLATE_RAW_TEMPLATE:{rawSource |
    "&rawSource&" };separator=","& )

    $destinations = @(
    &FILE_TEMPLATE_AGENT_DESTINATION_PATH:{destination |
    "&destination&" };separator=","& )

    $posthooks = @(
    &FILE_TEMPLATE_TEMPLATE_POST_HOOK_COMMAND:{destination |
    "&destination&" };separator=","& )

    $HashCheckType = "sha256"
    $local_classes = New-ClassContext

    for ($i=0; $i -lt $trackingkey.length; $i++) {
      $destinationsCanon = Canonify-Class($destinations[$i])
      $templatesFolder = "C:\Program Files\Rudder\tmp\templates"
      $templateDestinations = "${templatesFolder}\${destinationsCanon}"

      $componentName = "Templates location"
      #Check for templates folder
      $local_classes = Merge-ClassContext $local_classes $(Directory-Create -target $templatesFolder -ReportId $trackingkey[$i] -componentName $componentName -componentKey $destinations[$i] -TechniqueName $techniqueName -auditOnly:$auditOnly -Report:$true)
      #No need to check for permissions, they are well defined by the inheritence of Rudder folder
      if ($isRaws[$i] -eq "Raw") {
        #First, write the input in a file
        $componentName = "Load Template from a file or text input"
        $local_classes = Merge-ClassContext $local_classes $(File-Enforce-Content -File $templateDestinations -Lines $rawSources[$i] -ReportId $trackingkey[$i] -Enforce $true  -componentName $componentName -componentKey $destinations[$i] -TechniqueName $techniqueName -auditOnly:$auditOnly -Report:$true)
      } else {
        #First, get the template from the server
        $componentName = "Load Template from a file or text input"
        $local_classes = Merge-ClassContext $local_classes $(File-From-Shared-Folder -Source $sources[$i] -Destination $templateDestinations -HashType $HashCheckType -ReportId $trackingkey[$i] -TechniqueName $techniqueName  -componentName $componentName -componentKey $destinations[$i] -auditOnly:$auditOnly -Report:$true)
      }
      $componentName = "Expand template"
      #Expand the template
      $local_classes = Merge-ClassContext $local_classes $(File-From-Template-Mustache -SourceTemplate $templateDestinations -Destination $destinations[$i] -ReportId $trackingkey[$i] -TechniqueName $techniqueName -componentName $componentName -componentKey $destinations[$i] -auditOnly:$auditOnly -Report:$true)

      #Posthook execution
      $class_prefix = "file_from_template_${destinationsCanon}_repaired"
      $componentName = "Posthook"
      if ($posthooks[$i] -ne "" -And $local_classes["classes"].contains($class_prefix)) {
        $local_classes = Merge-ClassContext $local_classes $(Command-Execution -CommandName $posthooks[$i] -ComponentKey $destinations[$i] -ComponentName $componentName -Report:$true -auditOnly:$auditOnly)
      } else {
        if ($auditOnly) { $state = [ComplianceStatus]::audit_na } else { $state = [ComplianceStatus]::result_na }
        if ($posthooks[$i] -eq "") {
          $PostHookString = "No posthook defined"
        } else {
          $PostHookString = "No posthook execution needed"
        }
        $classes = _rudder_common_report -TechniqueName $techniqueName -Status $state -ReportId $reportId -ComponentName $componentName -ComponentKey $destinations[$i] -Message $PostHookString -report:$true
      }

      #Sections non applicables
      $state = if ($auditOnly) { [ComplianceStatus]::audit_na } else { [ComplianceStatus]::result_na }
      $StringNa = "Not applicable"
      $componentName = "Put permissions"
      $classes = _rudder_common_report -TechniqueName $techniqueName  -Status $state -ReportId $reportId -ComponentName $componentName -ComponentKey $destinations[$i] -Message $StringNa -report:$true
      $componentName = "Templates directory permissions"
      $classes = _rudder_common_report -TechniqueName $techniqueName  -Status $state -ReportId $reportId -ComponentName $componentName -ComponentKey $destinations[$i] -Message $StringNa -report:$true
    }
}

