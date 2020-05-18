update.json:

{
	"displayBar":true,
	"displayLabel":false,
	"labelText":"Production",
	"barColor":{
		"red":1,
		"blue":1,
		"green":1,
		"alpha":1
	},
	"labelColor":{
		"red":0,
		"blue":0,
		"green":0,
		"alpha":1
	},
	"wideLogo":{
		"enable":true
	},
	"smallLogo":{
		"enable":true
	},
	"displayBarLogin":true,
	"displayMotd":true,
	"motd":"Welcome, please sign in:"
}

curl --header "X-API-Token: yourToken" --request POST https://rudder.example.com/rudder/api/latest/branding --header "Content-type: application/json" --data @update.json

