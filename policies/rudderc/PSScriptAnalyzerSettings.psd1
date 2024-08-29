@{
  IncludeDefaultRules = $True
  Rules = @{
    PSPlaceOpenBrace = @{
      Enable             = $true
      OnSameLine         = $true
      NewLineAfter       = $true
      IgnoreOneLineBlock = $true
    }

    PSPlaceCloseBrace = @{
      Enable             = $true
      NoEmptyLineBefore  = $false
      IgnoreOneLineBlock = $true
      NewLineAfter       = $false
    }
  }

  ExcludeRules = @(
    "PSUseDeclaredVarsMoreThanAssignments",
    "PSAvoidTrailingWhitespace",
    "PSUseConsistentIndentation",
    "PSUseConsistentWhitespace",
    "AvoidUninitializedVariableInModule",
    "PSAvoidUsingWriteHost",
    "PSUseApprovedVerbs",
    "PSAvoidDefaultValueSwitchParameter",
    "PSUseOutputTypeCorrectly",
    "PSUseShouldProcessForStateChangingFunctions",
    "PSAvoidUsingInvokeExpression",
    "PSAvoidUsingPlainTextForPassword",
    "PSAvoidUsingConvertToSecureStringWithPlainText",
    "PSUseSingularNouns",
    "PSUseToExportFieldsInManifest"
  )
}
