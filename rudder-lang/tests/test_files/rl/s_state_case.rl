@format=0
@metadata=1
resource Configure_NTP()
Configure_NTP state technique() {
  case {
    # this case makes no sense, testing purpose
    os=~ubuntu => file("/tmp").absent(),
    os=~os:debian => file("/tmp").present(),
    default => log "info: ok"
  }
}
