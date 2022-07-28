# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2021 Normation SAS

function copyFileFromSharedFolder_&RudderUniqueID& {
  [CmdletBinding()]
  param (
      [parameter(Mandatory=$true)]  [string]$reportId,
      [parameter(Mandatory=$true)]  [string]$techniqueName,
      [switch]$auditOnly
  )

    $trackingkey = @(
    &TRACKINGKEY:{directiveId |
    "&directiveId&" };separator=","& )

    $names = @(
    &COPYFILE_NAME:{name |
    "&name&" };separator=","& )

    $destinations = @(
    &COPYFILE_DESTINATION:{destination |
    "&destination&" };separator=","& )

    $posthooks = @(
    &COPYFILE_POST_HOOK_COMMAND:{posthook |
    "&posthook&" };separator=","& )

    $SharedFiles   = "&SHARED_FILES_FOLDER&"
    $componentName = "Copy file"
    $postHookComponent = "Post-modification hook"
    $HashCheckType = "sha256"
    $local_classes = New-ClassContext

    for ($i=0; $i -lt $trackingkey.length; $i++) {
      $local_classes = Merge-ClassContext $local_classes $(File-From-Shared-Folder -Source $names[$i] -Destination $destinations[$i] -HashType $HashCheckType -ReportId $trackingkey[$i] -TechniqueName $techniqueName -auditOnly:$auditOnly -Report:$true -componentName $componentName -componentKey $destinations[$i] -ClassPrefix $componentName)
      $class_prefix  = "copy_file_"
      $class_prefix += Canonify-Class $destinations[$i]
      $class_prefix += "_repaired"

      #Posthook execution
      if ((-not([String]::IsNullOrEmpty($posthooks[$i]))) -And ($local_classes["classes"].contains($class_prefix))) {
        $local_classes = Merge-ClassContext $local_classes $(Command-Execution -CommandName $posthooks[$i] -ComponentKey $destinations[$i] -ComponentName $postHookComponent -ReportId $trackingkey[$i] -Techniquename $techniqueName -Report:$true -auditOnly:$auditOnly)
      } else {
        if ($auditOnly) {
          $state = [ComplianceStatus]::audit_na
        } else {
          $state = [ComplianceStatus]::result_na
        }
        if ([String]::IsNullOrEmpty($posthooks[$i])) {
          $PostHookString = "No posthook defined"
        } else {
          $PostHookString = "No posthook execution needed"
        }
        $classes = _rudder_common_report -TechniqueName $techniqueName -Status $state -ReportId $reportId -ComponentName $postHookComponent -ComponentKey $destinations[$i] -Message $PostHookString -report:$true -ClassPrefix $techniqueName
      }
    }
}

