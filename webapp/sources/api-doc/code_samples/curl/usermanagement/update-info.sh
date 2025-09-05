curl --header "X-API-Token: yourToken" \
  --request POST https://rudder.example.com/rudder/api/latest/usermanagement/update/info/johndoe \
  --header "Content-type: application/json" \
  --data @- <<EOF
{
	"name"     : "John Doe",
	"email"    : "john.doe@example.com",
	"otherInfo": {
		"phone": "+1234"
	}
}
EOF
