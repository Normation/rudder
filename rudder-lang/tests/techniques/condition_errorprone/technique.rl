# This file has been generated with rltranslate

@name="condition_errorprone"
@description=""
@version = 0
@category = "ncf_techniques"
@parameters= []

resource condition_errorprone()

condition_errorprone state technique() {
  @component = "Command execution"
  if (dragonfly|openbsd) => command("pwd").execution() as command_execution_pwd
}
