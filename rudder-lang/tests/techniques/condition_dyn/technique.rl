# Generated from json technique
@format = 0
@name = "condition dyn"
@description = ""
@version = "1.0"
@category = "ncf_techniques"
@parameters = []

resource condition_dyn()

condition_dyn state technique() {
  @component = "Command execution"
  if (${coucou}) => command("pwd").execution() as command_execution_pwd
}
