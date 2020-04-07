function Technique-By-Rudder {
  [CmdletBinding()]
  param (
      [parameter(Mandatory=$true)]
      [string]$reportId,
      [parameter(Mandatory=$true)]
      [string]$techniqueName,
      [parameter(Mandatory=$true)]
      [string]$TechniqueParameter,
      [switch]$auditOnly
  )

  $local_classes = New-ClassContext
  $resources_dir = $PSScriptRoot + "\resources"

  $local_classes = Merge-ClassContext $local_classes $(Package-Install-Version -PackageName "$($node.properties[apache_package_name])" -PackageVersion "2.2.11" -componentName "Customized component" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly).get_item("classes")

  $class = "windows"
  if (Evaluate-Class $class $local_classes $system_classes) {
    $local_classes = Merge-ClassContext $local_classes $(Command-Execution -Command "Write-Host `"testing special characters ` è &é 'à é `"" -componentName "Command execution" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly).get_item("classes")
  } else {
    _rudder_common_report_na -componentName "Command execution" -componentKey "Write-Host `"testing special characters ` è &é 'à é `"" -message "Not applicable" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly
  }

  $class = "package_install_version_$($node.properties[apache_package_name])_repaired"
  if (Evaluate-Class $class $local_classes $system_classes) {
    $local_classes = Merge-ClassContext $local_classes $(Service-Start -ServiceName "$($node.properties[apache_package_name])" -componentName "Customized component" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly).get_item("classes")
  } else {
    _rudder_common_report_na -componentName "Customized component" -componentKey "$($node.properties[apache_package_name])" -message "Not applicable" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly
  }

  _rudder_common_report_na -componentName "Package install" -componentKey "openssh-server" -message "Not applicable" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly

  $class = "cfengine-community"
  if (Evaluate-Class $class $local_classes $system_classes) {
    $local_classes = Merge-ClassContext $local_classes $(Command-Execution -Command "/bin/echo `"testing special characters ` è &é 'à é `"\" -componentName "Command execution" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly).get_item("classes")
  } else {
    _rudder_common_report_na -componentName "Command execution" -componentKey "/bin/echo `"testing special characters ` è &é 'à é `"\" -message "Not applicable" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly
  }

}
