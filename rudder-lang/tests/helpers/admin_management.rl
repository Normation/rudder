# Generated from json technique
@format = 0
@name = "Administrators Management"
@description = "test"
@category = "ncf_technique"
@version = "1.0"
@parameters = [
@  { "name" = "login_name", "id" = "bbe964b5-5b0a-436d-90ed-8faf7c841b11", "description" = "Login for the user" }
@]

resource Administrators_Management(login_name)

Administrators_Management state technique() {
  # let p0 = "${login_name}"
  @component = "User present"
  user("RMP").present() as user_present___login_name_

  # let p0 = "${login_name}:${login_name} FULL"
  @component = "File content"
  file("/etc/sudoers").content("COUCOU", "true") as file_lines_present__etc_sudoers

  @component = "Service restart"
  if (user_present___login_name_ =~ repaired) => service("sshd").restart() as service_restart_sshd
}
