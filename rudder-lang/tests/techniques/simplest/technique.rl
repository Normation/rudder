# This file has been generated with rltranslate
@format=0

@name="simplest"
@description="rudderlang simplest for a complete loop"
@version="1.0"

resource simplest()

simplest state technique() {
  @component = "File absent"
  if debian => file("tmp").absent() as file_absent_tmp

  log_info "plop"
  fail "test"
}
