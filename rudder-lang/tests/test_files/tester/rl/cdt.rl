# This file has been generated with rltranslate
@format=0

@name="cdt"
@description="rudderlang cdt for a complete loop"
@version="1.0"
@parameters=[]

resource cdt()

cdt state technique() {
  @component = "File absent"
  if debian => file("tmp").absent() as file_absent_tmp
}
