# This file has been generated with rltranslate
@format=0

@name="variables"
@description="technique using variables"
@version="1.0"
@parameters= [
  { "name": "coucou", "id": "ac15b0bd-a226-4ad7-b93c-5515cae507a1", "constraints": {"allow_empty_string":false,"allow_whitespace_string":false,"max_length":16384} },
]

resource variables_technique(coucou)

variables_technique state technique() {
  @component = "Package absent"
  package("vpn").absent("","","") as package_absent_vpn
  @component = "Variable string"
  variable("hello","world").string("valhelloworld") as variable_string_world
}
