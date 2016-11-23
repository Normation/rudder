param(
  [Parameter(Mandatory=$true)][string]$cfengine_version=$(throw "CFEngine version is mandatory, please provide a value, in the form major.minor (e.g., 3.6)"),
  [Parameter(Mandatory=$true)][string]$framework_path=$(throw "Framework path is mandatory, please provide a value."),
  [Parameter(Mandatory=$true)][string]$nn_directory=$(throw "nn_directory is mandatory, please provide a value, in the form [common|local]\nn_directory (e.g. local\30_generic_methods")
)
$path="$framework_path\$nn_directory"

# We consider that the Windows agent doesn't have any capability

$version_regex="([0-9]+)\.([0-9]+)*"
$agent_version_regex="^#[\s]*@agent_version[\s]*(>=|<)[\s]*$($version_regex)"
# capture capabilities, and remainder of line
$agent_requirements_regex="^#[\s]*@agent_requirements[\s]*""capabilities""[\s]*:[\s]*\[[\s]*(.*)[\s]*\](.*)"
$remainder_regex="[\s]*(&&|\|\|)[\s]*""agent_version""[\s]*(>=|<)[\s]*$($version_regex)"

# Create an empty exclude list
$excludes = @()

# Split CFEngine version numbers
if ($cfengine_version -match '([0-9]+)\.([0-9]+)') {
  $cfengine_major=$matches[1]
  $cfengine_minor=$matches[2]
}


# get all files with requirement
$req_files=Get-ChildItem -recurse $path | Select-String -Pattern $agent_requirements_regex -AllMatches
$req_files | ForEach {
  $capability=$_.Matches.Groups[1].Value

  # Split capabilities, to get a list of all of them for a file. Need to use -split for complete string
  $capabilities=($capability -split "[\s]*,[\s]*" | ForEach-Object { $_.Replace("""", "")})
  # Windows agent doesn't have any capabilities, so only if all start by "!" then we can include it
  #$capabilities
  $may_include = ((@($capabilities| where { ! $_.StartsWith("!") }).Count) -eq 0)

  $remainder=$_.Matches.Groups[2].Value
  # if the remainder is not empty, then we have a comparator on agent verions
  if ($remainder.Length -gt 0) {
    $valid_remainder = $remainder -match $remainder_regex
    if ($valid_remainder) {
      $logical_operator = $matches[1]
      $comparator = $matches[2]
      $major = $matches[3]
      $minor = $matches[4]

      $agent_version_condition_matched = $false
      if ($comparator -eq ">=") {
        if ( $cfengine_major -gt $major -Or ( $cfengine_major -eq $major -And $cfengine_minor -ge $minor ) ) {
          $agent_version_condition_matched = $true
        }
      } elseif ($comparator -eq "<") {
        if ( $cfengine_major -lt $major -Or ( $cfengine_major -eq $major -And $cfengine_minor -lt $minor ) ) {
          $agent_version_condition_matched = $true
        }
      }

      # combine the remainder and the capabilities
      if ($logical_operator -eq "||") {
        $may_include = $may_include -or $agent_version_condition_matched
      } else {
        $may_include = $may_include -and $agent_version_condition_matched
      }
    }
  }

  if (-not $may_include) {
    $excludes += $_.Path
  }

}


# get all files with a match in given folder
$matching_files=Get-ChildItem -recurse $path | Select-String -Pattern $agent_version_regex -AllMatches

$matching_files | ForEach {
  $major=$_.Matches.Groups[2].Value
  $minor=$_.Matches.Groups[3].Value
  $comparator = $_.Matches.Groups[1].Value
  if ($comparator -eq ">=") {
    if ( $cfengine_major -lt $major -Or ( $cfengine_major -eq $major -And $cfengine_minor -lt $minor ) ) {
      $excludes += $_.Path
    }
  } elseif ($comparator -eq "<") {
    if ( $cfengine_major -gt $major -Or ( $cfengine_major -eq $major -And $cfengine_minor -ge $minor ) ) {
      $excludes += $_.Path
    }
  }
}

# return all files in folder, minus the exclude list
Get-ChildItem $path -rec -filter *.cf | where { (!$_.PSIsContainer) -and ($excludes -notcontains $_.FullName) } |  Select-Object -expandproperty FullName | Foreach-Object { $_.replace($framework_path, "") }
