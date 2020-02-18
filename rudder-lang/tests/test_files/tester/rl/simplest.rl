# This file has been generated with rltranslate
@format=0

@name="simplest"
@description="rudderlang simplest for a complete loop"
@version="1.0"
@parameters=[]

resource simplest()

simplest state technique() {
  @component = "File absent"
  if ms_dos => file("tmp").absent() as file_absent_tmp
}
