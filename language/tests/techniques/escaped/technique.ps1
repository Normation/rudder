function Escaped {
  [CmdletBinding()]
  param (
      [parameter(Mandatory=$true)]
      [string]$reportId,
      [parameter(Mandatory=$true)]
      [string]$techniqueName,
      [switch]$auditOnly
  )

  $local_classes = New-ClassContext
  $resources_dir = $PSScriptRoot + "\resources"

  # missing conditions
  $local_classes = Merge-ClassContext $local_classes $(Command-Execution -Command "echo \"Hello de Lu\" > /tmp/myfile-${sys.host}.txt" -componentName "Command execution" -reportId $reportId -techniqueName $techniqueName -Report:$true -AuditOnly:$auditOnly).get_item("classes")
  _rudder_common_report_na -ComponentName "Command execution result" -ComponentKey "rpm -qi gpg-pubkey-\\*|grep -E ^Packager|grep Innoflair" -Message "Not applicable" -ReportId $ReportId -TechniqueName $TechniqueName -Report:$true -AuditOnly:$AuditOnly
  _rudder_common_report_na -componentName "File replace lines" -componentKey "/etc/default/grub" -message "Not applicable" -reportId $reportId -techniqueName $techniqueName -Report:$true -AuditOnly:$auditOnly
}
