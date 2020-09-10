# This file has been generated with rltranslate
@format=0
@name="parameters"
@description="technique using parameters"
@category = "ncf_technique"
@version = 0
@parameters= [
  { "name" = "paramtest", "id" = "d74a03dd-5b0b-4b06-8dcf-b4e0cb387c60", "description" = "" },
]
resource parameters(paramtest)
parameters state technique() {
  @component = "Package absent"
  package("vpn").absent("","","") as package_absent_vpn
}
