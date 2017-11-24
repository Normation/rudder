function Technique-By-Rudder {
  [CmdletBinding()]
  param (
      [parameter(Mandatory=$true)]
      [string]$reportId,
      [parameter(Mandatory=$true)]
      [string]$techniqueName,
      [switch]$auditOnly
  )

  $local_classes = New-ClassContext

  $local_classes = Merge-ClassContext $local_classes $(Package-Install-Version -Package-Name "${node.properties[apache_package_name]}" -Package-Version "2.2.11" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly).get_item("classes")

  $class = "windows"
  if (Evaluate-Class $class $local_classes $system_classes) {
    $local_classes = Merge-ClassContext $local_classes $(Command-Execution -Command "Write-Host `"test`"" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly).get_item("classes")
  } else {
    _rudder_common_report_na -componentName "Command execution" -componentKey "Write-Host `"test`"" -message "Not applicable" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly
  }

  $class = "package_install_version_${node.properties[apache_package_name]}_repaired"
  if (Evaluate-Class $class $local_classes $system_classes) {
    $local_classes = Merge-ClassContext $local_classes $(Service-Start -Service-Name "${node.properties[apache_package_name]}" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly).get_item("classes")
  } else {
    _rudder_common_report_na -componentName "Service start" -componentKey "${node.properties[apache_package_name]}" -message "Not applicable" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly
  }

  _rudder_common_report_na -componentName "Package install" -componentKey "openssh-server" -message "Not applicable" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly

  $class = "cfengine-community"
  if (Evaluate-Class $class $local_classes $system_classes) {
    $local_classes = Merge-ClassContext $local_classes $(Command-Execution -Command "/bin/echo `"test`"" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly).get_item("classes")
  } else {
    _rudder_common_report_na -componentName "Command execution" -componentKey "/bin/echo `"test`"" -message "Not applicable" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly
  }

}