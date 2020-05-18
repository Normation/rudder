update.json:

{
	"validatedUsers": ["John Do", "Jane Doe"]
}

curl --header "X-API-Token: yourToken" --request POST https://rudder.example.com/rudder/api/latest/validatedUsers --header "Content-type: application/json" --data @update.json

