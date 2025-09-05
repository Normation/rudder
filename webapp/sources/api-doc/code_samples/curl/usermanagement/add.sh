curl --header "X-API-Token: yourToken" \
  --request POST https://rudder.example.com/rudder/api/latest/usermanagement \
  --header "Content-type: application/json" \
  --data @- <<EOF
{
	"isPreHashed": false,
	"username"   : "johndoe",
	"password"   : "secret_password",
	"permissions": ["user"],
	"name"       : "John Doe",
	"email"      : "john.doe@example.com",
	"otherInfo"  : {
		"phone" : "+1234"
	}
}
EOF
