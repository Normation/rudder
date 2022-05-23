@format=0
@metadata=1
resource Configure_NTP()
Configure_NTP state technique() {
    # state_decl Audit
    !package("ntp").present("","","") as package_present_ntp
}