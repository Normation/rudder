curl --header "X-API-Token: yourToken" \
  --request POST https://rudder.example.com/rudder/api/latest/usermanagement/coverage/johndoe \
  --header "Content-type: application/json" \
  --data @- <<EOF
{
	"permissions" : ["user", "deployer", "inventory"],
	"authz" : ["node_read","node_write"]
}
EOF
