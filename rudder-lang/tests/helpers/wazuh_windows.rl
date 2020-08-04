# Generated from json technique
@format = 0
@name = "Wazuh windows install"
@description = ""
@version = "1.0"
@parameters = []

resource Wazuh_windows_install()

Wazuh_windows_install state technique() {
  @component = "File copy from Rudder shared folder"
  file("wazuh-agent-3.7.2-1.msi").from_shared_folder("C:\\Program Files\\Rudder\\tmp\\exe\\wazuh-agent-3.7.2-1.msi", "sha256") as file_from_shared_folder_C__Program_Files_Rudder_tmp_exe_wazuh_agent_3_7_2_1_msi

  @component = "Variable string from command"
  variable("wazuh", "is_installed").string_from_command("test-path \"C:\\Program Files (x86)\\ossec-agent\\ossec-agent.exe\"") as variable_string_from_command_is_installed

  @component = "Condition from variable match"
  condition("wazuh_installed").from_variable_match("wazuh.is_installed", "True") as condition_from_variable_match_wazuh_installed

  @component = "Command execution"
  if (wazuh_installed =~ false) => command("\"C:\\Program Files\\Rudder\\tmp\\exe\\wazuh-agent-3.7.2-1.msi\" /q").execution() as command_execution__C__Program_Files_Rudder_tmp_exe_wazuh_agent_3_7_2_1_msi___q
}
