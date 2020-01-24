# This file has been generated with rltranslate
@format=0

@name="parameters mult err"
@description="technique using multiple parameters"
@version="1.0"
@parameters=[{"constraints":{"allow_empty_string":false,"allow_whitespace_string":false,"max_length":16384},"id":"ac15b0bd-a226-4ad7-b93c-5515cae507a1","name":"${coucou}","type":"string"}]

resource parameters_mult_err(__coucou_)

parameters_mult_err state technique() {
  @component = "Package absent"
  package("vpn").absent("","","") as package_absent_vpn
}
