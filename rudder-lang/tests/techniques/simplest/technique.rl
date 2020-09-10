# This file has been generated with rltranslate
@format=0
@name="simplest"
@description="rudderlang simplest for a complete loop"
@category = "ncf_technique"
@version = 0
@parameters = []

resource simplest()

simplest state technique() {
  @component = "File absent"
  if debian => file("tmp").absent() as file_absent_tmp

  log_info "plop"
  fail "test"
}
