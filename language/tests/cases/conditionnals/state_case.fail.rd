@format=0
@metadata=1
resource Configure_NTP()
Configure_NTP state technique() {
  case {
    ubuntu => file("/tmp").doesnotexist(),
    debian => file("/tmp").doesnoteither()
  }
}