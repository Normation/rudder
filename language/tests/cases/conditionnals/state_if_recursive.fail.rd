@format=0
@metadata=1
resource Configure_NTP()
Configure_NTP state technique() {
    ## pstate_declaration
    file("/tmp").absent() as abs_file
    if abs_file =~ kept => if abs_file =~ kept => noop
}