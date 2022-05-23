@format=0

@metadata=1
resource Configure_NTP()
Configure_NTP state technique() {
  case {
    system=~debian => file("/tmp").absent()
  }
}