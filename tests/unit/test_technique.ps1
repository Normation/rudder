function content_escaping_test {
  [CmdletBinding()]
  param (
      [parameter(Mandatory=$true)]
      [string]$reportIdentifier,
      [parameter(Mandatory=$true)]
      [string]$techniqueName,
      [switch]$auditOnly
  )

  $local_classes = New-ClassContext

  $local_classes = Merge-ClassContext $local_classes $(Package-Install-Version -PackageName "apache2" -PackageVersion "2.2.11" -reportIdentifier $reportIdentifier -techniqueName $techniqueName -auditOnly:$auditOnly)

  $class = "redhat"
  if (Evaluate-Class $class $local_classes $system_classes) {
    $local_classes = Merge-ClassContext $local_classes $(File-Replace-Lines -File "/etc/httpd/conf/httpd.conf" -Line "ErrorLog \"/var/log/httpd/error_log\"" -Replacement "ErrorLog \"/projet/logs/httpd/error_log\"" -reportIdentifier $reportIdentifier -techniqueName $techniqueName -auditOnly:$auditOnly)
  } else {
    _rudder_common_report_na -componentName "File replace lines" -componentKey "/etc/httpd/conf/httpd.conf" -message "Not applicable" -reportIdentifier $reportIdentifier -techniqueName $techniqueName -auditOnly:$auditOnly)
  }

}
