@format=0
@metadata=1
resource Configure_NTP()
Configure_NTP state technique() {
    file("/tmp").absent() as abs_file
    file("/tmp").present() as pres_file
    if (((abs_file =~ kept | pres_file !~ kept) & pres_file =~ error) & pres_file =~ error) => noop
}