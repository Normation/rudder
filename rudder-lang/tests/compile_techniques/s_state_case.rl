@format=0
@metadata=1
resource Configure_NTP()
Configure_NTP state technique() {
  case {
    # this case makes no sense, testing purpose
    system=~ubuntu => file("/tmp").absent(),
    system=~debian => file("/tmp").present(),
    default => log_info "ok"
  }
}
