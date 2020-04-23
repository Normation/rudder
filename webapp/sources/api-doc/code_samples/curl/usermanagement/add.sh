add.json:

{
	"isPreHashed" : false,
	"username" : "John Doe",
  "password" : "passwdWillBeStoredHashed",
	"role" : ["user"]
}

curl --header "X-API-Token: yourToken" --request POST https://rudder.example.com/rudder/api/latest/usermanagement --header "Content-type: application/json" --data @add.json

