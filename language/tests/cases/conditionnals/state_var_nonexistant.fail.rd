@format=0
@metadata=1
resource Configure_NTP()
Configure_NTP state technique() {
    if outvar =~ kept => noop
}