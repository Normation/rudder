curl --header "X-API-Token: yourToken" \
  --request PUT https://rudder.example.com/rudder/api/latest/usermanagement/johndoe \
  --header "Content-type: application/json" \
  --data @- <<EOF
{
	"isPreHashed" : false,
	"username"    : "",
  "password"    : "Safer password",
	"permissions" : ["user", "deployer", "inventory"]
}
EOF