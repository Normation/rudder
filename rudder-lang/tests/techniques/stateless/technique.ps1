function Stateless {
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

  _rudder_common_report_na -componentName "Variable string escaped" -componentKey "pref.name" -message "Not applicable" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly

  $local_classes = Merge-ClassContext $local_classes $(Permissions-Ntfs -User "admin" -Path "/var/test" -Propagationpolicy "ThisFolderOnly" -Rights "rxw" -Accesstype "Allow" -componentName "Permissions NTFS" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly).get_item("classes")

  _rudder_common_report_na -componentName "Permissions (non recursive)" -componentKey "/file/path" -message "Not applicable" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly

  _rudder_common_report_na -componentName "Audit from osquery" -componentKey "query;" -message "Not applicable" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly

  _rudder_common_report_na -componentName "Monitoring template" -componentKey "vision" -message "Not applicable" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly

  _rudder_common_report_na -componentName "Monitoring parameter" -componentKey "paramname" -message "Not applicable" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly

  _rudder_common_report_na -componentName "Variable string escaped" -componentKey "escaped.var" -message "Not applicable" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly
}
