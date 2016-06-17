param(
  [Parameter(Mandatory=$true)][string]$cfengine_version=$(throw "CFEngine version is mandatory, please provide a value, in the form major.minor (e.g., 3.6)"),
  [Parameter(Mandatory=$true)][string]$framework_path=$(throw "Framework path is mandatory, please provide a value."),
  [Parameter(Mandatory=$true)][string]$nn_directory=$(throw "nn_directory is mandatory, please provide a value, in the form [common|local]\nn_directory (e.g. local\30_generic_methods")
)
$path="$framework_path\$nn_directory"

# Create an empty exclude list

$excludes = @()
# get all files with a match in given folder
$matching_files=Get-ChildItem -recurse $path | Select-String -Pattern '^#[ \t]*@agent_version[ \t](>=|<)*([0-9]+)\.([0-9]+)*' -AllMatches
# Split version numbers
if ($cfengine_version -match '([0-9]+)\.([0-9]+)') {
  $cfengine_major=$matches[1]
  $cfengine_minor=$matches[2]
}
$matching_files | ForEach {
  $major=$_.Matches.Groups[2].Value
  $minor=$_.Matches.Groups[3].Value
  if ($_.Matches.Groups[1].Value -eq ">=") {
    if ( $cfengine_major -lt $major -Or ( $cfengine_major -eq $major -And $cfengine_minor -lt $minor ) ) {
      $excludes += $_.Path
    }
  } elseif ($_.Matches.Groups[1].Value -eq "<") {
    if ( $cfengine_major -gt $major -Or ( $cfengine_major -eq $major -And $cfengine_minor -ge $minor ) ) {
      $excludes += $_.Path
    }
  }
}

# return all files in folder, minus the exclude list
Get-ChildItem $path -rec -filter *.cf | where { (!$_.PSIsContainer) -and ($excludes -notcontains $_.FullName) } |  Select-Object -expandproperty FullName | Foreach-Object { $_.replace($framework_path, "") }
