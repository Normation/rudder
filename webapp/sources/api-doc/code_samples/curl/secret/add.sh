add.json:

{
	"name" : "secret-password",
	"description" : "Password of my super secret user account",
	"value" : "nj-k;EO32!kFWewn2Nk,u",
}

curl --header "X-API-Token: yourToken" --request POST https://rudder.example.com/rudder/api/latest/secret --header "Content-type: application/json" --data @add.json

