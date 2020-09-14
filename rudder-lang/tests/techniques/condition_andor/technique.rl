# Generated from json technique
@format = 0
@name = "condition andor"
@description = "Kernel simplest"
@category = "ncf_techniques"
@version = 0
@parameters = []

resource condition_andor()

condition_andor state technique() {
  @component = "Kernel module loaded"
  if (debian_family|linux)|ubuntu.windows|(linux.ubuntu) => kernel_module("test").loaded() as kernel_module_loaded_test
}
