[CmdletBinding()]
param (
[Parameter(Mandatory=$true)]
[string] $action = "run",
[string] $class = $null
)

Write-Error "Not an actual agent"