# This file has been generated with rltranslate
@format=0

@name="condition_errorprone"
@description=""
@version="1.0"
@parameters=[]

resource condition_errorprone()

condition_errorprone state technique() {
  @component = "Command execution"
  if dragonfly|openbsd => command("pwd").execution() as command_execution_pwd
}
