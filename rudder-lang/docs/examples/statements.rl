@format=0

resource deb()

deb state technique()
{
  # list of possible statements
  @info="i am a metadata"
  let rights = "g+x"


  permissions("/tmp").dirs("root", "x$${root}i${user}2","g+w") as outvar
  
  if outvar=~kept => return kept

  case {
    outvar=~repaired  => log_info "repaired",
    outvar=~error => fail "failed agent",
    default => log_info "default case"
  }
}
