update.json:

{
	"isPreHashed" : false,
	"username"    : "",
  "password"    : "Safer password",
	"role"        : ["user", "deployer", "inventory"]
}

curl --header "X-API-Token: yourToken" --request PUT https://rudder.example.com/rudder/api/latest/usermanagement/Toto --header "Content-type: application/json" --data @update.json

