@format=0
# This file has been generated with rltranslate 

@name="Configure NTP"
@description="test"
@version="1.0"
@parameters=[]

resource Configure_NTP()

Configure_NTP state technique() {
  @component = "Package present"
  package("ntp").present("","","") as package_present_ntp
}
