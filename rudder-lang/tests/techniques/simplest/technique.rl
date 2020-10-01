# Generated from json technique
@format = 0
@name = "simplest"
@description = "rudderlang simplest for a complete loop"
@version = "1.0"
@parameters = []

resource simplest()

simplest state technique() {
  @component = "File absent"
  if (debian_family) => file("tmp").absent() as file_absent_tmp
}
