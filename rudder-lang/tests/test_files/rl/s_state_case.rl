@format=0
@metadata=1
resource Configure_NTP()
Configure_NTP state technique() {
  case {
    ubuntu => file("/tmp").absent(),
    os:debian => file("/tmp").present(),
    default => log "info: ok"
  }
}
