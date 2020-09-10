# This file has been generated with rltranslate
@format=0
@name="parameters mult"
@description="technique using multiple parameters"
@category = "ncf_technique"
@version = 0
@parameters=[
@  {"name" = "paramtest", "id" = "d74a03dd-5b0b-4b06-8dcf-b4e0cb387c60", "description" = ""},
@  {"name" = "12", "id" = "6e82d276-1181-4596-8fd8-bc3c007ae9ff", "description" = ""}
@]

resource parameters_mult(paramtest,12)

parameters_mult state technique() {
  @component = "Package absent"
  package("vpn").absent("","","") as package_absent_vpn
}
