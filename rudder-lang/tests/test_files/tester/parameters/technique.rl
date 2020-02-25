# This file has been generated with rltranslate
@format=0

@name="parameters"
@description="technique using parameters"
@version="1.0"
@parameters=[{"constraints":{"allow_empty_string":false,"allow_whitespace_string":false,"max_length":16384},"id":"d74a03dd-5b0b-4b06-8dcf-b4e0cb387c60","name":"paramtest","type":"string"}]

resource parameters(paramtest)

parameters state technique() {
  @component = "Package absent"
  package("vpn").absent("","","") as package_absent_vpn
}
