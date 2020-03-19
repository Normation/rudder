@format=0

@name="Configure NTP"
@description="test"
@version="1.0"
@parameters=[]

resource Configure_NTP()

Configure_NTP state technique() {
  @component = "Package present"
  package("ntp").present("","","") as package_present_ntp
}