# This file has been generated with rltranslate
@format=0
@name="multiple_simple"
@description=""
@category = "ncf_techniques"
@version = 0
@parameters=[]

resource multiple_simple()

multiple_simple state technique() {
  @component = "File absent"
  file("/tmp").absent() as file_absent__tmp
  @component = "File check exists"
  file("/tmp").check_exists() as file_check_exists__tmp
  @component = "File present"
  file("/tmp").present() as file_present__tmp
  @component = "Directory absent"
  directory("/tmp").absent("false") as directory_absent__tmp
  @component = "Directory present"
  directory("/tmp").present() as directory_present__tmp
  @component = "Directory check exists"
  directory("/tmp").check_exists() as directory_check_exists__tmp
}
