# This file has been generated with rltranslate
@format=0

@name="parameters mult"
@description="technique using multiple parameters"
@version="1.0"
@parameters=[{"constraints":{"allow_empty_string":false,"allow_whitespace_string":false,"max_length":16384},"id":"d74a03dd-5b0b-4b06-8dcf-b4e0cb387c60","name":"paramtest","type":"string"},{"constraints":{"allow_empty_string":false,"allow_whitespace_string":false,"max_length":16384},"id":"6e82d276-1181-4596-8fd8-bc3c007ae9ff","name":"12","type":"string"}]

resource parameters_mult(paramtest,12)

parameters_mult state technique() {
  @component = "Package absent"
  package("vpn").absent("","","") as package_absent_vpn
}
