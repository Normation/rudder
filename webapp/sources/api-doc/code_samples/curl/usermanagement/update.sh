curl --header "X-API-Token: yourToken" \
  --request POST https://rudder.example.com/rudder/api/latest/usermanagement/update/johndoe \
  --header "Content-type: application/json" \
  --data @- <<EOF
{
	"isPreHashed" : false,
	"username"    : "",
	"password"    : "Safer password",
	"permissions" : ["user", "deployer", "inventory"]
}
EOF
