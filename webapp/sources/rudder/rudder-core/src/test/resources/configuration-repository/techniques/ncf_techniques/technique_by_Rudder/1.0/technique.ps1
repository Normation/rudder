function Technique-By-Rudder {
  [CmdletBinding()]
  param (
      [parameter(Mandatory=$true)]
      [string]$reportId,
      [parameter(Mandatory=$true)]
      [string]$techniqueName,
      [parameter(Mandatory=$true)]
      [string]$technique_parameter,
      [switch]$auditOnly
  )
  $reportIdBase = $reportId.Substring(0,$reportId.Length-1)
  $local_classes = New-ClassContext
  $resources_dir = $PSScriptRoot + "\resources"

  $reportId=$reportIdBase+"id1"

  $class = "(debian)"
  if (Evaluate-Class $class $local_classes $system_classes) {
    $local_classes = Merge-ClassContext $local_classes $(Package-Install-Version -PackageName "$($node.properties[apache_package_name])" -PackageVersion "2.2.11" -componentName "Customized component" -Report:$true -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly).get_item("classes")
  } else {
    _rudder_common_report_na -componentName "Customized component" -componentKey "$($node.properties[apache_package_name])" -message "Not applicable" -Report:$true -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly
  }

  $reportId=$reportIdBase+"id2"

  $class = "(debian.windows)"
  if (Evaluate-Class $class $local_classes $system_classes) {
    $local_classes = Merge-ClassContext $local_classes $(Command-Execution -Command "Write-Host `"testing special characters ` è &é 'à é `"" -componentName "Command execution" -Report:$false -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly).get_item("classes")
  } else {
    _rudder_common_report_na -componentName "Command execution" -componentKey "Write-Host `"testing special characters ` è &é 'à é `"" -message "Not applicable" -Report:$false -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly
  }

  $reportId=$reportIdBase+"id3"

  $class = "package_install_version_" + $(Canonify-Class $($node.properties[apache_package_name])) + "_repaired"
  if (Evaluate-Class $class $local_classes $system_classes) {
    $local_classes = Merge-ClassContext $local_classes $(Service-Start -ServiceName "$($node.properties[apache_package_name])" -componentName "Customized component" -Report:$true -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly).get_item("classes")
  } else {
    _rudder_common_report_na -componentName "Customized component" -componentKey "$($node.properties[apache_package_name])" -message "Not applicable" -Report:$true -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly
  }

  $reportId=$reportIdBase+"id4"

  _rudder_common_report_na -componentName "Package install" -componentKey "openssh-server" -message "Not applicable" -Report:$true -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly

  $reportId=$reportIdBase+"id5"

  $class = "cfengine-community"
  if (Evaluate-Class $class $local_classes $system_classes) {
    $local_classes = Merge-ClassContext $local_classes $(Command-Execution -Command "/bin/echo `"testing special characters ` è &é 'à é `"\" -componentName "Command execution" -Report:$true -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly).get_item("classes")
  } else {
    _rudder_common_report_na -componentName "Command execution" -componentKey "/bin/echo `"testing special characters ` è &é 'à é `"\" -message "Not applicable" -Report:$true -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly
  }

  $reportId=$reportIdBase+"id6"

  $class = "dsc"
  if (Evaluate-Class $class $local_classes $system_classes) {
    $local_classes = Merge-ClassContext $local_classes $(Package-State-Windows -PackageName "vim" -componentName "Package state windows" -Report:$true -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly).get_item("classes")
  } else {
    _rudder_common_report_na -componentName "Package state windows" -componentKey "vim" -message "Not applicable" -Report:$true -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly
  }

}
