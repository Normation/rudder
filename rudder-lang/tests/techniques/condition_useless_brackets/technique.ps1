function Condition-Useless-Brackets {
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

  $class = "any.(((!debian|linux)|ubuntu.windows|(linux|ubuntu)))"
  if (Evaluate-Class $class $local_classes $system_classes) {
    $local_classes = Merge-ClassContext $local_classes $(Dsc-Built-In-Resource -Tag "tagname" -ResourceName "file" -ScriptBlock @'
exists
'@ -componentName "DSC built-in resource" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly).get_item("classes")
  } else {
    _rudder_common_report_na -componentName "DSC built-in resource" -componentKey "tagname" -message "Not applicable" -reportId $reportId -techniqueName $techniqueName -auditOnly:$auditOnly
  }

}
