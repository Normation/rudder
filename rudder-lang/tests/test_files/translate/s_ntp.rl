# This file has been generated with rltranslate
@format=0
@name="NTP Technique"
@description="Configure the NTP"
@version="1.1"
@parameters= [
]
resource NTP_Technique()
NTP_Technique state technique() {
  @component = "Package install"
  package("ntp").install() as package_install_ntp
  @component = "File ensure lines present"
  file("/etc/ntp.conf").ensure_lines_present("server pool.ntp.org") as file_ensure_lines_present__etc_ntp_conf
  @component = "Service restart"
  if file_ensure_lines_present__etc_ntp_conf =~ repaired => service("ntp").restart() as service_restart_ntp
}
