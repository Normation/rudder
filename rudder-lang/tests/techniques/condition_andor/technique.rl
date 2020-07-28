# This file has been generated with rltranslate
@format=0

@name="condition andor"
@description="Kernel simplest"
@version="1.0"
@parameters=[]

resource condition_andor()

condition_andor state technique() {
  @component = "Kernel module loaded"
  if (debian|linux)|ubuntu&windows|(linux&ubuntu) => kernel_module("test").loaded() as kernel_module_loaded_test
}
