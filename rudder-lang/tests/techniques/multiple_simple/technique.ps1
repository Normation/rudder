function Multiple-Simple {
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

  $local_classes = Merge-ClassContext $local_classes $(File-Absent -Target "/tmp" -componentName "File absent" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly).get_item("classes")

  _rudder_common_report_na -componentName "File check exists" -componentKey "/tmp" -message "Not applicable" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly

  $local_classes = Merge-ClassContext $local_classes $(File-Present -Target "/tmp" -componentName "File present" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly).get_item("classes")

  $local_classes = Merge-ClassContext $local_classes $(Directory-Absent -Target "/tmp" -Recursive "false" -componentName "Directory absent" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly).get_item("classes")

  $local_classes = Merge-ClassContext $local_classes $(Directory-Present -Target "/tmp" -componentName "Directory present" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly).get_item("classes")

  _rudder_common_report_na -componentName "Directory check exists" -componentKey "/tmp" -message "Not applicable" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly

}
