# This file has been generated with rltranslate
@format=0

@name="condition any bsd"
@description="Kernel simplest"
@version="1.0"
@parameters=[]

resource condition_anybsd()

condition_anybsd state technique() {
  @component = "Kernel module loaded"
  if dragonfly|freebsd|netbsd|openbsd => kernel_module("test").loaded() as kernel_module_loaded_test
}
