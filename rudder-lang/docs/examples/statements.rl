@format=0

rights = "g+x"

resource configure_NTP()

configure_NTP state technique()
{
  # list of possible statements
  @info="i am a metadata"
  is_debian = os =~ debian

  if is_debian =~ true => return kept # stop here if system is debian

  File("/tmp").permissions("root", "x$${root}i${user}2","g+w") as outvar

  case {
    outvar =~ kept => return kept,
    outvar =~ repaired  => log "info: repaired",
    is_debian =~ true && outvar =~ error => fail "failed agent"
    default
  }
}